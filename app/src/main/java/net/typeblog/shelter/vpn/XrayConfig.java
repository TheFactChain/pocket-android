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
        JSONArray outbounds = profile.xray.isEmpty() ? new JSONArray().put(fromLink(profile)) : new JSONArray(profile.xray);
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.getJSONObject(i);
            if (i == 0) outbound.put("tag", "proxy");
            resolveServers(outbound, resolver);
        }
        JSONObject socks = new JSONObject().put("tag", "pocket-in").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "password").put("udp", true).put("ip", "127.0.0.1")
                        .put("accounts", new JSONArray().put(new JSONObject().put("user", user).put("pass", password))));
        // The first outbound is Xray's default route; nothing else is configured, so nothing leaves around it.
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none"))
                .put("inbounds", new JSONArray().put(socks))
                .put("outbounds", outbounds)
                .toString();
    }

    /** The sing-box outbound that carries everything into Xray. */
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
