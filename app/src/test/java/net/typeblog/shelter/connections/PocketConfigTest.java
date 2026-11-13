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

    @Test public void visionRefusesQuicLikeXrayButKeepsOtherUdp() throws Exception {
        String key = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0", user = "bf000d23-0752-40b4-affe-68f7707a9661";
        ConnectionProfile vision = ProfileImporter.parse("vless://" + user + "@de.example:443?security=reality&type=tcp"
                + "&flow=xtls-rprx-vision&sni=www.example.com&pbk=" + key + "&sid=ab").profiles.get(0);
        org.json.JSONArray rules = new JSONObject(PocketConfig.create(vision, "192.0.2.1")).getJSONObject("route").getJSONArray("rules");
        boolean quicRefused = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            quicRefused |= "udp".equals(rule.optString("network")) && rule.optInt("port") == 443 && "reject".equals(rule.optString("action"));
        }
        assertTrue(quicRefused);
        ConnectionProfile grpc = ProfileImporter.parse("vless://" + user + "@at.example:443?security=tls&type=grpc&serviceName=s").profiles.get(0);
        assertFalse(new JSONObject(PocketConfig.create(grpc, "192.0.2.1")).toString().contains("\"network\":\"udp\""));
    }

    @Test public void vlessRunsOnXrayBehindPasswordedLoopbackSocks() throws Exception {
        String key = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0", user = "bf000d23-0752-40b4-affe-68f7707a9661";
        ConnectionProfile link = ProfileImporter.parse("vless://" + user + "@de.example:443?security=reality&type=tcp"
                + "&flow=xtls-rprx-vision&sni=www.example.com&pbk=" + key + "&sid=ab&fp=chrome").profiles.get(0);
        JSONObject xray = new JSONObject(net.typeblog.shelter.vpn.XrayConfig.create(link, host -> "192.0.2.7", 20000, "u", "p"));
        JSONObject in = xray.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("127.0.0.1", in.getString("listen"));
        assertEquals("password", in.getJSONObject("settings").getString("auth"));
        JSONObject out = xray.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("proxy", out.getString("tag"));
        JSONObject server = out.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0);
        assertEquals("192.0.2.7", server.getString("address"));
        assertEquals("xtls-rprx-vision", server.getJSONArray("users").getJSONObject(0).getString("flow"));
        assertEquals("www.example.com", out.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("serverName"));

        JSONObject carrier = net.typeblog.shelter.vpn.XrayConfig.socksOutbound(20000, "u", "p");
        JSONObject config = new JSONObject(PocketConfig.create(link, "192.0.2.7", null, carrier));
        JSONObject proxy = config.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("socks", proxy.getString("type"));
        assertEquals("127.0.0.1", proxy.getString("server"));
        assertTrue("Vision still refuses QUIC", config.toString().contains("\"port\":443"));
    }

    @Test public void providerChainKeepsEveryHopAndResolvesItsNames() throws Exception {
        String key = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0", user = "bf000d23-0752-40b4-affe-68f7707a9661";
        JSONObject exit = new JSONObject().put("tag", "proxy").put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new org.json.JSONArray().put(new JSONObject().put("address", "exit.example").put("port", 443)
                        .put("users", new org.json.JSONArray().put(new JSONObject().put("id", user).put("encryption", "none"))))))
                .put("streamSettings", new JSONObject().put("network", "tcp").put("security", "reality")
                        .put("realitySettings", new JSONObject().put("serverName", "www.example.com").put("publicKey", key).put("shortId", "ab"))
                        .put("sockopt", new JSONObject().put("dialerProxy", "entry")));
        JSONObject entry = new JSONObject(exit.toString()).put("tag", "entry");
        entry.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("address", "entry.example");
        entry.getJSONObject("streamSettings").remove("sockopt");
        JSONObject config = new JSONObject().put("remarks", "Double").put("outbounds", new org.json.JSONArray().put(exit).put(entry)
                .put(new JSONObject().put("protocol", "freedom").put("tag", "direct").put("settings", new JSONObject())));
        ConnectionProfile profile = ProfileImporter.parse(config.toString()).profiles.get(0);
        assertTrue(profile.chained());
        assertTrue(profile.details().contains("Double VPN"));
        JSONObject xray = new JSONObject(net.typeblog.shelter.vpn.XrayConfig.create(profile,
                host -> host.equals("entry.example") ? "192.0.2.1" : "192.0.2.2", 20000, "u", "p"));
        org.json.JSONArray outbounds = xray.getJSONArray("outbounds");
        assertEquals(2, outbounds.length());
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"));
        assertEquals("192.0.2.2", outbounds.getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"));
        assertEquals("192.0.2.1", outbounds.getJSONObject(1).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"));
        assertEquals("entry", outbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"));
        assertTrue(ConnectionProfile.fromJson(profile.toJson()).chained());

        // The server check puts several servers in one Xray; each chain keeps its own hops.
        org.json.JSONArray[] servers = {net.typeblog.shelter.vpn.XrayConfig.outbounds(profile), null,
                net.typeblog.shelter.vpn.XrayConfig.outbounds(profile)};
        java.util.List<Integer> kept = new java.util.ArrayList<>();
        JSONObject many = new JSONObject(net.typeblog.shelter.vpn.XrayConfig.createMany(servers, host -> {
            if (kept.size() == 1) throw new java.net.UnknownHostException(host);
            return "192.0.2.3";
        }, new int[]{20001, 0, 20003}, "u", "p", kept));
        assertEquals(java.util.Collections.singletonList(0), kept);
        assertEquals("blackhole", many.getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
        assertEquals("s-0-1", many.getJSONArray("outbounds").getJSONObject(1).getJSONObject("streamSettings")
                .getJSONObject("sockopt").getString("dialerProxy"));
        assertEquals(1, many.getJSONArray("inbounds").length());
        assertEquals("s-0", many.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"));
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
