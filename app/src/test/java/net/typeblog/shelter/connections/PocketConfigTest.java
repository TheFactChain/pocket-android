package net.typeblog.shelter.connections;

import net.typeblog.shelter.vpn.PocketConfig;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PocketConfigTest {
    @Test public void generatedRouteHasOneProxyAndNoDirectExceptions() throws Exception {
        ConnectionProfile profile = ConnectionProfile.fromJson(new JSONObject().put("name", "Test")
                .put("type", "hysteria2").put("server", "example.com").put("port", 443).put("password", "test-only"));
        JSONObject config = new JSONObject(PocketConfig.create(profile, "192.0.2.1"));
        assertEquals(1, config.getJSONArray("outbounds").length());
        assertEquals("hysteria2", config.getJSONArray("outbounds").getJSONObject(0).getString("type"));
        assertEquals("192.0.2.1", config.getJSONArray("outbounds").getJSONObject(0).getString("server"));
        assertEquals("example.com", config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getString("server_name"));
        assertEquals("proxy", config.getJSONObject("route").getString("final"));
        assertEquals("proxy", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"));
        assertEquals("Do not advertise unverified IPv6 destinations to applications", "ipv4_only",
                config.getJSONObject("dns").optString("strategy"));
        JSONObject tun = config.getJSONArray("inbounds").getJSONObject(0);
        assertEquals(2, tun.getJSONArray("address").length());
        assertFalse("Let libbox create the automatic DNS hijack addresses",tun.has("dns_address"));
        assertTrue(tun.getBoolean("auto_route"));
        assertTrue(tun.getBoolean("strict_route"));
        assertFalse(config.toString().contains("route_exclude"));
        assertFalse(config.toString().contains("\"direct\""));
        assertTrue(config.toString().contains("\"ip_version\":6"));
        assertFalse("Hysteria2 carries UDP", config.toString().contains("\"network\":\"udp\""));
    }

    @Test public void tcpOnlyProxiesRejectUdpLikeWindows() throws Exception {
        for (String link : new String[]{"socks5://a:b@10.0.0.2", "http://p.example", "https://p.example"}) {
            ConnectionProfile profile = ProfileImporter.parse(link).profiles.get(0);
            JSONObject config = new JSONObject(PocketConfig.create(profile, "192.0.2.1"));
            org.json.JSONArray rules = config.getJSONObject("route").getJSONArray("rules");
            boolean udpRejected = false;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                udpRejected |= "udp".equals(rule.optString("network")) && "reject".equals(rule.optString("action"));
            }
            assertTrue(link, udpRejected);
            assertEquals("192.0.2.1", config.getJSONArray("outbounds").getJSONObject(0).getString("server"));
        }
    }

    @Test public void directRussianRoutingMatchesWindows() throws Exception {
        ConnectionProfile profile = ProfileImporter.parse("hy2://pw@vpn.example").profiles.get(0);
        java.util.List<String> nets = PocketConfig.parseNetworks(new java.io.FileReader("src/main/assets/ru-ipv4.txt"));
        assertTrue(nets.size() > 1000);
        JSONObject config = new JSONObject(PocketConfig.create(profile, "192.0.2.1", nets));
        org.json.JSONArray rules = config.getJSONObject("route").getJSONArray("rules");
        assertEquals("sniff", rules.getJSONObject(0).getString("action"));
        JSONObject last = rules.getJSONObject(rules.length() - 1);
        assertEquals("tcp", last.getString("network"));
        assertEquals("direct", last.getString("outbound"));
        assertEquals("proxy", rules.getJSONObject(rules.length() - 3).getString("outbound"));
        assertTrue(rules.getJSONObject(rules.length() - 2).toString().contains("internet.yandex.ru"));
        assertEquals("proxy", config.getJSONObject("route").getString("final"));
        assertEquals(2, config.getJSONArray("outbounds").length());
        try {
            PocketConfig.create(profile, "192.0.2.1", new java.util.ArrayList<String>());
            fail("Enabled setting without the list must fail closed");
        } catch (IllegalArgumentException expected) { }
    }
}
