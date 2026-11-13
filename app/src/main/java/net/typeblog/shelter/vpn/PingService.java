package net.typeblog.shelter.vpn;

import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import net.typeblog.shelter.services.IPocketPing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.nekohasekai.libbox.*;

/**
 * Real server check, as Happ's ping: a request through each server to a test page, timed by the core.
 * Runs a second core in the «:ping» process with only the servers and no TUN, its sockets bound to the
 * physical network, so the working tunnel and its connections are not touched. The process ends after
 * each check, freeing the core's memory.
 *
 * Request: {"network": handle of the physical network, "servers": [{"id": …, "outbound": {sing-box outbound}}]}.
 * Result: {id: delay in ms, or -1 when the server did not carry the request}.
 */
public final class PingService extends Service {
    static final long FAILED = -1;
    private static final int WAIT_SECONDS = 20;
    private static boolean nativeReady;

    private final IPocketPing.Stub binder = new IPocketPing.Stub() {
        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Private server check");
            return super.onTransact(code, data, reply, flags);
        }
        @Override public String test(String request) {
            synchronized (PingService.class) { return run(request); }
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public boolean onUnbind(Intent intent) {
        // Nothing else lives in this process; ending it returns the core's memory at once.
        new android.os.Handler(getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 1000);
        return false;
    }

    private String run(String request) {
        JSONObject result = new JSONObject();
        CommandServer server = null;
        CommandClient client = null;
        AndroidPlatform platform = null;
        try {
            JSONObject input = new JSONObject(request);
            JSONArray servers = input.getJSONArray("servers");
            Network network = Network.fromNetworkHandle(input.getLong("network"));
            Map<String, String> tags = new HashMap<>();
            JSONArray outbounds = new JSONArray(), members = new JSONArray();
            JSONObject[] values = new JSONObject[servers.length()];
            String[] ids = new String[servers.length()];
            for (int i = 0; i < servers.length(); i++) {
                ids[i] = servers.getJSONObject(i).getString("id");
                values[i] = servers.getJSONObject(i).getJSONObject("outbound");
                result.put(ids[i], FAILED);
            }
            // Server names are resolved on the physical network, as for the working tunnel; the test page's
            // name goes to the server, so this core needs no DNS of its own.
            String[] addresses = resolve(network, values);
            for (int i = 0; i < values.length; i++) {
                if (addresses[i] == null) continue;
                String tag = "s" + i;
                tags.put(tag, ids[i]);
                outbounds.put(values[i].put("tag", tag).put("server", addresses[i]));
                members.put(tag);
            }
            if (members.length() == 0) return result.toString();
            outbounds.put(new JSONObject().put("type", "selector").put("tag", "ping").put("outbounds", members));
            String config = new JSONObject().put("log", new JSONObject().put("disabled", true))
                    .put("outbounds", outbounds)
                    .put("route", new JSONObject().put("auto_detect_interface", true).put("final", "ping")).toString();

            if (!nativeReady) {
                go.Seq.setContext(getApplicationContext());
                File base = new File(getFilesDir(), "ping-core"), temporary = new File(getCacheDir(), "ping-core");
                if ((!base.isDirectory() && !base.mkdirs()) || (!temporary.isDirectory() && !temporary.mkdirs())) return result.toString();
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
                nativeReady = true;
            }
            platform = new AndroidPlatform(this, null);
            Libbox.checkConfig(config);
            server = Libbox.newCommandServer(new QuietServer(), platform);
            server.start();
            server.startOrReloadService(config, new OverrideOptions());

            Map<String, Long> delays = new HashMap<>();
            CountDownLatch done = new CountDownLatch(1);
            long started = System.currentTimeMillis() / 1000;
            int expected = members.length();
            CommandClientOptions options = new CommandClientOptions();
            options.addCommand(Libbox.CommandOutbounds);
            client = Libbox.newCommandClient(new Results(tags, delays, started, expected, done), options);
            client.connect();
            client.urlTest("ping");
            done.await(WAIT_SECONDS, TimeUnit.SECONDS);
            synchronized (delays) { for (Map.Entry<String, Long> entry : delays.entrySet()) result.put(entry.getKey(), entry.getValue()); }
        } catch (Throwable e) {
            android.util.Log.w("PocketPing", "Server check failed: " + e.getClass().getSimpleName());
        } finally {
            if (client != null) try { client.disconnect(); } catch (Exception ignored) { }
            if (server != null) {
                try { server.closeService(); } catch (Exception ignored) { }
                try { server.close(); } catch (RuntimeException ignored) { }
            }
            if (platform != null) try { platform.close(); } catch (Exception ignored) { }
        }
        return result.toString();
    }

    private static String[] resolve(Network network, JSONObject[] servers) {
        String[] addresses = new String[servers.length];
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8, servers.length)));
        for (int i = 0; i < servers.length; i++) {
            final int index = i;
            pool.execute(() -> {
                try {
                    InetAddress address = network.getByName(servers[index].optString("server"));
                    addresses[index] = address.getHostAddress();
                } catch (Exception ignored) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        pool.shutdownNow();
        return addresses;
    }

    /** Collects each server's delay once the core reports a test newer than the request. */
    private static final class Results implements CommandClientHandler {
        private final Map<String, String> tags;
        private final Map<String, Long> delays;
        private final long started;
        private final int expected;
        private final CountDownLatch done;

        Results(Map<String, String> tags, Map<String, Long> delays, long started, int expected, CountDownLatch done) {
            this.tags = tags; this.delays = delays; this.started = started; this.expected = expected; this.done = done;
        }

        @Override public void writeOutbounds(OutboundGroupItemIterator values) {
            synchronized (delays) {
                while (values.hasNext()) {
                    OutboundGroupItem item = values.next();
                    String id = tags.get(item.getTag());
                    if (id != null && item.getURLTestTime() >= started && item.getURLTestDelay() > 0) delays.put(id, (long) item.getURLTestDelay());
                }
                if (delays.size() >= expected) done.countDown();
            }
        }
        @Override public void connected() { }
        @Override public void disconnected(String message) { }
        @Override public void clearLogs() { }
        @Override public void initializeClashMode(StringIterator modes, String current) { }
        @Override public void setDefaultLogLevel(int level) { }
        @Override public void updateClashMode(String mode) { }
        @Override public void writeConnectionEvents(ConnectionEvents events) { }
        @Override public void writeGroups(OutboundGroupIterator groups) { }
        @Override public void writeLogs(LogIterator logs) { }
        @Override public void writeStatus(StatusMessage status) { }
    }

    private static final class QuietServer implements CommandServerHandler {
        @Override public int connectSSHAgent() { throw new UnsupportedOperationException(); }
        @Override public SystemProxyStatus getSystemProxyStatus() { return new SystemProxyStatus(); }
        @Override public void serviceReload() { }
        @Override public void serviceStop() { }
        @Override public void setSystemProxyEnabled(boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public void triggerNativeCrash() { throw new UnsupportedOperationException(); }
        @Override public void writeDebugMessage(String message) { }
    }
}
