package net.typeblog.shelter.connections;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SettingsBackupTest {
    private static final String PLAIN = "{\"connections\":[],\"selected\":\"a\"}";

    @Test public void roundTrip() {
        String file = SettingsBackup.encrypt(PLAIN, "correct horse".toCharArray());
        assertFalse(file.contains("selected"));
        assertEquals(PLAIN, SettingsBackup.decrypt(file, "correct horse".toCharArray()));
    }

    @Test public void wrongPasswordFails() {
        String file = SettingsBackup.encrypt(PLAIN, "correct horse".toCharArray());
        try { SettingsBackup.decrypt(file, "wrong horse!".toCharArray()); fail(); }
        catch (IllegalArgumentException e) { assertEquals("Неверный пароль или файл повреждён", e.getMessage()); }
    }

    @Test public void tamperedDataFails() throws Exception {
        JSONObject json = new JSONObject(SettingsBackup.encrypt(PLAIN, "correct horse".toCharArray()));
        byte[] data = java.util.Base64.getDecoder().decode(json.getString("data"));
        data[0] ^= 1;
        json.put("data", java.util.Base64.getEncoder().encodeToString(data));
        try { SettingsBackup.decrypt(json.toString(), "correct horse".toCharArray()); fail(); }
        catch (IllegalArgumentException e) { assertEquals("Неверный пароль или файл повреждён", e.getMessage()); }
    }

    @Test public void badIterationsAndShortPasswordRejected() throws Exception {
        String file = SettingsBackup.encrypt(PLAIN, "correct horse".toCharArray());
        try { SettingsBackup.decrypt(new JSONObject(file).put("iterations", 10).toString(), "correct horse".toCharArray()); fail(); }
        catch (IllegalArgumentException expected) { }
        try { SettingsBackup.encrypt(PLAIN, "short".toCharArray()); fail(); }
        catch (IllegalArgumentException e) { assertEquals("Пароль — не короче 8 символов", e.getMessage()); }
    }
}
