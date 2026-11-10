package net.typeblog.shelter.connections;

import android.content.Context;
import net.typeblog.shelter.security.SecretFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owned only by the work-profile VPN process. IDs are never used as filesystem paths. */
public final class ProfileStore {
    private final SecretFile file;
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();
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
    public String summaries() {
        JSONArray array = new JSONArray();
        try {
            for (ConnectionProfile value : profiles.values()) array.put(new JSONObject().put("id", value.id)
                    .put("name", value.name).put("type", value.type).put("server", value.server)
                    .put("typeLabel", value.typeLabel()));
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
        String previous = selected;
        profiles.remove(id);
        if (id.equals(selected)) selected = null;
        try { persist(); } catch (RuntimeException e) { profiles.put(id, removed); selected = previous; throw e; }
    }
    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (ConnectionProfile profile : profiles.values()) array.put(profile.toJson());
            JSONObject root = new JSONObject().put("schema", 1).put("selectedId", selected == null ? JSONObject.NULL : selected).put("profiles", array);
            file.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) { throw new IllegalStateException("Не удалось сохранить подключения"); }
    }
}
