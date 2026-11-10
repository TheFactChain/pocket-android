package net.typeblog.shelter;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import net.typeblog.shelter.connections.ConnectionProfile;
import net.typeblog.shelter.connections.ProfileStore;
import net.typeblog.shelter.security.SecretFile;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PocketStorageTest {
    private Context isolatedContext() {
        Context base=InstrumentationRegistry.getTargetContext();
        File directory=new File(base.getCacheDir(),"storage-test-"+UUID.randomUUID());
        assertTrue(directory.mkdirs());
        return new ContextWrapper(base) { @Override public File getFilesDir(){ return directory; } };
    }

    @Test public void ciphertextTamperingPreservesEvidenceAndNeverResets() throws Exception {
        Context context=isolatedContext();
        String name="test-secret-"+UUID.randomUUID()+".enc";
        SecretFile secret=new SecretFile(context,name);
        byte[] plain="local-test-password-only".getBytes(StandardCharsets.UTF_8);
        secret.write(plain);
        assertArrayEquals(plain,new SecretFile(context,name).read());
        File path=new File(context.getFilesDir(),name);
        byte[] encoded=Files.readAllBytes(path.toPath());
        assertFalse(new String(encoded,StandardCharsets.ISO_8859_1).contains("local-test-password-only"));
        encoded[encoded.length-1]^=1;
        Files.write(path.toPath(),encoded);
        try { new SecretFile(context,name).read(); fail("Tampered data must be rejected"); }
        catch(IllegalStateException expected) { assertArrayEquals(encoded,Files.readAllBytes(path.toPath())); }
        secret.delete();
        java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);keys.deleteEntry("pocket.private.v1."+name);
    }

    @Test public void profilesSurviveReopenAndActiveDeletionHasNoFallback() throws Exception {
        Context context=isolatedContext();
        ProfileStore store=new ProfileStore(context);
        ConnectionProfile a=profile("A"),b=profile("B");
        store.save(a);store.select(a.id);store.save(b);
        assertEquals(a.id,new ProfileStore(context).selectedId());
        store.select(b.id);
        ProfileStore reopened=new ProfileStore(context);
        assertEquals(b.id,reopened.selectedId());
        assertEquals("test-only",reopened.get(a.id).password);
        reopened.delete(b.id);
        assertNull(new ProfileStore(context).selected());
        assertEquals(a.id,new ProfileStore(context).get(a.id).id);
        assertFalse(new ProfileStore(context).summaries().contains("test-only"));
    }

    private ConnectionProfile profile(String name) throws Exception {
        return ConnectionProfile.fromJson(new JSONObject().put("name",name).put("type","hysteria2")
                .put("server","example.com").put("port",443).put("password","test-only"));
    }
}
