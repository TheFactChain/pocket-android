package net.typeblog.shelter.connections;

import android.content.Context;
import net.typeblog.shelter.security.SecretFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owned only by the work-profile VPN process. IDs are never used as filesystem paths. */
public final class ProfileStore {
    public static final String SELECTED_GONE = "selected-gone";
    private final SecretFile file;
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private String selected;

    public ProfileStore(Context context) {
        file = new SecretFile(context, "connections.v1.enc");
        byte[] data = file.read();
        if (data == null) return;
        try {
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (root.getInt("schema") != 1) throw new IllegalArgumentException();
            JSONArray values = root.getJSONArray("profiles");
            for (int i = 0; i < values.length(); i++) {
                ConnectionProfile profile = ConnectionProfile.fromJson(values.getJSONObject(i));
                if (profiles.put(profile.id, profile) != null) throw new IllegalArgumentException();
            }
            JSONArray subs = root.optJSONArray("subscriptions");
            for (int i = 0; subs != null && i < subs.length(); i++) {
                Subscription sub = Subscription.fromJson(subs.getJSONObject(i));
                if (subscriptions.put(sub.id, sub) != null) throw new IllegalArgumentException();
            }
            selected = root.isNull("selectedId") ? null : root.getString("selectedId");
            if (selected != null && !profiles.containsKey(selected)) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalStateException("Настройки повреждены. Сеть заблокирована, исходные данные сохранены.");
        } finally { java.util.Arrays.fill(data, (byte) 0); }
    }

    public ConnectionProfile selected() { return profiles.get(selected); }
    public String selectedId() { return selected; }
    public ConnectionProfile get(String id) {
        ConnectionProfile value = profiles.get(id);
        if (value == null) throw new IllegalArgumentException("Подключение не найдено");
        return value;
    }
    public List<ConnectionProfile> all() { return new ArrayList<>(profiles.values()); }
    public String summaries() {
        JSONArray array = new JSONArray();
        try {
            for (ConnectionProfile value : profiles.values()) array.put(new JSONObject().put("id", value.id)
                    .put("name", value.name).put("type", value.type).put("server", value.server)
                    .put("typeLabel", value.typeLabel()).put("details", value.details())
                    .put("subscription", value.subscription));
        } catch (org.json.JSONException e) { throw new IllegalStateException("Не удалось прочитать подключения"); }
        return array.toString();
    }
    public String save(ConnectionProfile value) {
        ConnectionProfile previous = profiles.put(value.id, value);
        try { persist(); } catch (RuntimeException e) { if (previous == null) profiles.remove(value.id); else profiles.put(value.id, previous); throw e; }
        return value.id;
    }
    public void select(String id) {
        get(id);
        String previous = selected;
        selected = id;
        try { persist(); } catch (RuntimeException e) { selected = previous; throw e; }
    }
    public void delete(String id) {
        ConnectionProfile removed = get(id);
        Map<String, ConnectionProfile> before = new LinkedHashMap<>(profiles);
        String previous = selected;
        profiles.remove(id);
        if (id.equals(selected)) selected = null;
        try { persist(); } catch (RuntimeException e) { profiles.clear(); profiles.putAll(before); selected = previous; throw e; }
    }

    // ---- Subscriptions ----

    /** Provider details for the interface, without the secret link. */
    public String subscriptionSummaries() {
        JSONArray array = new JSONArray();
        for (Subscription sub : subscriptions.values()) array.put(sub.toJson(false));
        return array.toString();
    }
    public List<Subscription> subscriptions() { return new ArrayList<>(subscriptions.values()); }
    public Subscription subscription(String id) {
        Subscription value = subscriptions.get(id);
        if (value == null) throw new IllegalArgumentException("Подписка не найдена");
        return value;
    }
    public Subscription findSubscription(String url) {
        for (Subscription sub : subscriptions.values()) if (sub.url.equals(url)) return sub;
        return null;
    }

    /**
     * Replaces the subscription's servers with the downloaded ones. Servers that stay keep their ids,
     * so the selected one stays selected; a selected server the provider removed is kept until the user
     * picks another, so the connection never changes silently.
     * @return the ids of servers whose settings changed (the caller restarts the core if one is in use);
     *         {@link #SELECTED_GONE} is added when the selected server is no longer in the subscription
     */
    public List<String> applySubscription(Subscription sub, List<ConnectionProfile> incoming) {
        Map<String, ConnectionProfile> before = new LinkedHashMap<>(profiles);
        Map<String, Subscription> subsBefore = new LinkedHashMap<>(subscriptions);
        Map<String, Deque<String>> reusable = new HashMap<>();
        for (ConnectionProfile old : profiles.values()) {
            if (old.subscription.equals(sub.id)) reusable.computeIfAbsent(key(old), k -> new ArrayDeque<>()).add(old.id);
        }
        List<ConnectionProfile> fresh = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (ConnectionProfile value : incoming) {
            Deque<String> ids = reusable.get(key(value));
            String id = ids == null || ids.isEmpty() ? UUID.randomUUID().toString() : ids.poll();
            ConnectionProfile kept = value.withIdentity(id, sub.id);
            ConnectionProfile old = profiles.get(id);
            if (old != null && !old.toJson().toString().equals(kept.toJson().toString())) changed.add(id);
            fresh.add(kept);
        }
        // Rebuild in order: other connections first as they were, then this subscription's servers.
        LinkedHashMap<String, ConnectionProfile> next = new LinkedHashMap<>();
        for (ConnectionProfile old : profiles.values()) if (!old.subscription.equals(sub.id)) next.put(old.id, old);
        for (ConnectionProfile value : fresh) next.put(value.id, value);
        if (selected != null && !next.containsKey(selected)) {
            next.put(selected, profiles.get(selected));
            changed.add(SELECTED_GONE);
        }
        profiles.clear();
        profiles.putAll(next);
        subscriptions.put(sub.id, sub);
        try { persist(); }
        catch (RuntimeException e) { profiles.clear(); profiles.putAll(before); subscriptions.clear(); subscriptions.putAll(subsBefore); throw e; }
        return changed;
    }

    /** Keeps the servers and records the failure, so the last working list stays in use. */
    public void updateSubscription(Subscription sub) {
        subscription(sub.id);
        Subscription previous = subscriptions.put(sub.id, sub);
        try { persist(); } catch (RuntimeException e) { subscriptions.put(sub.id, previous); throw e; }
    }

    public void deleteSubscription(String id) {
        subscription(id);
        Map<String, ConnectionProfile> before = new LinkedHashMap<>(profiles);
        Subscription removed = subscriptions.remove(id);
        String previous = selected;
        profiles.values().removeIf(value -> value.subscription.equals(id));
        if (selected != null && !profiles.containsKey(selected)) selected = null;
        try { persist(); }
        catch (RuntimeException e) { profiles.clear(); profiles.putAll(before); subscriptions.put(id, removed); selected = previous; throw e; }
    }

    private static String key(ConnectionProfile value) {
        return value.type + "|" + value.server + "|" + value.port + "|" + value.name;
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (ConnectionProfile profile : profiles.values()) array.put(profile.toJson());
            JSONArray subs = new JSONArray();
            for (Subscription sub : subscriptions.values()) subs.put(sub.toJson(true));
            JSONObject root = new JSONObject().put("schema", 1).put("selectedId", selected == null ? JSONObject.NULL : selected)
                    .put("profiles", array).put("subscriptions", subs);
            file.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) { throw new IllegalStateException("Не удалось сохранить подключения"); }
    }
}
