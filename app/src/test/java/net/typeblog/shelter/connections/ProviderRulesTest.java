package net.typeblog.shelter.connections;

import net.typeblog.shelter.vpn.PocketConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderRulesTest {
    private static final JSONArray OUTBOUNDS = new JSONArray().put(outbound("proxy", "vless")).put(outbound("direct", "freedom")).put(outbound("block", "blackhole"));

    private static JSONObject outbound(String tag, String protocol) {
        try { return new JSONObject().put("tag", tag).put("protocol", protocol); } catch (org.json.JSONException e) { throw new AssertionError(e); }
    }

    private static JSONArray rules(JSONArray list) throws Exception {
        return XrayImport.rules(new JSONObject().put("routing", new JSONObject().put("rules", list)), OUTBOUNDS);
    }

    @Test public void onlyPlainDirectAndBlockRulesAreTaken() throws Exception {
        JSONArray out = rules(new JSONArray()
                .put(new JSONObject().put("type", "field").put("outboundTag", "direct")
                        .put("domain", new JSONArray().put("domain:ru").put("full:ya.ru").put("keyword:sber").put("geosite:category-ru").put("regexp:.*")))
                .put(new JSONObject().put("outboundTag", "block").put("ip", new JSONArray().put("10.0.0.0/8").put("geoip:private")))
                .put(new JSONObject().put("outboundTag", "proxy").put("domain", new JSONArray().put("domain:com")))
                .put(new JSONObject().put("outboundTag", "direct").put("port", "443").put("domain", new JSONArray().put("domain:x.ru")))
                .put(new JSONObject().put("outboundTag", "direct").put("domain", new JSONArray().put("domain:y.ru")).put("ip", new JSONArray().put("1.1.1.1")))
                .put(new JSONObject().put("outboundTag", "direct").put("domain", new JSONArray().put("geosite:cn"))));
        assertEquals(2, out.length());
        JSONObject direct = out.getJSONObject(0);
        assertEquals("direct", direct.getString("action"));
        assertEquals("ru", direct.getJSONArray("domain_suffix").getString(0));
        assertEquals("ya.ru", direct.getJSONArray("domain").getString(0));
        assertEquals("sber", direct.getJSONArray("domain_keyword").getString(0));
        JSONObject block = out.getJSONObject(1);
        assertEquals("block", block.getString("action"));
        assertEquals(1, block.getJSONArray("ip_cidr").length());
    }

    @Test public void rulesApplyOnlyWhenTurnedOn() throws Exception {
        String stored = new JSONArray()
                .put(new JSONObject().put("action", "direct").put("domain_suffix", new JSONArray().put("ru")))
                .put(new JSONObject().put("action", "block").put("ip_cidr", new JSONArray().put("10.0.0.0/8"))).toString();
        ConnectionProfile profile = ConnectionProfile.fromJson(new JSONObject().put("name", "Test").put("type", "hysteria2")
                .put("server", "example.com").put("port", 443).put("password", "test-only").put("rules", stored));
        assertFalse(PocketConfig.create(profile, "192.0.2.1", null, null, null, false).contains("\"direct\""));

        JSONObject config = new JSONObject(PocketConfig.create(profile, "192.0.2.1", null, null, null, true));
        JSONArray route = config.getJSONObject("route").getJSONArray("rules");
        assertEquals("Domains need sniffing", "sniff", route.getJSONObject(0).getString("action"));
        assertEquals("hijack-dns", route.getJSONObject(1).getString("action"));
        assertEquals("IPv6 stays rejected before the provider's rules", 6, route.getJSONObject(2).getInt("ip_version"));
        assertEquals("direct", route.getJSONObject(3).getString("outbound"));
        assertEquals("reject", route.getJSONObject(4).getString("action"));
        boolean directOutbound = false;
        JSONArray outbounds = config.getJSONArray("outbounds");
        for (int i = 0; i < outbounds.length(); i++) directOutbound |= "direct".equals(outbounds.getJSONObject(i).optString("tag"));
        assertTrue(directOutbound);
        assertEquals("proxy", config.getJSONObject("route").getString("final"));
        assertEquals("Only TCP leaves directly", "tcp", route.getJSONObject(3).getString("network"));

        // With Russian sites direct, DNS ports and IP-echo services still win over the provider's direct rule.
        String withRu = PocketConfig.create(profile, "192.0.2.1", java.util.Collections.singletonList("5.0.0.0/8"), null, null, true);
        JSONArray ruRoute = new JSONObject(withRu).getJSONObject("route").getJSONArray("rules");
        int echo = -1, providerDirect = -1;
        for (int i = 0; i < ruRoute.length(); i++) {
            JSONObject rule = ruRoute.getJSONObject(i);
            if (rule.has("domain_suffix") && rule.getJSONArray("domain_suffix").toString().contains("2ip.ru")) echo = i;
            if (rule.has("domain_suffix") && "ru".equals(rule.getJSONArray("domain_suffix").optString(0))) providerDirect = i;
        }
        assertTrue(echo >= 0 && providerDirect > echo);
        assertEquals("One direct outbound", 1, withRu.split("\"tag\":\"direct\"", -1).length - 1);
    }

    @Test public void onlyLiteralAddressesAreTakenAsCidr() {
        assertTrue(XrayImport.isCidr("10.0.0.0/8"));
        assertTrue(XrayImport.isCidr("1.1.1.1"));
        assertTrue(XrayImport.isCidr("2001:db8::/32"));
        assertFalse(XrayImport.isCidr("cafe:babe"));
        assertFalse(XrayImport.isCidr("10:20"));
        assertFalse(XrayImport.isCidr("300.1.1.1"));
        assertFalse(XrayImport.isCidr("10.0.0.0/33"));
        assertFalse(XrayImport.isCidr("geoip:ru"));
        assertFalse(XrayImport.isCidr("example.com"));
    }
}
