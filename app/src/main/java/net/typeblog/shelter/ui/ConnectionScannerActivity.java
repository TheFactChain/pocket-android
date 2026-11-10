package net.typeblog.shelter.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import net.typeblog.shelter.R;

/** Offline QR scanner, entered only through the user's scan action. */
public class ConnectionScannerActivity extends AppCompatActivity {
    public static final String EXTRA_QR_TEXT = "connection_qr_text";
    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() == null) {
                    setResult(RESULT_CANCELED);
                } else {
                    setResult(RESULT_OK, new Intent().putExtra(
                            EXTRA_QR_TEXT, result.getContents()));
                }
                finish();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        if (savedInstanceState == null) {
            ScanOptions options = new ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setBarcodeImageEnabled(false)
                    .setOrientationLocked(false)
                    .setPrompt(getString(R.string.pc_scan_prompt))
                    .setCaptureActivity(SecureCaptureActivity.class);
            scanner.launch(options);
        }
    }

    /** The camera window must also prevent screenshots and recent-app previews. */
    public static class SecureCaptureActivity extends CaptureActivity {
        @Override protected void onCreate(Bundle savedInstanceState) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            super.onCreate(savedInstanceState);
        }
    }
}
