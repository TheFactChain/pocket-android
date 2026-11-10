package net.typeblog.shelter.ui;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.CrossProfileApps;
import android.os.Bundle;
import android.os.UserHandle;
import android.widget.Toast;
import net.typeblog.shelter.vpn.ProtectionPolicy;

/** Opens our own personal-profile activity from the work VPN notification. */
public final class OpenPocketActivity extends Activity {
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        try {
            if(ProtectionPolicy.isOwner(this)) {
                CrossProfileApps profiles=getSystemService(CrossProfileApps.class);
                if(profiles.getTargetUserProfiles().isEmpty()) throw new IllegalStateException();
                profiles.startMainActivity(new ComponentName(getPackageName(),MainActivity.class.getName()),profiles.getTargetUserProfiles().get(0));
            } else startActivity(new Intent(this,MainActivity.class));
        } catch(RuntimeException e){ Toast.makeText(this,"Открой Pocket в личном профиле",Toast.LENGTH_LONG).show(); }
        finish();
    }
}
