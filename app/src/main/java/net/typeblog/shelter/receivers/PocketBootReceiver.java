package net.typeblog.shelter.receivers;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import net.typeblog.shelter.util.Utility;
import net.typeblog.shelter.vpn.ProtectionPolicy;
import net.typeblog.shelter.vpn.PocketVpnService;

public final class PocketBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!ProtectionPolicy.isOwner(context))return;
        try { Utility.enforceWorkProfilePolicies(context); ProtectionPolicy.ensure(context); PocketVpnService.start(context); }
        catch(RuntimeException e){ android.util.Log.w("PocketVPN","Profile protection needs attention"); }
    }
}
