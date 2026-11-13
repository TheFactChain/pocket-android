package net.typeblog.shelter.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Network;
import android.net.VpnService;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.UserManager;
import androidx.core.content.ContextCompat;
import net.typeblog.shelter.R;
import net.typeblog.shelter.connections.ConnectionProfile;
import net.typeblog.shelter.connections.ProfileImporter;
import net.typeblog.shelter.connections.ProfileStore;
import net.typeblog.shelter.connections.Subscription;
import net.typeblog.shelter.services.IPocketVpnService;
import net.typeblog.shelter.ui.OpenPocketActivity;
import io.nekohasekai.libbox.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** The system lockdown survives this process. Only this process owns connection secrets. */
public class PocketVpnService extends VpnService {
    public static final String ACTION_CONTROL = "pocket.vpn.CONTROL";
    private static final String ACTION_START = "pocket.vpn.START";
    private static final String CHANNEL = "pocket-network";
    private static final int NOTIFICATION = 901;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    /** Subscription downloads: slow network work never blocks the core's thread. */
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private volatile String snapshot = "{\"policyState\":\"UNCONFIRMED\",\"connectionState\":\"NO_SELECTION\"}";
    private volatile boolean closed;
    private ProfileStore store;
    private String storeError;
    private String connectionState = "NO_SELECTION", error = "", runningId;
    private long lastCheckTime;
    private ProbeHealth health=new ProbeHealth();
    private int generation, retries, addressCursor, probeFailures;
    private AndroidPlatform platform;
    private Network lastNetwork;
    private CommandServer server;
    private CommandClient client;
    private ScheduledFuture<?> restartTask, probeTask, probeDeadline, ipTask;
    private volatile String ipStatus = "", ipObserved = "";
    private boolean nativeReady, xrayRunning;

    private static String firstIpv4(InetAddress[] addresses) {
        for (InetAddress address : addresses) if (address instanceof java.net.Inet4Address) return address.getHostAddress();
        throw new IllegalStateException("Server DNS unavailable");
    }

