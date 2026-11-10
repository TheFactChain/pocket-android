package net.typeblog.shelter.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class FinalizeActivity extends AppCompatActivity {
    private final androidx.activity.result.ActivityResultLauncher<Intent> finalizeProfile=registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),result -> {
                setResult(result.getResultCode());finish();
            });
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { net.typeblog.shelter.vpn.ProtectionPolicy.ensure(this); }
        catch(RuntimeException e){
            android.widget.Toast.makeText(this,"Не удалось подтвердить системную защиту Pocket. Профиль сохранён.",android.widget.Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);finish();return;
        }
        if(savedInstanceState!=null)return;
        Intent i = new Intent(getApplicationContext(), DummyActivity.class);
        i.setAction(DummyActivity.FINALIZE_PROVISION);
        DummyActivity.registerSameProcessRequest(i);
        finalizeProfile.launch(i);
    }
}
