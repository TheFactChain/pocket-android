package net.typeblog.shelter.security;

import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MessageAuthenticator {
    private MessageAuthenticator() {}

    public static String sign(String key, byte[] payload) {
        try {
            byte[] secret = decode(key);
            if (secret.length != 32) throw new IllegalArgumentException("Invalid authentication key");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hex(mac.doFinal(payload));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Authentication unavailable", e);
        }
    }

    public static boolean verify(String key, byte[] payload, String signature, long timestamp, long now) {
        if (timestamp > now || timestamp < now - 30000L) return false;
        try {
            return MessageDigest.isEqual(decode(sign(key, payload)), decode(signature));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static byte[] decode(String value) {
        if (value == null || value.length() != 64 || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid signature");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