    private final IPocketVpnService.Stub binder = new IPocketVpnService.Stub() {
        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Private VPN controller");
            return super.onTransact(code, data, reply, flags);
        }
        @Override public String getPocketState() { return currentState(); }
        @Override public String listConnections() { return call(() -> requireStore().summaries()); }
        @Override public String readConnection(String id) { return call(() -> requireStore().get(id).toJson().toString()); }
        @Override public String saveConnection(String json) {
            return call(() -> {
                ConnectionProfile value = ConnectionProfile.fromJson(new JSONObject(json));
                String id = requireStore().save(value);
                if (id.equals(store.selectedId())) { cancelRestart(); stopCore(); startSelected(); }
                publish();
                return id;
            });
        }
        @Override public void activateConnection(String id) {
            call(() -> {
                ProtectionPolicy.ensure(PocketVpnService.this);
                boolean other = !id.equals(requireStore().selectedId());
                store.select(id);
                // The user confirmed switching to another server, so its address becomes the one to keep;
                // choosing the same server again never accepts a changed address.
                if (other) { prefs().edit().remove(PREF_IP_CONFIRMED).remove(PREF_IP_PENDING).commit(); ipStatus = ""; }
                cancelRestart(); stopCore(); retries=0; addressCursor=0; startSelected(); return null;
            });
        }
        @Override public void deleteConnection(String id) {
            call(() -> {
                boolean active = id.equals(requireStore().selectedId());
                store.delete(id);
                if (active) { cancelRestart(); stopCore(); connectionState="NO_SELECTION"; error=""; }
                publish(); return null;
            });
        }
        @Override public void setDirectRu(boolean enabled) {
            call(() -> {
                if (enabled == directRu()) return null;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DIRECT_RU, enabled).commit();
                if (store != null && store.selected() != null) { cancelRestart(); stopCore(); retries=0; startSelected(); }
                publish(); return null;
            });
        }
        @Override public void setIpControl(boolean enabled, int minutes) {
            call(() -> {
                if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Интервал: от 1 до 1440 минут");
                boolean wasBlocked = ipBlocked();
                prefs().edit().putBoolean(PREF_IP_CONTROL, enabled).putInt(PREF_IP_MINUTES, minutes).commit();
                if (!enabled) { prefs().edit().remove(PREF_IP_PENDING).commit(); ipStatus = ""; }
                if (wasBlocked && !enabled) { cancelRestart(); stopCore(); retries=0; startSelected(); }
                else if (enabled && client != null) scheduleIpCheck(generation, 0);
                publish(); return null;
            });
        }
        @Override public void confirmIp() {
            call(() -> {
                String pending = prefs().getString(PREF_IP_PENDING, "");
                if (pending.isEmpty()) return null;
                prefs().edit().putString(PREF_IP_CONFIRMED, pending).remove(PREF_IP_PENDING).commit();
                ipStatus = "OK"; ipObserved = pending;
                cancelRestart(); stopCore(); retries=0; startSelected(); return null;
            });
        }
        @Override public void checkNow() {
            call(() -> {
                if (runningId == null || client == null) return null;
                if (probeTask != null) probeTask.cancel(false);
                final int ticket = generation;
                probeTask = worker.schedule(() -> probe(ticket), 0, TimeUnit.MILLISECONDS);
                return null;
            });
        }
        @Override public String listSubscriptions() { return call(() -> requireStore().subscriptionSummaries()); }
        @Override public String addSubscription(String text, boolean allowDevice) {
            String url = Subscription.subscriptionUrl(text);
            if (url == null) throw new IllegalArgumentException("Нужна ссылка подписки https://…");
            Subscription existing = call(() -> requireStore().findSubscription(url));
            Subscription sub = existing != null ? existing : Subscription.create(UUID.randomUUID().toString(), url);
            return refresh(allowDevice ? sub.withHwid(true) : sub, existing == null);
        }
        @Override public String refreshSubscription(String id, boolean allowDevice) {
            Subscription sub = call(() -> requireStore().subscription(id));
            return refresh(allowDevice ? sub.withHwid(true) : sub, false);
        }
        @Override public void deleteSubscription(String id) {
            call(() -> {
                ConnectionProfile current = requireStore().selected();
                store.deleteSubscription(id);
                if (current != null && store.selected() == null) { cancelRestart(); stopCore(); connectionState="NO_SELECTION"; error=""; }
                publish(); return null;
            });
        }
        @Override public void refreshDueSubscriptions() { network.execute(PocketVpnService.this::refreshDue); }
        @Override public String pingConnections(String subscriptionId, boolean force) {
            JSONArray servers = call(() -> {
                JSONArray chosen = new JSONArray();
                for (ConnectionProfile value : requireStore().all()) {
                    if (value.subscription.equals(subscriptionId)) chosen.put(new JSONObject().put("id", value.id).put("outbound", value.toOutbound()));
                }
                return chosen;
            });
            long now = android.os.SystemClock.elapsedRealtime();
            JSONObject result = new JSONObject();
            try {
                synchronized (pings) {
                    boolean fresh = !force && servers.length() > 0;
                    for (int i = 0; i < servers.length(); i++) {
                        long[] known = pings.get(servers.getJSONObject(i).getString("id"));
                        if (known == null || now - known[1] > PING_CACHE_MS) { fresh = false; break; }
                        result.put(servers.getJSONObject(i).getString("id"), known[0]);
                    }
                    if (fresh || servers.length() == 0) return result.toString();
                }
                Network real = call(() -> platform != null ? platform.getUnderlyingNetwork() : null);
                if (real == null) real = getSystemService(android.net.ConnectivityManager.class).getActiveNetwork();
                if (real == null) throw new IllegalStateException("Нет сети для проверки серверов.");
                JSONObject measured = new JSONObject(checkServers(new JSONObject().put("network", real.getNetworkHandle())
                        .put("servers", servers).toString()));
                synchronized (pings) {
                    for (java.util.Iterator<String> keys = measured.keys(); keys.hasNext();) {
                        String id = keys.next();
                        pings.put(id, new long[]{measured.getLong(id), now});
                    }
                }
                return measured.toString();
            } catch (org.json.JSONException e) {
                throw new IllegalStateException("Проверка серверов не выполнена.");
            }
        }
    };

    private static final long PING_CACHE_MS = 10 * 60_000L;
    /** Last check per server id: {delay ms or -1, elapsedRealtime of the check}. */
    private final java.util.Map<String, long[]> pings = new java.util.HashMap<>();

    /** Runs the request through each server in the separate «:ping» process (see PingService). */
    private String checkServers(String request) {
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        net.typeblog.shelter.services.IPocketPing[] remote = new net.typeblog.shelter.services.IPocketPing[1];
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, IBinder service) {
                remote[0] = net.typeblog.shelter.services.IPocketPing.Stub.asInterface(service);
                ready.countDown();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) { }
        };
        if (!bindService(new Intent(this, PingService.class), connection, BIND_AUTO_CREATE)) {
            throw new IllegalStateException("Проверка серверов не запустилась.");
        }
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || remote[0] == null) throw new IllegalStateException("Проверка серверов не запустилась.");
            return remote[0].test(request);
        } catch (InterruptedException | android.os.RemoteException e) {
            throw new IllegalStateException("Проверка серверов прервалась.");
        } finally {
            unbindService(connection);
        }
    }

    // shortcut: fixed client names; if a panel blocks them too, add a per-subscription choice of client.
    private static final String[] SUBSCRIPTION_AGENTS = {"Happ/3.11.0", "v2rayNG/1.10.16",
            "Pocket/" + net.typeblog.shelter.BuildConfig.VERSION_NAME};

    /** The device id providers count against their limit: random per Pocket install, never a hardware id. */
    private java.util.Map<String, String> deviceHeaders() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String hwid = prefs.getString("subscription_hwid", null);
        if (hwid == null) {
            hwid = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("subscription_hwid", hwid).commit();
        }
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("x-hwid", hwid);
        headers.put("x-device-os", "Android");
        headers.put("x-ver-os", android.os.Build.VERSION.RELEASE);
        // The provider shows this name in the user's list of devices.
        headers.put("x-device-model", "Pocket " + android.os.Build.MODEL);
        return headers;
    }

    /** Downloads and stores a subscription; runs on the caller's thread so the core keeps running meanwhile. */
    private String refresh(Subscription sub, boolean isNew) {
        long now = System.currentTimeMillis();
        Subscription.Response response = null;
        ProfileImporter.Result parsed = null;
        try {
            // Panels choose the format by User-Agent and send unknown apps a single «app not supported» stub,
            // so ask as the clients whose formats Pocket reads: Happ (Xray JSON), then v2rayNG (links).
            IllegalArgumentException firstProblem = null;
            // The device id goes only to providers the user allowed; panels with a device limit
            // (Remnawave «X-Hwid-Active») otherwise answer with a stub, and the user is asked.
            java.util.Map<String, String> device = sub.hwid ? deviceHeaders() : java.util.Collections.<String, String>emptyMap();
            for (String agent : SUBSCRIPTION_AGENTS) {
                response = Subscription.download(sub.url, agent, device);
                try { parsed = ProfileImporter.parse(response.body); break; }
                catch (IllegalArgumentException e) {
                    if (!sub.hwid && "true".equalsIgnoreCase(response.headers.get("x-hwid-active"))) {
                        throw new Subscription.Failure(Subscription.NEEDS_DEVICE + " Разреши отправку случайного идентификатора Pocket, чтобы получить серверы.");
                    }
                    if (firstProblem == null) firstProblem = e;
                }
            }
            if (parsed == null && sub.hwid && ProfileImporter.decodeBase64List(response.body).contains("@0.0.0.0:")) {
                throw new Subscription.Failure("Провайдер не выдал серверы этому устройству. Возможно, исчерпан лимит устройств подписки: освободи место в боте или личном кабинете провайдера.");
            }
            if (parsed == null) throw firstProblem;
        } catch (java.io.IOException | IllegalArgumentException e) {
            // Other network errors may carry the secret link in their message.
            final String text = e instanceof Subscription.Failure ? e.getMessage()
                    : e instanceof IllegalArgumentException ? "В подписке нет поддерживаемых серверов. " + e.getMessage()
                    : "Подписка не загрузилась. Проверь интернет и ссылку.";
            if (!isNew) call(() -> { store.updateSubscription(sub.failed(text, now)); return null; });
            throw new IllegalArgumentException(text);
        }
        Subscription fresh = sub.updated(response.headers, response.body, now).withSkipped(parsed.warnings);
        final ProfileImporter.Result parsedServers = parsed;
        return call(() -> {
            List<String> changed = requireStore().applySubscription(fresh, parsedServers.profiles);
            boolean gone = changed.remove(ProfileStore.SELECTED_GONE);
            if (store.selectedId() != null && changed.contains(store.selectedId())) { cancelRestart(); stopCore(); retries=0; startSelected(); }
            publish();
            JSONObject result = new JSONObject().put("id", fresh.id).put("title", fresh.toJson(false).getString("title"))
                    .put("count", parsedServers.profiles.size()).put("skipped", parsedServers.warnings.size()).put("selectedGone", gone);
            if (!parsedServers.warnings.isEmpty()) result.put("warning", parsedServers.warnings.get(0));
            return result.toString();
        });
    }

    /** Background update by the provider's interval; failures keep the last working list. */
    private void refreshDue() {
        if (closed) return;
        List<Subscription> due = new java.util.ArrayList<>();
        try {
            List<Subscription> all = call(() -> store == null ? new java.util.ArrayList<Subscription>() : store.subscriptions());
            long now = System.currentTimeMillis();
            for (Subscription sub : all) if (sub.due(now)) due.add(sub);
        } catch (RuntimeException e) { return; }
        for (Subscription sub : due) {
            try { refresh(sub, false); } catch (RuntimeException e) { android.util.Log.w("PocketVPN", "Subscription update failed"); }
        }
    }

    private static final String PREFS = "pocket_routing", PREF_DIRECT_RU = "direct_ru";
    private List<String> russianNetworks;

    /** «Российские сайты напрямую»: on by default, as in Pocket Windows. */
    private boolean directRu() { return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_DIRECT_RU, false); }
    private static final String PREF_IP_CONTROL = "ip_control", PREF_IP_MINUTES = "ip_minutes",
            PREF_IP_CONFIRMED = "ip_confirmed", PREF_IP_PENDING = "ip_pending";
    private android.content.SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private boolean ipBlocked() { return prefs().getBoolean(PREF_IP_CONTROL, false) && !prefs().getString(PREF_IP_PENDING, "").isEmpty(); }
    private String ipChangedText() {
        String was = prefs().getString(PREF_IP_CONFIRMED, ""), now = prefs().getString(PREF_IP_PENDING, "");
        return "Внешний IP изменился" + (was.isEmpty() ? "" : " с " + was) + " на " + now + ". Интернет копий закрыт: подтверди новый адрес в Настройках Pocket.";
    }

    private void scheduleIpCheck(int ticket, long delay) {
        if (!prefs().getBoolean(PREF_IP_CONTROL, false) || closed) return;
        if (ipTask != null) ipTask.cancel(false);
        ipTask = worker.schedule(() -> checkIp(ticket), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Address of the protected exit as one service sees it, through Pocket's own tunnel. Android may delay
     * the timer while the phone sleeps; no wake lock is held for it. Provider rules that send some sites
     * through other exits are not covered by one address.
     */
    private void checkIp(int ticket) {
        if (closed || ticket != generation || client == null || !prefs().getBoolean(PREF_IP_CONTROL, false)) return;
        long interval = prefs().getInt(PREF_IP_MINUTES, 30) * 60_000L;
        if (!"CONNECTED".equals(connectionState)) { scheduleIpCheck(ticket, Math.min(interval, 5000)); return; }
        // The request runs off the worker so binder calls are never held up by a slow check service.
        network.execute(() -> {
            String ip = null;
            try { ip = publicIp(); } catch (Exception ignored) { }
            String seen = ip;
            if (!closed) worker.execute(() -> ipChecked(ticket, seen, interval));
        });
    }

    private void ipChecked(int ticket, String ip, long interval) {
        if (closed || ticket != generation || client == null || !prefs().getBoolean(PREF_IP_CONTROL, false)) return;
        if (ip == null) { ipStatus = "UNCONFIRMED"; publish(); scheduleIpCheck(ticket, Math.min(interval, 60_000)); return; }
        ipObserved = ip;
        String confirmed = prefs().getString(PREF_IP_CONFIRMED, "");
        // shortcut: the first address seen after enabling is kept as the one to hold; the owner has not decided this yet.
        if (confirmed.isEmpty()) { prefs().edit().putString(PREF_IP_CONFIRMED, ip).commit(); confirmed = ip; }
        if (confirmed.equals(ip)) { ipStatus = "OK"; publish(); scheduleIpCheck(ticket, interval); return; }
        prefs().edit().putString(PREF_IP_PENDING, ip).commit();
        ipStatus = "CHANGED";
        cancelRestart(); stopCore();
        connectionState = "IP_CHANGED"; error = ipChangedText(); publish();
    }

    private String publicIp() throws Exception {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        Network tunnel = null;
        for (Network network : cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
            // Android 10 does not tell the owner; in the work profile the only VPN is Pocket's, which is then the active network.
            if (android.os.Build.VERSION.SDK_INT >= 30 ? caps.getOwnerUid() == Process.myUid() : network.equals(cm.getActiveNetwork())) tunnel = network;
        }
        if (tunnel == null) return null;
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) tunnel.openConnection(new java.net.URL("https://api.ipify.org"));
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setUseCaches(false);
        try (java.io.InputStream in = connection.getInputStream()) {
            byte[] data = new byte[64]; int length = 0;
            for (int n; length < data.length && (n = in.read(data, length, data.length - length)) > 0;) length += n;
            String text = new String(data, 0, length, java.nio.charset.StandardCharsets.US_ASCII).trim();
            // Only an address literal counts; anything else is «not confirmed», never a change.
            boolean literal = text.matches("(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])")
                    || (text.contains(":") && text.matches("[0-9a-fA-F:]{2,45}") && InetAddress.getByName(text) instanceof java.net.Inet6Address);
            return literal ? text : null;
        } finally { connection.disconnect(); }
    }

    private List<String> russianNetworks() throws java.io.IOException {
        if (russianNetworks == null) {
            try (java.io.Reader reader = new java.io.InputStreamReader(getAssets().open("ru-ipv4.txt"), java.nio.charset.StandardCharsets.US_ASCII)) {
                russianNetworks = PocketConfig.parseNetworks(reader);
            }
        }
        return russianNetworks;
    }

    public static void start(android.content.Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, PocketVpnService.class).setAction(ACTION_START));
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Защищённая сеть Pocket", NotificationManager.IMPORTANCE_LOW));
        worker.execute(() -> {
            try { store = new ProfileStore(this); }
            catch (RuntimeException e) { storeError="Защищённые настройки недоступны. Исходные данные сохранены."; error=storeError; connectionState="ERROR"; }
            publish();
        });
        network.scheduleWithFixedDelay(this::refreshDue, 1, 30, TimeUnit.MINUTES);
    }

    @Override public IBinder onBind(Intent intent) {
        if (ACTION_CONTROL.equals(intent.getAction())) return binder;
        return super.onBind(intent);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ProtectionPolicy.isOwner(this)) { stopSelf(); return START_NOT_STICKY; }
        showNotification(client != null ? statusText(store == null ? null : store.selected()) : "Pocket проверяет защиту рабочего профиля");
        worker.execute(() -> {
            if (closed) return;
            // Opening Pocket starts the service again; a tunnel already running the selected server is kept,
            // so work apps do not lose their connections and Pocket does not wait for a new handshake.
            ConnectionProfile selected = store == null ? null : store.selected();
            if (selected != null && client != null && selected.id.equals(runningId)) { publish(); return; }
            restart();
        });
        return START_STICKY;
    }

    private <T> T call(Callable<T> operation) {
        if (closed) throw new IllegalStateException("Служба Pocket остановлена");
        try { return worker.submit(operation).get(15, TimeUnit.SECONDS); }
        catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            if (cause instanceof IllegalArgumentException) throw (IllegalArgumentException)cause;
            if (cause instanceof SecurityException) throw (SecurityException)cause;
            throw new IllegalStateException("Операция Pocket не завершена. Проверь состояние профиля.");
        }
    }

    private ProfileStore requireStore() {
        if (store == null) throw new IllegalStateException(storeError == null ? "Разблокируй рабочий профиль" : storeError);
        return store;
    }

    private void startSelected() {
        start(this);
        connectionState="CONNECTING"; error=""; lastCheckTime=0;
        publish();
    }

    private void initializeNative() throws Exception {
        if (nativeReady) return;
        go.Seq.setContext(getApplicationContext());
        File base = new File(getFilesDir(), "core");
        File temporary = new File(getCacheDir(), "core");
        if ((!base.isDirectory() && !base.mkdirs()) || (!temporary.isDirectory() && !temporary.mkdirs())) throw new IllegalStateException("Private core directories unavailable");
        SetupOptions options = new SetupOptions();
        options.setBasePath(base.getAbsolutePath());
        options.setWorkingPath(base.getAbsolutePath());
        options.setTempPath(temporary.getAbsolutePath());
        options.setFixAndroidStack(true);
        options.setCommandServerListenPort(0);
        options.setCommandServerSecret(UUID.randomUUID().toString());
        options.setDebug(false);
        options.setOomKillerDisabled(true);
        options.setPowerReportEnabled(false);
        Libbox.setup(options);
        if (!Libbox.version().startsWith("1.14.4")) throw new IllegalStateException("Unexpected core version");
        nativeReady=true;
    }

    private void restart() {
        cancelRestart();
        stopCore();
        if (closed) return;
        try {
            ProtectionPolicy.ensure(this);
            requireStore();
            ConnectionProfile selected = store.selected();
            if (selected == null) { connectionState="NO_SELECTION"; error=""; publish(); return; }
            if (!getSystemService(UserManager.class).isUserUnlocked()) throw new IllegalStateException("Profile locked");
            // A detected address change keeps the internet closed until the user confirms it.
            if (ipBlocked()) { connectionState="IP_CHANGED"; error=ipChangedText(); publish(); return; }
            initializeNative();
            platform = new AndroidPlatform(this);
            lastNetwork = platform.getUnderlyingNetwork();
            final int ticket = generation;
            platform.setNetworkChangeListener(() -> {
                if (!closed) worker.execute(() -> {
                    if (ticket != generation || platform == null) return;
                    Network observed = platform.getUnderlyingNetwork();
                    if (!Objects.equals(lastNetwork, observed)) { lastNetwork=observed; connectionState="RECONNECTING"; publish(); scheduleRestart(300); }
                });
            });
            if (lastNetwork == null) { connectionState="NO_NETWORK"; error="Нет доступной сети"; publish(); return; }
            connectionState="CONNECTING"; error=""; publish();
            // Only the VPN server's hostname is resolved outside the tunnel.
            InetAddress[] addresses = lastNetwork.getAllByName(selected.server);
            if (addresses.length == 0) throw new IllegalStateException("Server DNS unavailable");
            String resolved = addresses[Math.floorMod(addressCursor++,addresses.length)].getHostAddress();
            JSONObject carrier = null;
            if (selected.type.equals("vless")) {
                // VLESS is carried by Xray (see XrayConfig); its sockets leave the tunnel as the core's do.
                Network network = lastNetwork;
                String user = UUID.randomUUID().toString(), password = UUID.randomUUID().toString();
                int port;
                try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = probe.getLocalPort(); }
                String xray = XrayConfig.create(selected, host -> host.equals(selected.server) ? resolved : firstIpv4(network.getAllByName(host)),
                        port, user, password);
                io.nekohasekai.pocketxray.Pocketxray.setProtector(fd -> protect(fd));
                xrayRunning = true; // set first: stopCore must stop a start that failed halfway
                io.nekohasekai.pocketxray.Pocketxray.start(xray);
                carrier = XrayConfig.socksOutbound(port, user, password);
            }
            String configuration = PocketConfig.create(selected, resolved, directRu() ? russianNetworks() : null, carrier);
            Libbox.checkConfig(configuration);
            server = Libbox.newCommandServer(serverHandler(), platform);
            server.start();
            server.startOrReloadService(configuration, new OverrideOptions());
            runningId = selected.id;
            CommandClientOptions options = new CommandClientOptions();
            options.addCommand(Libbox.CommandOutbounds);
            client = Libbox.newCommandClient(clientHandler(ticket), options);
            client.connect();
            retries=0; probeFailures=0;
            probeTask = worker.schedule(() -> probe(ticket), 1200, TimeUnit.MILLISECONDS);
            scheduleIpCheck(ticket, 4000);
            publish();
        } catch (Throwable failure) {
            stopCore();
            connectionState="RECONNECTING";
            error="Не удалось подключиться. Проверь сеть и параметры сервера.";
            android.util.Log.w("PocketVPN", "Core start failed: " + failure.getClass().getSimpleName());
            publish(); scheduleRetry();
        }
    }

    private void probe(int ticket) {
        if (closed || ticket != generation || client == null) return;
        if (!ProtectionPolicy.confirmed(this)) { connectionState="ERROR"; error="Системная защита не подтверждена"; publish(); stopCore(); return; }
        if(!health.begin()){
            probeTask=worker.schedule(() -> probe(ticket),1000,TimeUnit.MILLISECONDS);
            return;
        }
        try {
            client.urlTest("proxy");
            probeDeadline=worker.schedule(() -> {
                if (ticket != generation) return;
                if (health.pending()) {
                    health.expire();
                    probeFailures++;
                    connectionState="RECONNECTING";
                    error="Сервер пока не подтвердил связь. Прямой доступ заблокирован.";
                    publish();
                    if (probeFailures >= 3) { scheduleRetry(); return; }
                }
                probeTask=worker.schedule(() -> probe(ticket),30,TimeUnit.SECONDS);
            },17,TimeUnit.SECONDS);
        } catch (Exception e) {
            connectionState="RECONNECTING"; error="Связь восстанавливается"; publish(); scheduleRetry();
        }
    }

    private CommandClientHandler clientHandler(final int ticket) {
        return new CommandClientHandler() {
            @Override public void connected() {}
            @Override public void disconnected(String ignored) { if(!closed) worker.execute(() -> { if(ticket==generation){ connectionState="RECONNECTING"; error="Связь восстанавливается"; publish(); scheduleRetry(); } }); }
            @Override public void writeOutbounds(OutboundGroupItemIterator values) {
                while (values.hasNext()) {
                    OutboundGroupItem item=values.next();
                    if (!"proxy".equals(item.getTag())) continue;
                    final long time=item.getURLTestTime();
                    if (!closed) worker.execute(() -> {
                        if(ticket!=generation) return;
                        if(health.observe(time,android.os.SystemClock.elapsedRealtime())){
                            lastCheckTime=time*1000; connectionState="CONNECTED"; error=""; probeFailures=0; publish();
                        }
                    });
                }
            }
            @Override public void clearLogs() {}
            @Override public void initializeClashMode(StringIterator modes,String current) {}
            @Override public void setDefaultLogLevel(int level) {}
            @Override public void updateClashMode(String mode) {}
            @Override public void writeConnectionEvents(ConnectionEvents events) {}
            @Override public void writeGroups(OutboundGroupIterator groups) {}
            @Override public void writeLogs(LogIterator logs) {}
            @Override public void writeStatus(StatusMessage status) {}
        };
    }

    private CommandServerHandler serverHandler() {
        return new CommandServerHandler() {
            @Override public int connectSSHAgent() { throw new UnsupportedOperationException(); }
            @Override public SystemProxyStatus getSystemProxyStatus() { return new SystemProxyStatus(); }
            @Override public void serviceReload() { if(!closed) worker.execute(() -> scheduleRestart(0)); }
            @Override public void serviceStop() { if(!closed) worker.execute(() -> { stopCore(); connectionState="ERROR"; error="Туннель остановлен. Прямой доступ заблокирован."; publish(); }); }
            @Override public void setSystemProxyEnabled(boolean enabled) { throw new UnsupportedOperationException(); }
            @Override public void triggerNativeCrash() { throw new UnsupportedOperationException(); }
            @Override public void writeDebugMessage(String message) {}
        };
    }

    private void scheduleRetry() {
        if (closed || store == null || store.selected() == null) return;
        long seconds=Math.min(30,2L << Math.min(retries++,4));
        scheduleRestart(seconds*1000);
    }
    private void scheduleRestart(long millis) {
        cancelRestart();
        restartTask=worker.schedule(this::restart,millis,TimeUnit.MILLISECONDS);
    }
    private void cancelRestart() { if(restartTask!=null){ restartTask.cancel(false); restartTask=null; } }
    private void stopCore() {
        generation++;
        if(probeTask!=null){probeTask.cancel(false);probeTask=null;}
        if(probeDeadline!=null){probeDeadline.cancel(false);probeDeadline=null;}
        health=new ProbeHealth(); lastCheckTime=0; runningId=null;
        if(ipTask!=null){ipTask.cancel(false);ipTask=null;}
        if(xrayRunning){ xrayRunning=false; try{io.nekohasekai.pocketxray.Pocketxray.stop();}catch(Exception ignored){} }
        CommandClient oldClient=client; client=null;
        if(oldClient!=null) try{oldClient.disconnect();}catch(Exception ignored){}
        CommandServer oldServer=server; server=null;
        if(oldServer!=null){
            try{oldServer.closeService();}catch(Exception ignored){}
            try{oldServer.close();}catch(RuntimeException ignored){}
        }
        AndroidPlatform oldPlatform=platform;platform=null;
        if(oldPlatform!=null)try{oldPlatform.close();}catch(java.io.IOException e){android.util.Log.w("PocketVPN","TUN cleanup failed");}
    }

    private String currentState() {
        try {
            JSONObject state=new JSONObject(snapshot);
            state.put("policyState",ProtectionPolicy.isOwner(this)?(ProtectionPolicy.confirmed(this)?"CONFIRMED":"UNCONFIRMED"):"NO_PROFILE");
            long checked=state.optLong("lastCheckElapsed",-1),now=android.os.SystemClock.elapsedRealtime();
            if("CONNECTED".equals(state.optString("connectionState")) && (checked<0 || now<checked || now-checked>75000)) {
                state.put("connectionState","RECONNECTING"); state.put("error","Ожидаем свежую проверку связи");
            }
            return state.toString();
        } catch(Exception e){ return "{\"policyState\":\"UNCONFIRMED\",\"connectionState\":\"ERROR\"}"; }
    }
    private void publish() {
        try {
            ConnectionProfile selected=store==null?null:store.selected();
            JSONObject value=new JSONObject().put("policyState",ProtectionPolicy.confirmed(this)?"CONFIRMED":"UNCONFIRMED")
                    .put("connectionState",connectionState).put("selectedId",selected==null?"":selected.id)
                    .put("selectedName",selected==null?"":selected.name).put("runningId",runningId==null?"":runningId)
                    .put("selectedType",selected==null?"":selected.typeLabel()).put("directRu",directRu())
                    .put("ipControl",prefs().getBoolean(PREF_IP_CONTROL,false)).put("ipMinutes",prefs().getInt(PREF_IP_MINUTES,30))
                    .put("ipStatus",ipStatus).put("ipObserved",ipObserved).put("ipConfirmed",prefs().getString(PREF_IP_CONFIRMED,""))
                    .put("ipPending",prefs().getString(PREF_IP_PENDING,""))
                    .put("lastCheckTime",lastCheckTime).put("lastCheckElapsed",health.verifiedElapsed()).put("error",error)
                    .put("profileSerial",getSystemService(UserManager.class).getSerialNumberForUser(Process.myUserHandle()));
            snapshot=value.toString();
            showNotification(statusText(selected));
        } catch(Exception ignored) {}
    }
    private String statusText(ConnectionProfile selected) {
        return !ProtectionPolicy.confirmed(this)?"Защита не подтверждена. Открой Pocket":
                ("CONNECTED".equals(connectionState)?"Подключено: "+(selected==null?"":selected.name):"Интернет рабочих приложений заблокирован до подключения");
    }
    private void showNotification(String text) {
        if(!ProtectionPolicy.isOwner(this))return;
        Intent open=new Intent(this,OpenPocketActivity.class);
        PendingIntent target=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setContentTitle("Pocket")
                .setContentText(text).setSmallIcon(R.drawable.ic_notification_white_24dp).setOngoing(true).setContentIntent(target).build();
        startForeground(NOTIFICATION,notification);
    }
    @Override public void onRevoke() {
        worker.execute(() -> { cancelRestart(); stopCore(); connectionState="ERROR"; error="Разрешение VPN отозвано. Проверь защиту профиля."; publish(); });
    }
    @Override public void onDestroy() {
        closed=true;
        worker.execute(() -> { cancelRestart(); stopCore(); });
        worker.shutdown();
        network.shutdownNow();
        super.onDestroy();
    }
}
