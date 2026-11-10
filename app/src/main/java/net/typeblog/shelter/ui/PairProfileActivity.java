package net.typeblog.shelter.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import net.typeblog.shelter.services.IAuthRequest;
import net.typeblog.shelter.services.IAuthResponse;
import net.typeblog.shelter.util.AuthenticationUtility;
import net.typeblog.shelter.vpn.ProtectionPolicy;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Signature permission admits only Pocket; Binder authenticates both profiles again. */
public final class PairProfileActivity extends Activity {
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,
            new SynchronousQueue<>(),task -> {Thread thread=new Thread(task,"Pocket pairing");thread.setDaemon(true);return thread;});
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean active=true;
    private final Runnable timeout=() -> complete(false);
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setResult(RESULT_CANCELED);
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
    private void complete(boolean success){
        if(!active)return;
        active=false;main.removeCallbacks(timeout);setResult(success?RESULT_OK:RESULT_CANCELED);finish();
    }
    @Override public void onDestroy(){active=false;main.removeCallbacksAndMessages(null);super.onDestroy();}
}
