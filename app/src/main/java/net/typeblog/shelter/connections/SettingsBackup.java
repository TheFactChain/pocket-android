package net.typeblog.shelter.connections;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-encrypted settings file: PBKDF2-HMAC-SHA256 key, AES-256-GCM. No Android dependencies. */
public final class SettingsBackup {
    private static final String FORMAT = "pocket-settings", KDF = "PBKDF2WithHmacSHA256";
    private static final int VERSION = 1, ITERATIONS = 600_000, MIN_ITERATIONS = 100_000, MAX_ITERATIONS = 1_000_000;
    private static final int MAX_FILE = 4 * 1024 * 1024;
    private static final byte[] AAD = (FORMAT + ":" + VERSION).getBytes(StandardCharsets.US_ASCII);
    private static final String BAD = "Неверный пароль или файл повреждён";

    private SettingsBackup() { }

    public static String encrypt(String json, char[] password) {
        checkPassword(password, 12);
        byte[] salt = new byte[16], iv = new byte[12], plain = json.getBytes(StandardCharsets.UTF_8), key = null;
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        try {
            key = derive(password, salt, ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            Base64.Encoder b64 = Base64.getEncoder();
            return new JSONObject().put("format", FORMAT).put("version", VERSION).put("kdf", KDF)
                    .put("iterations", ITERATIONS).put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
                    .put("data", b64.encodeToString(cipher.doFinal(plain))).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать настройки.");
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    public static String decrypt(String file, char[] password) {
        checkPassword(password, 8);
        if (file == null || file.length() > MAX_FILE) throw new IllegalArgumentException("Файл слишком большой или пустой");
        byte[] key = null, plain = null;
        try {
            JSONObject root = new JSONObject(file);
            int iterations = root.getInt("iterations");
            if (!FORMAT.equals(root.getString("format")) || root.getInt("version") != VERSION || !KDF.equals(root.getString("kdf"))
                    || iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw new IllegalArgumentException("Это не файл настроек Pocket");
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] salt = b64.decode(root.getString("salt")), iv = b64.decode(root.getString("iv")), data = b64.decode(root.getString("data"));
            if (salt.length != 16 || iv.length != 12) throw new IllegalArgumentException(BAD);
            key = derive(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            plain = cipher.doFinal(data);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            if ("Это не файл настроек Pocket".equals(e.getMessage())) throw e;
            throw new IllegalArgumentException(BAD);
        } catch (Exception e) {
            throw new IllegalArgumentException(BAD);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /** New files need 12 characters; files saved earlier with 8 still open. */
    private static void checkPassword(char[] password, int min) {
        if (password == null || password.length < min) throw new IllegalArgumentException("Пароль — не короче " + min + " символов");
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
