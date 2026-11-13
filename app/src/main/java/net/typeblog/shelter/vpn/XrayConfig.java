package net.typeblog.shelter.vpn;

import net.typeblog.shelter.connections.ConnectionProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * VLESS runs on Xray, as in Happ: the provider's outbounds as served (REALITY, masks, mux, Double VPN),
 * or ones built from a link. sing-box keeps the TUN, DNS and routing and hands traffic to Xray through
 * a SOCKS inbound on loopback with a fresh password per start, so other apps cannot use it.
 */
public final class XrayConfig {
    /** Resolves a server name outside the tunnel; returns an IP literal. */
    public interface Resolver { String resolve(String host) throws Exception; }

    private XrayConfig() { }

    public static String create(ConnectionProfile profile, Resolver resolver, int port, String user, String password) throws Exception {
        JSONArray outbounds = outbounds(profile);
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.getJSONObject(i);
            if (i == 0) outbound.put("tag", "proxy");
            resolveServers(outbound, resolver);
        }
        // The first outbound is Xray's default route; nothing else is configured, so nothing leaves around it.
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none"))
                .put("inbounds", new JSONArray().put(socks("pocket-in", port, user, password)))
                .put("outbounds", outbounds)
                .toString();
    }

    /** The profile's Xray outbounds: the provider's as served, or one built from the link. */
    public static JSONArray outbounds(ConnectionProfile profile) throws JSONException {
        return profile.xray.isEmpty() ? new JSONArray().put(fromLink(profile)) : new JSONArray(profile.xray);
    }

    /**
     * Several servers in one Xray for the server check: server i is reached through inbound «in-i» on ports[i]
     * and its own outbounds, renamed «s-i-…» so chains of different servers stay apart.
     * Servers whose names do not resolve are left out; the result lists the indexes kept.
     */
    public static String createMany(JSONArray[] servers, Resolver resolver, int[] ports, String user, String password, java.util.List<Integer> kept) throws Exception {
        JSONArray inbounds = new JSONArray(), all = new JSONArray(), rules = new JSONArray();
        for (int i = 0; i < servers.length; i++) {
            if (servers[i] == null) continue;
            JSONArray outbounds = new JSONArray(servers[i].toString());
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (int k = 0; k < outbounds.length(); k++) {
                String tag = outbounds.getJSONObject(k).optString("tag");
                names.put(tag, k == 0 ? "s-" + i : "s-" + i + "-" + k);
            }
            try {
                for (int k = 0; k < outbounds.length(); k++) {
                    JSONObject outbound = outbounds.getJSONObject(k);
                    outbound.put("tag", k == 0 ? "s-" + i : "s-" + i + "-" + k);
                    retarget(outbound, names);
                    resolveServers(outbound, resolver);
                }
            } catch (Exception unresolved) { continue; }
            for (int k = 0; k < outbounds.length(); k++) all.put(outbounds.get(k));
            inbounds.put(socks("in-" + i, ports[i], user, password));
            rules.put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("in-" + i)).put("outboundTag", "s-" + i));
            kept.add(i);
        }
        // Nothing reaches a server it was not sent to: without a matching rule traffic is refused.
        all.put(new JSONObject().put("tag", "refuse").put("protocol", "blackhole"));
        JSONArray ordered = new JSONArray().put(all.get(all.length() - 1));
        for (int k = 0; k < all.length() - 1; k++) ordered.put(all.get(k));
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none"))
                .put("inbounds", inbounds).put("outbounds", ordered)
                .put("routing", new JSONObject().put("rules", rules)).toString();
    }

    private static void retarget(JSONObject outbound, java.util.Map<String, String> names) throws JSONException {
        JSONObject stream = outbound.optJSONObject("streamSettings");
        JSONObject sockopt = stream == null ? null : stream.optJSONObject("sockopt");
        if (sockopt != null && names.containsKey(sockopt.optString("dialerProxy"))) sockopt.put("dialerProxy", names.get(sockopt.optString("dialerProxy")));
        JSONObject proxy = outbound.optJSONObject("proxySettings");
        if (proxy != null && names.containsKey(proxy.optString("tag"))) proxy.put("tag", names.get(proxy.optString("tag")));
    }

    private static JSONObject socks(String tag, int port, String user, String password) throws JSONException {
        return new JSONObject().put("tag", tag).put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "password").put("udp", true).put("ip", "127.0.0.1")
                        .put("accounts", new JSONArray().put(new JSONObject().put("user", user).put("pass", password))));
    }

    /** The sing-box outbound that carries everything into Xray. */
    /** A distinct free loopback port for each non-null chain; the probes stay open together so no two get the same port. */
    public static boolean pickPorts(JSONArray[] chains, int[] ports) throws java.io.IOException {
        java.util.List<java.net.ServerSocket> open = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < chains.length; i++) {
                if (chains[i] == null) continue;
                java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                open.add(probe);
                ports[i] = probe.getLocalPort();
            }
            return !open.isEmpty();
        } finally { for (java.net.ServerSocket probe : open) try { probe.close(); } catch (java.io.IOException ignored) { } }
    }

    public static JSONObject socksOutbound(int port, String user, String password) throws JSONException {
        return new JSONObject().put("type", "socks").put("tag", "proxy").put("server", "127.0.0.1").put("server_port", port)
                .put("version", "5").put("username", user).put("password", password);
    }

    /** Xray outbound for a VLESS link (no provider config). */
    static JSONObject fromLink(ConnectionProfile p) throws JSONException {
        JSONObject user = new JSONObject().put("id", p.uuid).put("encryption", "none");
        if (!p.flow.isEmpty()) user.put("flow", p.flow);
        JSONObject stream = new JSONObject().put("network", p.transport).put("security", p.security);
        if (p.security.equals("reality")) {
            stream.put("realitySettings", new JSONObject().put("serverName", p.serverName).put("publicKey", p.realityPublicKey)
                    .put("shortId", p.shortId).put("fingerprint", fingerprint(p.fingerprint)).put("spiderX", "/"));
        } else {
            stream.put("tlsSettings", new JSONObject().put("serverName", p.serverName).put("fingerprint", fingerprint(p.fingerprint)).put("allowInsecure", false));
        }
        if (p.transport.equals("grpc")) stream.put("grpcSettings", new JSONObject().put("serviceName", p.path));
        else if (p.transport.equals("ws")) {
            JSONObject ws = new JSONObject().put("path", p.path.isEmpty() ? "/" : p.path);
            if (!p.wsHost.isEmpty()) ws.put("host", p.wsHost);
            stream.put("wsSettings", ws);
        }
        return new JSONObject().put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
                        .put("address", p.server).put("port", p.port).put("users", new JSONArray().put(user)))))
                .put("streamSettings", stream);
    }

    private static String fingerprint(String value) { return value.equals("randomized") ? "random" : value; }

    /**
     * Server names are resolved by Pocket on the physical network, as for sing-box: Xray inside the VPN
     * process would otherwise ask the tunnel, which needs Xray. TLS keeps the name as SNI.
     */
    static void resolveServers(JSONObject outbound, Resolver resolver) throws Exception {
        JSONObject settings = outbound.optJSONObject("settings");
        if (settings == null) return;
        String first = null;
        for (String key : new String[]{"vnext", "servers"}) {
            JSONArray list = settings.optJSONArray(key);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject server = list.getJSONObject(i);
                String host = server.optString("address");
                if (first == null) first = host;
                if (!host.isEmpty()) server.put("address", resolver.resolve(host));
            }
        }
        if (settings.has("address") && settings.opt("address") instanceof String) {
            String host = settings.getString("address");
            if (first == null) first = host;
            settings.put("address", resolver.resolve(host));
        }
        JSONObject stream = outbound.optJSONObject("streamSettings");
        if (stream == null || first == null || first.isEmpty()) return;
        JSONObject tls = stream.optJSONObject("tlsSettings");
        if ("tls".equals(stream.optString("security")) && (tls == null || tls.optString("serverName").isEmpty())) {
            stream.put("tlsSettings", (tls == null ? new JSONObject() : tls).put("serverName", first));
        }
    }
}
