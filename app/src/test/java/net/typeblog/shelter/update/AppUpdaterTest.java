package net.typeblog.shelter.update;

import static org.junit.Assert.*;

import org.junit.Test;

public class AppUpdaterTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static String json(String url, String sha, String size, String extra) {
        return "{\"schema\":1,\"versionCode\":5,\"versionName\":\"1.2\",\"url\":\"" + url + "\",\"sha256\":\"" + sha
                + "\",\"size\":" + size + extra + "}";
    }

    private static void bad(String s) {
        try { AppUpdater.parse(s); fail(s); } catch (IllegalArgumentException expected) { }
    }

    @Test public void valid() {
        AppUpdater.Manifest m = AppUpdater.parse(json("https://example.com/a.apk", SHA, "1000", ",\"notes\":\"n\""));
        assertEquals(5, m.versionCode);
        assertEquals("1.2", m.versionName);
        assertEquals(1000, m.size);
        assertEquals("n", m.notes);
    }

    @Test public void rejects() {
        bad(json("https://example.com/a.apk", SHA, "1000", ",\"extra\":1"));
        bad(json("http://example.com/a.apk", SHA, "1000", ""));
        bad(json("https://u:p@example.com/a.apk", SHA, "1000", ""));
        bad(json("https://example.com/a.apk#x", SHA, "1000", ""));
        bad(json("https://example.com/a.apk", SHA.toUpperCase(), "1000", ""));
        bad(json("https://example.com/a.apk", "abc", "1000", ""));
        bad(json("https://example.com/a.apk", SHA, "0", ""));
        bad(json("https://example.com/a.apk", SHA, String.valueOf(200L * 1024 * 1024 + 1), ""));
        bad(json("https://example.com/a.apk", SHA, "\"1000\"", ""));
        bad("{\"schema\":2}");
        bad("мусор");
    }

    @Test public void debugLoopback() {
        String j = json("http://127.0.0.1:8765/a.apk", SHA, "10", "");
        bad(j);
        assertNotNull(AppUpdater.parse(j, true));
        try { AppUpdater.parse(json("http://example.com/a.apk", SHA, "10", ""), true); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void newer() {
        AppUpdater.Manifest m = AppUpdater.parse(json("https://example.com/a.apk", SHA, "1000", ""));
        assertTrue(AppUpdater.isNewer(m, 4));
        assertFalse(AppUpdater.isNewer(m, 5));
        assertFalse(AppUpdater.isNewer(m, 6));
    }
}
