package net.typeblog.shelter;

import android.content.Context;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import net.typeblog.shelter.vpn.ProtectionPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class PocketWorkProfilePolicyTest {
    @Test public void workProfileAllowsInstallSourcesAndKeepsVpnLockdown() {
        Context context = InstrumentationRegistry.getTargetContext();
        assumeTrue("Run in Pocket's managed work profile", ProtectionPolicy.isOwner(context));

        assertTrue("Pocket VPN lockdown must remain active", ProtectionPolicy.confirmed(context));
        assertFalse("The work profile blocks installation from unknown sources",
                context.getSystemService(UserManager.class)
                        .hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));
    }
}
