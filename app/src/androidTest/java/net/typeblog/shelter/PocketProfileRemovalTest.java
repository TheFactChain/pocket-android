package net.typeblog.shelter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import net.typeblog.shelter.ui.PairProfileActivity;
import net.typeblog.shelter.vpn.ProtectionPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PocketProfileRemovalTest {
    @Test public void unsignedRemovalCommandIsRejected() {
        Context context = InstrumentationRegistry.getTargetContext();
        long serial = context.getSystemService(UserManager.class).getSerialNumberForUser(Process.myUserHandle());
        Intent intent = new Intent(context, PairProfileActivity.class)
                .putExtra(PairProfileActivity.EXTRA_REMOVE_PROFILE_SERIAL, serial)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        try {
            assertTrue("An unsigned request must be rejected immediately", activity.isFinishing());
        } finally {
            activity.finish();
        }
    }

    @Test public void removalGuardRejectsWrongProfileSerial() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        long serial = context.getSystemService(UserManager.class).getSerialNumberForUser(Process.myUserHandle());
        Intent intent = new Intent(context, PairProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        try {
            Method guard = PairProfileActivity.class.getDeclaredMethod("mayRemoveProfile", long.class);
            guard.setAccessible(true);
            assertFalse((Boolean) guard.invoke(activity, serial + 1));
            if (ProtectionPolicy.isOwner(context)) assertTrue((Boolean) guard.invoke(activity, serial));
        } finally {
            activity.finish();
        }
    }
}
