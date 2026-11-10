package net.typeblog.shelter.connections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class ProfileImporterTest {
    private static final String USER = "bf000d23-0752-40b4-affe-68f7707a9661";
    private static final String KEY = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0";
    private static final String VLESS = "vless://" + USER + "@vpn.example:443?security=reality"
            + "&type=tcp&encryption=none&sni=www.example.com&pbk=" + KEY;

    private static ConnectionProfile first(String text) {
        return ProfileImporter.parse(text).profiles.get(0);
    }

    private static void rejected(String text) {
        try {
            ProfileImporter.parse(text);
            fail("Expected safe rejection");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("TOP_SECRET"));
            assertNull("Exceptions must not retain raw input in their cause", expected.getCause());
        }
    }

    @Test public void hysteriaPasswordUsesPercentDecodingWithoutFormDecoding() {
        ConnectionProfile profile = first("hy2://user%3Ap%40ss+word%2B%3F@vpn.example:8443/"
                + "?sni=tls.example&obfs=salamander&obfs-password=ab%2Bcd+ef#%D0%9A%D0%B0%D0%B7%D0%B0%D1%85%D1%81%D1%82%D0%B0%D0%BD");
        assertEquals("hysteria2", profile.type);
        assertEquals("user:p@ss+word+?", profile.password);
        assertEquals("ab+cd+ef", profile.obfsPassword);
        assertEquals("Казахстан", profile.name);
        assertEquals(8443, profile.port);
        assertEquals("tls.example", profile.serverName);
    }

    @Test public void hysteriaDefaultPortSniAndIpv6() {
        ConnectionProfile profile = first("hysteria2://test-password@[2001:db8::1]");
        assertEquals("2001:db8::1", profile.server);
        assertEquals(443, profile.port);
        assertEquals("2001:db8::1", profile.serverName);
        assertEquals("vpn.example", first("hy2://pw@vpn.example").serverName);
    }

    @Test public void unicodeHostAndLabelAreSupported() {
        ConnectionProfile profile = first("hy2://pw@пример.рф:443#Алматы");
        assertEquals("xn--e1afmkfd.xn--p1ai", profile.server);
        assertEquals("Алматы", profile.name);
        assertEquals("Алматы + KZ", first("hy2://pw@vpn.example#Алматы%20+%20KZ").name);
        assertEquals(USER, first(VLESS.replace(USER, USER.replace("-", "%2D"))).uuid);
    }

    @Test public void realityOutboundDoesNotDisableUdp() throws Exception {
        ConnectionProfile profile = first(VLESS + "&sid=aB12&flow=xtls-rprx-vision#Work");
        assertEquals("chrome", profile.fingerprint);
        assertEquals("ab12", profile.shortId);
        JSONObject outbound = profile.toOutbound();
        assertEquals("proxy", outbound.getString("tag"));
        assertEquals(USER, outbound.getString("uuid"));
        assertEquals("xudp", outbound.getString("packet_encoding"));
        assertFalse(outbound.has("network"));
        assertFalse(outbound.has("transport"));
        assertFalse(outbound.has("detour"));
        JSONObject tls = outbound.getJSONObject("tls");
        assertTrue(tls.getBoolean("enabled"));
        assertFalse(tls.optBoolean("insecure"));
        assertEquals(KEY, tls.getJSONObject("reality").getString("public_key"));
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"));
    }

    @Test public void emptyRealityShortIdIsValid() {
        assertEquals("", first(VLESS + "&sid=").shortId);
        assertEquals("", first(VLESS).shortId);
    }

    @Test public void modelEditingRoundTripPreservesIdAndSecrets() throws Exception {
        ConnectionProfile original = first("hy2://test-password@vpn.example#Original");
        UUID.fromString(original.id);
        JSONObject changed = original.toJson().put("name", "Edited");
        ConnectionProfile edited = ConnectionProfile.fromJson(changed);
        assertEquals(original.id, edited.id);
        assertEquals("Edited", edited.name);
        assertEquals(original.password, edited.password);
        assertEquals(original.id, ConnectionProfile.fromJson(edited.toJson()).id);
        assertFalse(edited.toString().contains("test-password"));
    }

    @Test public void flatModelImportCreatesNewIdAndPreservesConnectionData() {
        ConnectionProfile original = first("hy2://test-password@vpn.example#Original");
        ConnectionProfile imported = first(original.toJson().toString());
        UUID.fromString(imported.id);
        assertNotEquals(original.id, imported.id);
        assertEquals(original.name, imported.name);
        assertEquals(original.server, imported.server);
        assertEquals(original.password, imported.password);
        assertNotEquals(imported.id, first(original.toJson().toString()).id);
    }

    @Test public void duplicateIncomingIdsBecomeDistinctNewImportIds() throws Exception {
        ConnectionProfile original = first("hy2://test-password@vpn.example#Original");
        JSONObject input = new JSONObject().put("outbounds", new JSONArray()
                .put(original.toJson()).put(original.toJson().put("server", "other.example")));
        ProfileImporter.Result result = ProfileImporter.parse(input.toString());
        assertEquals(2, result.profiles.size());
        assertTrue(result.warnings.isEmpty());
        for (ConnectionProfile profile : result.profiles) {
            UUID.fromString(profile.id);
            assertNotEquals(original.id, profile.id);
        }
        assertNotEquals(result.profiles.get(0).id, result.profiles.get(1).id);
        assertEquals("other.example", result.profiles.get(1).server);
    }

    @Test public void hysteriaOutboundUsesVerifiedTlsAndSalamander() throws Exception {
        JSONObject outbound = first("hy2://pw@vpn.example?obfs=salamander&obfs-password=obfs-test")
                .toOutbound();
        assertEquals("pw", outbound.getString("password"));
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"));
        assertFalse(outbound.getJSONObject("tls").optBoolean("insecure"));
        assertEquals("salamander", outbound.getJSONObject("obfs").getString("type"));
        assertEquals("obfs-test", outbound.getJSONObject("obfs").getString("password"));
    }

    @Test public void generatedOutboundsCanBeImported() {
        for (String uri : new String[] {VLESS, "hy2://pw@vpn.example", "hy2://pw@vpn.example?obfs=salamander&obfs-password=test"}) {
            ConnectionProfile original = first(uri);
            ConnectionProfile imported = first(original.toOutbound().toString());
            assertNotEquals(original.id, imported.id);
            assertEquals(original.type, imported.type);
            assertEquals(original.server, imported.server);
            assertEquals(original.password, imported.password);
            assertEquals(original.uuid, imported.uuid);
            assertEquals(original.realityPublicKey, imported.realityPublicKey);
            assertEquals(original.obfsPassword, imported.obfsPassword);
        }
    }

    @Test public void fullConfigExtractsOnlySupportedOutboundsWithWarnings() throws Exception {
        JSONObject config = new JSONObject().put("outbounds", new JSONArray()
                .put(new JSONObject().put("type", "direct"))
                .put(first(VLESS).toOutbound())
                .put(new JSONObject().put("type", "selector").put("outbounds", new JSONArray().put("TOP_SECRET"))))
                .put("route", new JSONObject().put("final", "TOP_SECRET"))
                .put("dns", new JSONObject());
        ProfileImporter.Result result = ProfileImporter.parse(config.toString());
        assertEquals(1, result.profiles.size());
        assertTrue(result.warnings.size() >= 3);
        assertFalse(result.warnings.toString().contains("TOP_SECRET"));
        assertFalse(result.profiles.get(0).toOutbound().has("route"));
    }

    @Test public void txtImportKeepsValidEntriesAndRedactsBadRecords() {
        ProfileImporter.Result result = ProfileImporter.parse("\uFEFF# My links\r\n"
                + "hy2://pw@vpn.example#One\nss://TOP_SECRET@bad.example\n" + VLESS);
        assertEquals(2, result.profiles.size());
        assertEquals(1, result.warnings.size());
        assertFalse(result.warnings.toString().contains("TOP_SECRET"));
        try { result.profiles.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { result.warnings.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void insecureTlsIsRejectedEverywhere() throws Exception {
        rejected("hy2://TOP_SECRET@vpn.example?insecure=1");
        rejected("hy2://TOP_SECRET@vpn.example?insecure=true");
        rejected(VLESS + "&insecure=1#TOP_SECRET");
        JSONObject outbound = first(VLESS).toOutbound();
        outbound.getJSONObject("tls").put("insecure", true);
        rejected(outbound.toString());
        outbound.getJSONObject("tls").put("insecure", "false");
        rejected(outbound.toString());
        assertEquals("pw", first("hy2://pw@vpn.example?insecure=0").password);
    }

    @Test public void duplicateAndAmbiguousParametersAreRejected() {
        rejected("hy2://TOP_SECRET@vpn.example?sni=one.example&sni=two.example");
        rejected("hy2://TOP_SECRET@vpn.example?sni=one.example&%73ni=two.example");
        rejected("hy2://TOP_SECRET@vpn.example?insecure=0&insecure=1");
        rejected(VLESS + "&pbk=" + KEY);
    }

    @Test public void unknownConnectionOptionsAreNotSilentlyDropped() {
        rejected("hy2://TOP_SECRET@vpn.example?unknown=TOP_SECRET");
        rejected("hy2://TOP_SECRET@vpn.example?obfs=unknown&obfs-password=test");
        rejected("hy2://TOP_SECRET@vpn.example?obfs-password=test");
        rejected(VLESS + "&spx=%2FTOP_SECRET");
        rejected(VLESS.replace("type=tcp", "type=ws"));
        rejected(VLESS.replace("encryption=none", "encryption=auto"));
        rejected(VLESS.replace("security=reality", "security=tls"));
        rejected(VLESS + "&fp=unrecognized");
        rejected(VLESS + "&flow=unknown");
    }

    @Test public void unknownJsonOptionsAreNotAppliedOrSilentlyDropped() throws Exception {
        JSONObject outbound = first(VLESS).toOutbound();
        rejected(outbound.put("detour", "TOP_SECRET").toString());
        outbound.remove("detour");
        rejected(outbound.put("network", "tcp").toString());
        outbound.remove("network");
        rejected(outbound.put("transport", new JSONObject().put("type", "ws")).toString());
        outbound.remove("transport");
        outbound.getJSONObject("tls").put("certificate_path", "TOP_SECRET");
        rejected(outbound.toString());
    }

    @Test public void credentialAndEndpointValidationRejectsMalformedValues() throws Exception {
        for (String uri : new String[] {"hy2://@vpn.example", "hy2://pw@vpn.example:0",
                "hy2://pw@vpn.example:65536", "hy2://pw@vpn.example:1.5", "hy2://pw@",
                "hy2://pw@vpn.example/path", "hy2://pw@vpn.example?", "hy2://%ZZ@vpn.example",
                "hy2://%C3%28@vpn.example", "hy2://pw@vpn.example?sni=a%00b",
                VLESS.replace(USER, "bad-uuid"), VLESS.replace(KEY, "bad-key"),
                VLESS + "&sid=abc", VLESS + "&sid=0123456789abcdef00", VLESS + "&sid=zz",
                VLESS.replace("sni=www.example.com&", "")}) {
            rejected(uri);
        }
        JSONObject flat = first(VLESS).toJson();
        rejected(flat.put("port", 443.5).toString());
        rejected(flat.put("port", "443").toString());
        rejected(flat.put("port", 443).put("server", "https://TOP_SECRET").toString());
    }

    @Test public void errorsDoNotExposeInputOrRetainParserExceptions() {
        rejected("{\"password\":\"TOP_SECRET\", malformed}");
        rejected("{\"outbounds\":\"TOP_SECRET\"}");
        rejected("TOP_SECRET");
        rejected(null);
        rejected(" ");
        rejected("{\"type\":\"direct\",\"tag\":\"TOP_SECRET\"}");
    }

    @Test public void inputSizeDepthAndRecordLimitsAreEnforced() throws Exception {
        assertEquals(1048576, ProfileImporter.MAX_INPUT_BYTES);
        rejected(new String(new char[ProfileImporter.MAX_INPUT_BYTES + 1]).replace('\0', 'a'));
        rejected(new String(new char[ProfileImporter.MAX_INPUT_BYTES / 2]).replace('\0', 'я'));
        String nested = "{}";
        for (int i = 0; i < 40; i++) nested = "{\"nested\":" + nested + "}";
        rejected(nested);
        JSONArray many = new JSONArray();
        for (int i = 0; i < 300; i++) many.put(first("hy2://pw@vpn.example").toOutbound());
        rejected(new JSONObject().put("outbounds", many).toString());
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 300; i++) lines.append("hy2://pw@vpn.example\n");
        rejected(lines.toString());
    }

    @Test public void jsonMustBeCompleteAndStringBracesDoNotCountAsDepth() {
        rejected(first(VLESS).toJson().toString() + " TOP_SECRET");
        ConnectionProfile braces = first("hy2://pw@vpn.example#%7B%7B%7B%7B");
        assertEquals("{{{{", first(braces.toJson().toString()).name);
    }

    @Test public void flatModelRejectsUnvalidatedTypesOptionsAndMalformedText() throws Exception {
        JSONObject flat = first(VLESS).toJson();
        rejected(flat.put("password", "TOP_SECRET").toString());
        flat.put("password", "");
        rejected(flat.put("insecure", true).toString());
        flat.remove("insecure");
        rejected(flat.put("serverName", JSONObject.NULL).toString());
        flat.put("serverName", "www.example.com");
        rejected(flat.put("realityPublicKey", KEY.substring(0, 42) + "1").toString());
        rejected("hy2://pw@vpn.example#" + '\uD800');
        try {
            ConnectionProfile.fromJson(null);
            fail();
        } catch (IllegalArgumentException expected) { assertNull(expected.getCause()); }
    }

    @Test public void paddedRealityKeyAndCanonicalIpv6RoundTrip() throws Exception {
        ConnectionProfile profile = first(VLESS.replace(KEY, KEY + "%3D")
                .replace("vpn.example", "[2001:db8::1]") + "&sid=0123456789ABCDEF&fp=firefox");
        assertEquals("2001:db8::1", profile.server);
        assertEquals("firefox", profile.fingerprint);
        assertEquals("0123456789abcdef", profile.shortId);
        assertEquals(KEY + "=", profile.toOutbound().getJSONObject("tls")
                .getJSONObject("reality").getString("public_key"));
    }

    // Cases mirror Pocket Windows tests/test_connection_profile.py so the same links work on both.
    @Test public void windowsHysteriaLinkFromItsTests() {
        ConnectionProfile p = first("hy2://user%3Ap%40ss@EXAMPLE.com/?sni=TLS.EXAMPLE.COM&obfs=salamander"
                + "&obfs-password=a%26b#%D0%9C%D0%BE%D0%B9%20%D1%81%D0%B5%D1%80%D0%B2%D0%B5%D1%80");
        assertEquals("example.com", p.server);
        assertEquals("tls.example.com", p.serverName);
        assertEquals("user:p@ss", p.password);
        assertEquals("a&b", p.obfsPassword);
        assertEquals("Мой сервер", p.name);
    }

    @Test public void proxyLinksUseWindowsDefaultsAndOptionalLogin() throws Exception {
        ConnectionProfile socks = first("socks5://bob:p%40ss@10.0.0.2#Офис");
        assertEquals("socks5", socks.type);
        assertEquals(1080, socks.port);
        assertEquals("bob", socks.username);
        assertEquals("p@ss", socks.password);
        assertEquals("Офис", socks.name);
        JSONObject out = socks.toOutbound();
        assertEquals("socks", out.getString("type"));
        assertEquals("5", out.getString("version"));
        assertEquals("tcp", out.getString("network"));
        assertFalse(out.has("tls"));

        ConnectionProfile http = first("http://proxy.example");
        assertEquals(80, http.port);
        assertEquals("", http.username);
        assertFalse(http.toOutbound().has("username"));

        ConnectionProfile https = first("https://u:p@proxy.example:8443");
        assertEquals(8443, https.port);
        JSONObject tls = https.toOutbound().getJSONObject("tls");
        assertEquals("http", https.toOutbound().getString("type"));
        assertEquals("proxy.example", tls.getString("server_name"));
        assertFalse(tls.getBoolean("insecure"));
        assertEquals(443, first("https://proxy.example").port);
    }

    @Test public void proxyLinksRejectQueryIpv6AndHalfLogin() {
        rejected("socks5://h.example:1080?x=1");
        rejected("http://[2001:db8::1]:80");
        rejected("http://onlyuser@h.example");
        rejected("ftp://h.example");
    }

    @Test public void windowsFileRoundTripWithSecrets() throws Exception {
        ConnectionProfile p = first("hy2://pw@vpn.example:443?sni=tls.example&obfs=salamander&obfs-password=ob#Мой");
        JSONObject file = p.toWindowsJson(true);
        assertEquals(1, file.getInt("schema"));
        assertEquals("hysteria2", file.getString("protocol"));
        assertEquals("vpn.example", file.getString("host"));
        assertTrue(file.getBoolean("tls_verify"));
        assertTrue(file.getBoolean("udp_supported"));
        assertEquals("tls.example", file.getString("tls_server_name"));
        assertEquals("salamander", file.getString("obfs"));
        assertEquals("pw", file.getJSONObject("credentials").getString("auth"));
        ConnectionProfile back = first(file.toString());
        assertEquals(p.password, back.password);
        assertEquals(p.obfsPassword, back.obfsPassword);
        assertEquals(p.serverName, back.serverName);
        assertNotEquals(p.id, back.id);

        ConnectionProfile socks = first("socks5://a:b@10.0.0.2:1081");
        ConnectionProfile socksBack = first(socks.toWindowsJson(true).toString());
        assertEquals("a", socksBack.username);
        assertEquals("b", socksBack.password);
        assertFalse(socks.toWindowsJson(true).getBoolean("udp_supported"));
    }

    @Test public void windowsExportWithoutPasswordsAsksForThem() throws Exception {
        ConnectionProfile p = first("hy2://pw@vpn.example:443?obfs=salamander&obfs-password=ob");
        JSONObject file = p.toWindowsJson(false);
        assertFalse(file.has("credentials"));
        assertFalse(file.toString().contains("pw"));
        ProfileImporter.Result result = ProfileImporter.parseForInterface(file.toString());
        assertTrue(result.profiles.isEmpty());
        assertNotNull(result.draft);
        assertEquals("vpn.example", result.draft.getString("server"));
        assertEquals("salamander", result.draft.getString("obfs"));
    }

    @Test public void windowsFileRejectsWeakenedOrForeignFields() throws Exception {
        String base = "{\"schema\":1,\"protocol\":\"socks5\",\"host\":\"h.example\",\"port\":1080";
        rejected(base + ",\"tls_verify\":true}");
        rejected(base + ",\"udp_supported\":true}");
        rejected(base + ",\"obfs\":\"salamander\"}");
        rejected(base + ",\"route\":{}}");
        rejected("{\"schema\":2,\"protocol\":\"http\",\"host\":\"h.example\",\"port\":80}");
        rejected("{\"protocol\":\"http\",\"host\":\"h.example\",\"port\":80,\"tls_server_name\":\"x.example\"}");
    }

    @Test public void interfaceImportHidesVless() {
        try {
            ProfileImporter.parseForInterface(VLESS);
            fail("VLESS must not reach the interface");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("VLESS"));
        }
        assertEquals(1, ProfileImporter.parseForInterface(VLESS + "\nhy2://pw@vpn.example").profiles.size());
    }

    @Test public void storedProfileWithoutUsernameStillLoads() throws Exception {
        JSONObject old = first("hy2://pw@vpn.example").toJson();
        old.remove("username");
        assertEquals("pw", ConnectionProfile.fromJson(old).password);
    }
}
