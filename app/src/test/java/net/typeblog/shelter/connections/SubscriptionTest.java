package net.typeblog.shelter.connections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SubscriptionTest {
    private static final String USER = "bf000d23-0752-40b4-affe-68f7707a9661";
    private static final String KEY = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0";
    private static final String GRPC = "vless://" + USER + "@at.example:443?security=tls&type=grpc&serviceName=svc"
            + "&mode=gun&fp=chrome&alpn=h2&sni=front.example#Austria%20(gRPC)";
    private static final String REALITY = "vless://" + USER + "@de.example:443?security=reality&type=tcp&headerType=none"
            + "&encryption=none&flow=xtls-rprx-vision&sni=www.example.com&pbk=" + KEY + "&sid=ab&spx=%2F#Germany";

    @Test public void vlessGrpcOverTlsBuildsGrpcTransportWithVerifiedTls() throws Exception {
        ConnectionProfile p = ProfileImporter.parse(GRPC).profiles.get(0);
        assertEquals("Austria (gRPC)", p.name);
        assertEquals("VLESS · gRPC · TLS", p.details());
        JSONObject out = p.toOutbound();
        assertEquals("grpc", out.getJSONObject("transport").getString("type"));
        assertEquals("svc", out.getJSONObject("transport").getString("service_name"));
        JSONObject tls = out.getJSONObject("tls");
        assertFalse(tls.has("reality"));
        assertFalse(tls.getBoolean("insecure"));
        assertEquals("front.example", tls.getString("server_name"));
        assertFalse(out.has("flow"));
        // Round trip through storage and through a sing-box export keeps the transport.
        ConnectionProfile stored = ConnectionProfile.fromJson(p.toJson());
        assertEquals("grpc", stored.transport);
        assertEquals("svc", ProfileImporter.parse(out.toString()).profiles.get(0).path);
    }

    @Test public void vlessWebSocketAndRealityWithEmptySpiderX() throws Exception {
        ConnectionProfile ws = ProfileImporter.parse("vless://" + USER + "@ws.example?security=tls&type=ws&path=%2Fws&host=cdn.example").profiles.get(0);
        JSONObject transport = ws.toOutbound().getJSONObject("transport");
        assertEquals("/ws", transport.getString("path"));
        assertEquals("cdn.example", transport.getJSONObject("headers").getString("Host"));
        assertEquals("ws.example", ws.serverName);
        ConnectionProfile reality = ProfileImporter.parse(REALITY).profiles.get(0);
        assertEquals("VLESS · TCP · REALITY", reality.details());
        assertFalse(reality.toOutbound().has("transport"));
    }

    @Test public void unsafeOrUnsupportedVlessOptionsAreRefused() {
        for (String link : new String[] {GRPC.replace("#", "&allowInsecure=1#"), GRPC.replace("mode=gun", "mode=multi"),
                REALITY.replace("type=tcp", "type=grpc"), GRPC.replace("#", "&pbk=" + KEY + "#"),
                REALITY.replace("headerType=none", "headerType=http")}) {
            try { ProfileImporter.parse(link); fail(link); } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void base64BodyWithMissingPaddingIsDecoded() {
        String list = GRPC + "\n" + REALITY + "\nhy2://pw@hy.example:8443?sni=hy.example#Hy";
        String body = Base64.getEncoder().withoutPadding().encodeToString(list.getBytes(StandardCharsets.UTF_8));
        ProfileImporter.Result result = ProfileImporter.parse(body);
        assertEquals(3, result.profiles.size());
        String url = Base64.getUrlEncoder().withoutPadding().encodeToString(list.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, ProfileImporter.parse(url).profiles.size());
        assertEquals("not base64 at all", ProfileImporter.decodeBase64List("not base64 at all"));
    }

    @Test public void xrayJsonListAsHappServesIt() throws Exception {
        JSONObject reality = xray("Austria", new JSONObject().put("protocol", "vless")
                .put("mux", new JSONObject().put("enabled", true).put("concurrency", 8))
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject().put("address", "at.example").put("port", 443)
                        .put("users", new JSONArray().put(new JSONObject().put("id", USER).put("encryption", "none").put("flow", "xtls-rprx-vision"))))))
                .put("streamSettings", new JSONObject().put("network", "tcp").put("security", "reality")
                        .put("realitySettings", new JSONObject().put("serverName", "www.example.com").put("publicKey", KEY)
                                .put("shortId", "ab").put("fingerprint", "chrome").put("spiderX", "/"))
                        .put("finalmask", new JSONObject().put("tcp", new JSONArray().put(new JSONObject().put("type", "fragment")
                                .put("settings", new JSONObject().put("packets", "tlshello").put("length", "10-20")))))));
        JSONObject grpc = xray("Russia YouTube", new JSONObject().put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject().put("address", "ru.example").put("port", "8443")
                        .put("users", new JSONArray().put(new JSONObject().put("id", USER).put("encryption", "none"))))))
                .put("streamSettings", new JSONObject().put("network", "grpc").put("security", "tls")
                        .put("grpcSettings", new JSONObject().put("serviceName", "yt"))
                        .put("tlsSettings", new JSONObject().put("serverName", "ru.example").put("allowInsecure", false))));
        JSONObject hysteria = xray("Austria Hysteria2", new JSONObject().put("protocol", "hysteria")
                .put("settings", new JSONObject().put("version", 2).put("address", "hy.example").put("port", 443))
                .put("streamSettings", new JSONObject().put("network", "hysteria").put("security", "tls")
                        .put("hysteriaSettings", new JSONObject().put("version", 2).put("auth", "hy-pass"))
                        .put("tlsSettings", new JSONObject().put("serverName", "hy.example"))
                        .put("finalmask", new JSONObject().put("quicParams", new JSONObject().put("congestion", "bbr"))
                                .put("udp", new JSONArray().put(new JSONObject().put("type", "salamander")
                                .put("settings", new JSONObject().put("password", "ob-pass")))))));
        JSONObject chained = xray("Double VPN", new JSONObject(grpc.getJSONArray("outbounds").getJSONObject(0).toString())
                .put("streamSettings", new JSONObject().put("network", "tcp").put("security", "tls")
                        .put("sockopt", new JSONObject().put("dialerProxy", "TOP_SECRET"))));
        JSONObject vmess = xray("Old", new JSONObject().put("protocol", "vmess").put("settings", new JSONObject().put("TOP_SECRET", 1)));
        JSONObject insecure = new JSONObject(grpc.toString());
        insecure.put("remarks", "Insecure").getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
                .getJSONObject("tlsSettings").put("allowInsecure", true);

        ProfileImporter.Result result = ProfileImporter.parse(new JSONArray().put(reality).put(grpc).put(hysteria)
                .put(chained).put(vmess).put(insecure).toString());
        assertEquals(3, result.profiles.size());
        assertEquals(3, result.warnings.size());
        assertFalse(result.warnings.toString().contains("TOP_SECRET"));
        assertTrue(result.warnings.get(0).contains("Double VPN"));
        ConnectionProfile at = result.profiles.get(0);
        assertEquals("Austria", at.name);
        assertEquals("xtls-rprx-vision", at.flow);
        assertTrue("The provider's hello fragmentation is kept", at.toOutbound().getJSONObject("tls").getBoolean("fragment"));
        assertTrue(ConnectionProfile.fromJson(at.toJson()).fragment);
        ConnectionProfile ru = result.profiles.get(1);
        assertFalse(ru.toOutbound().getJSONObject("tls").has("fragment"));
        assertEquals(8443, ru.port);
        assertEquals("yt", ru.path);
        ConnectionProfile hy = result.profiles.get(2);
        assertEquals("hysteria2", hy.type);
        assertEquals("hy-pass", hy.password);
        assertEquals("ob-pass", hy.obfsPassword);
        // A single Xray config (not a list) is read the same way.
        assertEquals("Austria", ProfileImporter.parse(reality.toString()).profiles.get(0).name);
    }

    private static JSONObject xray(String remarks, JSONObject proxy) throws Exception {
        return new JSONObject().put("remarks", remarks).put("dns", new JSONObject().put("servers", new JSONArray().put("1.1.1.1")))
                .put("outbounds", new JSONArray().put(proxy.put("tag", "proxy"))
                        .put(new JSONObject().put("protocol", "freedom").put("tag", "direct").put("settings", new JSONObject())))
                .put("routing", new JSONObject().put("rules", new JSONArray()));
    }

    @Test public void subscriptionLinksAreRecognisedButProxiesAreNot() {
        assertEquals("https://sub.example/api/sub/abc", Subscription.subscriptionUrl(" https://sub.example/api/sub/abc "));
        assertEquals("https://sub.example/s?t=1", Subscription.subscriptionUrl("happ://add/https://sub.example/s?t=1"));
        assertNull(Subscription.subscriptionUrl("https://proxy.example:8443"));
        assertNull(Subscription.subscriptionUrl("https://u:p@proxy.example/path"));
        assertNull(Subscription.subscriptionUrl("http://sub.example/api/sub/abc"));
        assertNull(Subscription.subscriptionUrl("happ://crypt/abcdef"));
    }

    @Test public void happHeadersFillTitleTrafficExpiryAndInterval() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Profile-Title", "base64:" + Base64.getEncoder().encodeToString("OrganicVPN 💎 PREMIUM".getBytes(StandardCharsets.UTF_8)));
        headers.put("subscription-userinfo", "upload=1073741824; download=226492416000; total=0; expire=1796515200");
        headers.put("profile-update-interval", "12");
        headers.put("support-url", "https://t.me/support_bot");
        headers.put("profile-web-page-url", "javascript:alert(1)");
        Subscription sub = Subscription.create("id1", "https://sub.example/s/x").updated(headers,
                "#announce: base64:" + Base64.getEncoder().encodeToString("Используйте Double VPN".getBytes(StandardCharsets.UTF_8)) + "\n" + REALITY, 1000);
        assertEquals("OrganicVPN 💎 PREMIUM", sub.title);
        assertEquals(1073741824L, sub.upload);
        assertEquals(226492416000L, sub.download);
        assertEquals(0, sub.total);
        assertEquals(1796515200L, sub.expire);
        assertEquals(12, sub.intervalHours);
        assertEquals("https://t.me/support_bot", sub.supportUrl);
        assertEquals("", sub.webUrl);
        assertEquals("Используйте Double VPN", sub.announce);
        // Stored and read back without losing anything, and the interface copy has no link.
        Subscription back = Subscription.fromJson(sub.toJson(true));
        assertEquals(sub.title, back.title);
        assertEquals(sub.url, back.url);
        assertFalse(sub.toJson(false).has("url"));
    }

    @Test public void deviceConsentAndSkippedServersSurviveStorageAndUpdates() {
        Subscription sub = Subscription.create("id1", "https://sub.example/s/x");
        assertFalse(sub.hwid);
        Subscription allowed = sub.withHwid(true).withSkipped(java.util.Arrays.asList("«A» пропущено: xhttp", "«B» пропущено: x\u0007"));
        assertEquals("«A» пропущено: xhttp\n«B» пропущено: x", allowed.skipped);
        Subscription back = Subscription.fromJson(allowed.toJson(true));
        assertTrue(back.hwid);
        assertEquals(allowed.skipped, back.skipped);
        // Consent stays after an update and after a failure; the skipped list is replaced on each update.
        Subscription updated = back.updated(new HashMap<>(), REALITY, 1000).withSkipped(java.util.Collections.emptyList());
        assertTrue(updated.hwid);
        assertEquals("", updated.skipped);
        assertTrue(updated.failed("x", 2000).hwid);
        assertFalse(Subscription.fromJson(sub.toJson(true)).hwid);
    }

    @Test public void untitledSubscriptionFallsBackToHostAndDefaultInterval() {
        Subscription sub = Subscription.create("id1", "https://sub.example/s/x").updated(new HashMap<>(), REALITY, 1000);
        assertEquals("sub.example", sub.toJson(false).optString("title"));
        assertEquals(Subscription.DEFAULT_INTERVAL_HOURS, sub.intervalHours);
    }

    @Test public void dueFollowsIntervalAndBacksOffAfterFailure() {
        long hour = 3_600_000L, now = 100 * hour;
        Subscription fresh = Subscription.create("id1", "https://sub.example/s/x");
        assertTrue(fresh.due(now));
        Subscription ok = fresh.updated(new HashMap<>(), REALITY, now);
        assertFalse(ok.due(now + 11 * hour));
        assertTrue(ok.due(now + 12 * hour));
        Subscription failed = ok.failed("Сервер подписки ответил кодом 500.", now + 12 * hour);
        assertFalse(failed.due(now + 12 * hour + 60_000));
        assertTrue(failed.due(now + 12 * hour + 16 * 60_000));
        assertEquals("Сервер подписки ответил кодом 500.", failed.error);
        assertEquals(ok.updated, failed.updated);
    }
}
