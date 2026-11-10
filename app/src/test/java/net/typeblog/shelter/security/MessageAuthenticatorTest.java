package net.typeblog.shelter.security;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class MessageAuthenticatorTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @Test public void signatureBindsEveryPayloadByte() {
        byte[] payload = "launch|work|app.one|nonce-1|100000".getBytes(StandardCharsets.UTF_8);
        String signature = MessageAuthenticator.sign(KEY, payload);
        assertTrue(MessageAuthenticator.verify(KEY, payload, signature, 100000, 100010));
        assertFalse(MessageAuthenticator.verify(KEY, "launch|work|app.two|nonce-1|100000".getBytes(StandardCharsets.UTF_8), signature, 100000, 100010));
    }
    @Test public void rejectsFutureAndExpiredMessages() {
        byte[] payload = new byte[]{1, 2, 3};
        String signature = MessageAuthenticator.sign(KEY, payload);
        assertFalse(MessageAuthenticator.verify(KEY, payload, signature, 100001, 100000));
        assertFalse(MessageAuthenticator.verify(KEY, payload, signature, 69999, 100000));
        assertTrue(MessageAuthenticator.verify(KEY, payload, signature, 70000, 100000));
    }
    @Test public void malformedSignatureNeverAuthenticates() {
        assertFalse(MessageAuthenticator.verify(KEY, new byte[]{1}, "not-hex", 1, 1));
        assertFalse(MessageAuthenticator.verify(KEY, new byte[]{1}, null, 1, 1));
    }
}
