package net.typeblog.shelter.vpn;

import net.typeblog.shelter.connections.ConnectionProfile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Same routing rules as the Pocket Windows gateway (scripts/gateway_profile.py sing_box_config). */
public final class PocketConfig {
    // Russian services that report the caller's address back: a foreign page could ask them for the real IP.
    // shortcut: a known list only (copied from Windows), any other Russian echo service still answers with the real IP.
    static final String[] IP_ECHO = {"2ip.ru", "2ip.io", "2ip.me", "myip.ru", "ip-ping.ru", "whatismyip.ru",
            "internet.yandex.ru", "ipinfo.ru", "ip.ru", "ip2geo.ru"};

    private PocketConfig() {}

    public static String create(ConnectionProfile profile, String resolvedServer) {
        return create(profile, resolvedServer, null);
    }

    /** @param directRu Russian IPv4 networks reached around the proxy, or null when the setting is off. */
    public static String create(ConnectionProfile profile, String resolvedServer, List<String> directRu) {
        return create(profile, resolvedServer, directRu, null);
    }

    /** @param carrier outbound used instead of the profile's own, e.g. the SOCKS hand-off to Xray (see XrayConfig). */
    public static String create(ConnectionProfile profile, String resolvedServer, List<String> directRu, JSONObject carrier) {
        // An enabled setting without its list fails closed, as in Windows.
        if (directRu != null && directRu.isEmpty()) throw new IllegalArgumentException("Нет списка российских сетей");
        try {
            JSONObject outbound = carrier != null ? carrier : profile.toOutbound();
            if (carrier == null) outbound.put("server", resolvedServer);
            JSONObject tun = new JSONObject().put("type", "tun").put("tag", "pocket-tun")
                    .put("address", new JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                    .put("dns_mode", "hijack")
                    .put("mtu", 1400).put("stack", "gvisor").put("auto_route", true).put("strict_route", true);
            JSONObject resolver = new JSONObject().put("type", "https").put("tag", "protected-dns")
                    .put("server", "1.1.1.1").put("server_port", 443).put("path", "/dns-query").put("detour", "proxy")
                    .put("tls", new JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"));
            JSONArray rules = new JSONArray()
                    .put(new JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    // The internet is IPv4 only, as in Windows: apps fall back from IPv6 at once.
                    .put(new JSONObject().put("ip_version", 6).put("action", "reject"));
            if (!profile.udpSupported()) rules.put(new JSONObject().put("network", "udp").put("action", "reject"));
            // Xray clients refuse QUIC (UDP/443) under XTLS Vision and Vision servers do not carry it, so browsers
            // waited on a silent QUIC attempt; refusing it at once makes them use TCP right away, as with Happ.
            else if (!profile.flow.isEmpty()) rules.put(new JSONObject().put("network", "udp").put("port", 443).put("action", "reject"));
            JSONArray outbounds = new JSONArray().put(outbound);
            JSONObject route = new JSONObject().put("auto_detect_interface", true).put("final", "proxy");
            if (directRu != null) {
                // Only TCP to a Russian address leaves directly; UDP (WebRTC, QUIC), DNS ports and IP echo services keep the proxy.
                JSONArray echo = new JSONArray();
                for (String name : IP_ECHO) echo.put(name);
                JSONArray cidrs = new JSONArray();
                for (String cidr : directRu) cidrs.put(cidr);
                JSONArray withDirect = new JSONArray().put(new JSONObject().put("action", "sniff"));
                for (int i = 0; i < rules.length(); i++) withDirect.put(rules.get(i));
                withDirect.put(new JSONObject().put("port", new JSONArray().put(53).put(853)).put("outbound", "proxy"))
                        .put(new JSONObject().put("domain_suffix", echo).put("outbound", "proxy"))
                        .put(new JSONObject().put("network", "tcp").put("rule_set", "ru-ipv4").put("outbound", "direct"));
                rules = withDirect;
                outbounds.put(new JSONObject().put("type", "direct").put("tag", "direct"));
                route.put("rule_set", new JSONArray().put(new JSONObject().put("type", "inline").put("tag", "ru-ipv4")
                        .put("rules", new JSONArray().put(new JSONObject().put("ip_cidr", cidrs)))));
            }
            route.put("rules", rules);
            return new JSONObject().put("log", new JSONObject().put("disabled", true))
                    .put("inbounds", new JSONArray().put(tun)).put("outbounds", outbounds)
                    // shortcut: DNS is IPv4-only until gateway IPv6 is verified; IPv6 traffic is rejected above.
                    .put("dns", new JSONObject().put("servers", new JSONArray().put(resolver)).put("final", "protected-dns").put("strategy", "ipv4_only"))
                    .put("route", route)
                    .toString();
        } catch (org.json.JSONException e) { throw new IllegalArgumentException("Не удалось подготовить подключение"); }
    }

    /** Reads ru-ipv4.txt: one IPv4 CIDR per line, '#' comments. */
    public static List<String> parseNetworks(java.io.Reader source) throws java.io.IOException {
        List<String> result = new java.util.ArrayList<>();
        java.io.BufferedReader reader = new java.io.BufferedReader(source);
        for (String line; (line = reader.readLine()) != null;) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!line.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}")) throw new java.io.IOException("Некорректный список российских сетей");
            result.add(line);
        }
        return result;
    }
}
