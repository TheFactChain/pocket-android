package net.typeblog.shelter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserManager;
import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver;
import net.typeblog.shelter.services.IAuthRequest;
import net.typeblog.shelter.services.IAuthResponse;
import net.typeblog.shelter.util.AuthenticationUtility;
import net.typeblog.shelter.vpn.ProtectionPolicy;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Signature permission admits only Pocket; Binder authenticates both profiles again. */
public final class PairProfileActivity extends Activity {
    public static final String EXTRA_REMOVE_PROFILE_SERIAL = BuildConfig.APPLICATION_ID + ".extra.REMOVE_PROFILE_SERIAL";
    public static final int RESULT_REMOVAL_CANCELED = Activity.RESULT_FIRST_USER;
    public static final int RESULT_REMOVAL_UNAVAILABLE = Activity.RESULT_FIRST_USER + 1;
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,
            new SynchronousQueue<>(),task -> {Thread thread=new Thread(task,"Pocket pairing");thread.setDaemon(true);return thread;});
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean active=true;
    private final Runnable timeout=() -> complete(false);
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setResult(RESULT_CANCELED);
        if(getIntent().hasExtra(EXTRA_REMOVE_PROFILE_SERIAL)){
            if(!AuthenticationUtility.checkIntent(getIntent())){finishWith(RESULT_REMOVAL_UNAVAILABLE);return;}
            confirmProfileRemoval(getIntent().getLongExtra(EXTRA_REMOVE_PROFILE_SERIAL,-1));
            return;
        }
        Bundle pairing=getIntent().getBundleExtra("pairing");
        if(!ProtectionPolicy.isOwner(this) || pairing==null || pairing.getBinder("request")==null){complete(false);return;}
        IAuthRequest request=IAuthRequest.Stub.asInterface(pairing.getBinder("request"));
        main.postDelayed(timeout,15000);
        try {
            worker.execute(() -> {
                try {
                    request.shareKey(new IAuthResponse.Stub(){
                        @Override public void acceptKey(String key){
                            if(!active)return;
                            AuthenticationUtility.acceptPairedKey(key);
                            main.post(() -> complete(true));
                        }
                    });
                }catch(Exception e){main.post(() -> complete(false));}
            });
        }catch(java.util.concurrent.RejectedExecutionException e){complete(false);}
    }
    private boolean mayRemoveProfile(long serial) {
        DevicePolicyManager dpm=getSystemService(DevicePolicyManager.class);
        UserManager users=getSystemService(UserManager.class);
        ComponentName admin=new ComponentName(this,ShelterDeviceAdminReceiver.class);
        return serial>0 && dpm!=null && users!=null && dpm.isProfileOwnerApp(getPackageName())
                && !dpm.isDeviceOwnerApp(getPackageName()) && dpm.isManagedProfile(admin)
                && !users.isSystemUser() && (Build.VERSION.SDK_INT<Build.VERSION_CODES.R || users.isManagedProfile())
                && serial==users.getSerialNumberForUser(Process.myUserHandle())
                && dpm.hasGrantedPolicy(admin,DeviceAdminInfo.USES_POLICY_WIPE_DATA);
    }

    private void confirmProfileRemoval(long serial) {
        try {
            if(!mayRemoveProfile(serial)){removalUnavailable();return;}
        } catch(RuntimeException e){removalUnavailable();return;}
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_pocket_title)
                .setMessage(R.string.remove_pocket_message)
                .setNegativeButton(R.string.remove_pocket_cancel,(dialog,which) -> finishWith(RESULT_REMOVAL_CANCELED))
                .setPositiveButton(R.string.remove_pocket_confirm,(dialog,which) -> {
                    try {
                        if(!mayRemoveProfile(serial)){removalUnavailable();return;}
                        getSystemService(DevicePolicyManager.class).wipeData(0,getString(R.string.remove_pocket_title));
                        finishWith(RESULT_OK);
                    } catch(RuntimeException e){removalUnavailable();}
                })
                .setOnCancelListener(dialog -> finishWith(RESULT_REMOVAL_CANCELED))
                .show();
    }

    private void removalUnavailable() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_pocket_unavailable_title)
                .setMessage(R.string.remove_pocket_unavailable_message)
                .setNegativeButton(R.string.remove_pocket_cancel,(dialog,which) -> finishWith(RESULT_REMOVAL_CANCELED))
                .setPositiveButton(R.string.remove_pocket_open_settings,(dialog,which) -> finishWith(RESULT_REMOVAL_UNAVAILABLE))
                .setOnCancelListener(dialog -> finishWith(RESULT_REMOVAL_CANCELED))
                .show();
    }

    private void complete(boolean success){finishWith(success?RESULT_OK:RESULT_CANCELED);}
    private void finishWith(int result){
        if(!active)return;
        active=false;main.removeCallbacks(timeout);setResult(result);finish();
    }
    @Override public void onDestroy(){active=false;main.removeCallbacksAndMessages(null);super.onDestroy();}
}
