package net.typeblog.shelter.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private credential-protected files; every write has a fresh nonce and an atomic commit. */
public final class SecretFile {
    private static final String ALIAS = "pocket.private.v1";
    private final AtomicFile file;
    private final byte[] associatedData;
    private final String keyAlias;

    public SecretFile(Context context, String name) {
        if (!name.matches("[a-z0-9.-]+")) throw new IllegalArgumentException("Invalid private file name");
        file = new AtomicFile(new File(context.getFilesDir(), name));
        associatedData = name.getBytes(StandardCharsets.UTF_8);
        keyAlias = ALIAS + "." + name;
    }

    public synchronized byte[] read() {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        try {
            byte[] encoded = file.readFully();
            if (encoded.length < 30 || encoded.length > 2 * 1024 * 1024 || encoded[0] != 1) throw new IllegalStateException("Invalid private file");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, encoded, 1, 12));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(encoded, 13, encoded.length - 13);
        } catch (Exception e) {
            throw new IllegalStateException("Защищённые настройки недоступны. Данные сохранены для восстановления.", e);
        }
    }

    public synchronized void write(byte[] plaintext) {
        if (plaintext.length > 1024 * 1024) throw new IllegalArgumentException("Слишком много настроек");
        FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(associatedData);
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IllegalStateException("Invalid nonce length");
            byte[] ciphertext = cipher.doFinal(plaintext);
            output = file.startWrite();
            output.write(1);
            output.write(iv);
            output.write(ciphertext);
            file.finishWrite(output);
            output = null;
            Arrays.fill(ciphertext, (byte) 0);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("Не удалось сохранить защищённые настройки.", e);
        }
    }

    public synchronized void delete() { file.delete(); }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(keyAlias, null);
        if (existing != null) return existing;
        if (!create) throw new IllegalStateException("Private key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
