package net.typeblog.shelter;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getTargetContext();

        assertEquals("io.github.nekontritce11123.pocketandroid", appContext.getPackageName());
    }
    @Test
    public void excludedFeaturesAreNotPackaged() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS |
                        PackageManager.GET_PERMISSIONS | PackageManager.MATCH_DISABLED_COMPONENTS);
        Set<String> excluded = new HashSet<>(Arrays.asList(
                "net.typeblog.shelter.services.FreezeService",
                "net.typeblog.shelter.services.FileShuttleService",
                "net.typeblog.shelter.services.PaymentStubService",
                "net.typeblog.shelter.util.CrossProfileDocumentsProvider"));
        for (android.content.pm.ServiceInfo service : info.services) {
            assertFalse(service.name, excluded.contains(service.name));
        }
        boolean apkProviderFound = false;
        for (android.content.pm.ProviderInfo provider : info.providers) {
            assertFalse(provider.name, excluded.contains(provider.name));
            if (provider.name.equals("net.typeblog.shelter.util.FileProviderProxy")) {
                apkProviderFound = true;
                assertEquals(context.getPackageName() + ".files", provider.authority);
                assertFalse(provider.exported);
                assertTrue(provider.grantUriPermissions);
            }
        }
        assertTrue("APK FileProvider must remain available", apkProviderFound);
        Set<String> permissions = new HashSet<>(info.requestedPermissions == null ?
                Collections.emptyList() : Arrays.asList(info.requestedPermissions));
        for (String permission : Arrays.asList("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE",
                "WRITE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS", "NFC")) {
            assertFalse(permission, permissions.contains("android.permission." + permission));
        }
    }
    @Test
    public void excludedActionsAreNotResolvable() {
        Context context = InstrumentationRegistry.getTargetContext();
        for (String action : Arrays.asList("PUBLIC_FREEZE_ALL", "FREEZE_ALL_IN_LIST",
                "START_FILE_SHUTTLE", "START_FILE_SHUTTLE_2")) {
            android.content.Intent intent = new android.content.Intent(
                    context.getPackageName() + ".action." + action).setPackage(context.getPackageName());
            assertTrue(action, context.getPackageManager().queryIntentActivities(intent, 0).isEmpty());
        }
        assertEquals("Pocket", context.getString(R.string.app_name));
    }
}
