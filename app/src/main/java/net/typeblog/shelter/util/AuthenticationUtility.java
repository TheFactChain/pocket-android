package net.typeblog.shelter.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import net.typeblog.shelter.security.MessageAuthenticator;
import net.typeblog.shelter.security.SecretFile;
import net.typeblog.shelter.services.IAuthRequest;
import net.typeblog.shelter.services.IAuthResponse;
import net.typeblog.shelter.vpn.ProtectionPolicy;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.UUID;

/** Full-payload authentication; Android's Binder UID authenticates initial pairing. */
public final class AuthenticationUtility {
    private static Context context;
    private static SecretFile keyFile;
    private static SecretFile replayFile;
    private static String cachedKey;
    private static final String PREFIX = "_pocket_auth_";
    private AuthenticationUtility() {}

    public static void initialize(Context value) {
        context = value.getApplicationContext();
        keyFile = new SecretFile(context, "auth-key.v1.enc");
        replayFile = new SecretFile(context, "auth-replay.v1.enc");
    }

    private static synchronized String key(boolean create) {
        if (cachedKey != null) return cachedKey;
        byte[] bytes = keyFile.read();
        if (bytes != null) cachedKey = new String(bytes, StandardCharsets.UTF_8);
        else {
            // Upgrade a local, already-paired Shelter key; never accept a key from an intent.
            cachedKey = LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_AUTH_KEY);
            if (cachedKey == null && create) {
                byte[] secret = new byte[32];
                new SecureRandom().nextBytes(secret);
                cachedKey = MessageAuthenticator.hex(secret);
                java.util.Arrays.fill(secret, (byte) 0);
            }
            if (cachedKey != null) {
                MessageAuthenticator.sign(cachedKey, new byte[0]);
                keyFile.write(cachedKey.getBytes(StandardCharsets.UTF_8));
                LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_KEY);
            }
        }
        if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        return cachedKey;
    }

    public static void requireTrustedBinderCaller() {
        int caller = Binder.getCallingUid();
        if (caller != Process.myUid() && context.getPackageManager().checkSignatures(caller, Process.myUid()) != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Команда отправлена другим приложением");
    }

    public static void putPairingRequest(Intent intent) {
        Bundle request = new Bundle();
        request.putBinder("request", new IAuthRequest.Stub() {
            @Override public void shareKey(IAuthResponse response) throws android.os.RemoteException {
                requireTrustedBinderCaller();
                response.acceptKey(key(true));
            }
        });
        intent.putExtra("pairing", request);
    }

    public static synchronized void acceptPairedKey(String value) {
        requireTrustedBinderCaller();
        if (!ProtectionPolicy.isOwner(context)) throw new SecurityException("Нет рабочего профиля Pocket");
        MessageAuthenticator.sign(value, new byte[0]);
        keyFile.write(value.getBytes(StandardCharsets.UTF_8));
        cachedKey = value;
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_KEY);
    }

    public static void signIntent(Intent intent) {
        String secret = key(false);
        if (secret == null) throw new IllegalStateException("Открой Pocket, чтобы связать рабочий профиль");
        intent.putExtra(PREFIX + "time", android.os.SystemClock.elapsedRealtime());
        intent.putExtra(PREFIX + "boot", android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 0));
        intent.putExtra(PREFIX + "nonce", UUID.randomUUID().toString());
        intent.putExtra(PREFIX + "target", ProtectionPolicy.isOwner(context) ? "personal" : "work");
        intent.putExtra(PREFIX + "mac", MessageAuthenticator.sign(secret, payload(intent)));
    }

    public static String attest(String nonce, String role) {
        if(nonce==null || !nonce.matches("[a-fA-F0-9-]{36}")) throw new SecurityException("Invalid challenge");
        String secret=key(false);
        if(secret==null)throw new SecurityException("Профиль ещё не связан");
        return MessageAuthenticator.sign(secret,("Pocket service v1|"+role+"|"+nonce).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verifyAttestation(String nonce,String role,String signature){
        return signature!=null && java.security.MessageDigest.isEqual(attest(nonce,role).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8));
    }

    public static synchronized boolean checkIntent(Intent intent) {
        try {
            String secret = key(false);
            if (secret == null) return false;
            String expected = ProtectionPolicy.isOwner(context) ? "work" : "personal";
            if (!expected.equals(intent.getStringExtra(PREFIX + "target"))) return false;
            long now = android.os.SystemClock.elapsedRealtime();
            if (intent.getIntExtra(PREFIX + "boot", -1) != android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 0)) return false;
            long timestamp = intent.getLongExtra(PREFIX + "time", 0);
            String nonce = intent.getStringExtra(PREFIX + "nonce");
            if (nonce == null || !nonce.matches("[a-fA-F0-9-]{36}")) return false;
            if (!MessageAuthenticator.verify(secret, payload(intent), intent.getStringExtra(PREFIX + "mac"), timestamp, now)) return false;
            byte[] stored = replayFile.read();
            JSONObject seen = stored == null ? new JSONObject() : new JSONObject(new String(stored, StandardCharsets.UTF_8));
            if (seen.has(nonce)) return false;
            Iterator<String> iterator = seen.keys();
            while (iterator.hasNext()) if (seen.getLong(iterator.next()) < now - 30000L) iterator.remove();
            seen.put(nonce, timestamp);
            replayFile.write(seen.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) { return false; }
    }

    public static synchronized void reset() {
        cachedKey = null;
        keyFile.delete();
        replayFile.delete();
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_KEY);
    }

    private static byte[] payload(Intent intent) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            writeString(output, intent.getAction());
            writeString(output, intent.getDataString());
            writeString(output, intent.getType());
            Bundle extras = intent.getExtras();
            if (extras != null) writeBundle(output, extras);
            return buffer.toByteArray();
        } catch (Exception e) { throw new IllegalArgumentException("Некорректная команда Pocket"); }
    }

    private static void writeBundle(DataOutputStream output, Bundle extras) throws Exception {
        ArrayList<String> names = new ArrayList<>(extras.keySet());
        Collections.sort(names);
        output.writeInt(names.size() - (names.contains(PREFIX + "mac") ? 1 : 0));
        for (String name : names) {
            if ((PREFIX + "mac").equals(name)) continue;
            writeString(output, name);
            Object value = extras.get(name);
            if (value == null) output.writeByte(0);
            else if (value instanceof String) { output.writeByte(1); writeString(output, (String) value); }
            else if (value instanceof Boolean) { output.writeByte(2); output.writeBoolean((Boolean) value); }
            else if (value instanceof Integer) { output.writeByte(3); output.writeInt((Integer) value); }
            else if (value instanceof Long) { output.writeByte(4); output.writeLong((Long) value); }
            else if (value instanceof String[]) { output.writeByte(5); String[] values=(String[])value; output.writeInt(values.length); for(String item:values) writeString(output,item); }
            else if (value instanceof Bundle) { output.writeByte(6); writeBundle(output,(Bundle)value); output.writeByte(0); }
            else if (value instanceof Uri) { output.writeByte(7); writeString(output,value.toString()); }
            else if (value instanceof IBinder) { output.writeByte(8); writeString(output,((IBinder)value).getInterfaceDescriptor()); }
            else throw new IllegalArgumentException("Unsupported command argument");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(value == null ? -1 : bytes.length);
        output.write(bytes);
    }
}
