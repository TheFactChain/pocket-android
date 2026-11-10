package net.typeblog.shelter.ui;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.ShelterApplication;
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver;
import net.typeblog.shelter.services.IAppInstallCallback;
import net.typeblog.shelter.util.AuthenticationUtility;
import net.typeblog.shelter.util.FileProviderProxy;
import net.typeblog.shelter.util.LocalStorageManager;
import net.typeblog.shelter.util.Utility;
import net.typeblog.shelter.vpn.ProtectionPolicy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

// DummyActivity does nothing about presenting any UI
// It is a wrapper over various different operations
// that might be required to perform across user profiles
// which is only possible through Intents that are in
// the crossProfileIntentFilter
public class DummyActivity extends Activity {
    public static final String FINALIZE_PROVISION = BuildConfig.APPLICATION_ID + ".action.FINALIZE_PROVISION";
    public static final String AUTHENTICATE = BuildConfig.APPLICATION_ID + ".action.AUTHENTICATE";
    public static final String START_SERVICE = BuildConfig.APPLICATION_ID + ".action.START_SERVICE";
    public static final String TRY_START_SERVICE = BuildConfig.APPLICATION_ID + ".action.TRY_START_SERVICE";
    public static final String INSTALL_PACKAGE = BuildConfig.APPLICATION_ID + ".action.INSTALL_PACKAGE";
    public static final String UNINSTALL_PACKAGE = BuildConfig.APPLICATION_ID + ".action.UNINSTALL_PACKAGE";
    public static final String UNFREEZE_AND_LAUNCH = BuildConfig.APPLICATION_ID + ".action.UNFREEZE_AND_LAUNCH";
    public static final String PUBLIC_UNFREEZE_AND_LAUNCH = BuildConfig.APPLICATION_ID + ".action.PUBLIC_UNFREEZE_AND_LAUNCH";
    public static final String PUBLIC_FREEZE_ALL = BuildConfig.APPLICATION_ID + ".action.PUBLIC_FREEZE_ALL";
    public static final String FREEZE_ALL_IN_LIST = BuildConfig.APPLICATION_ID + ".action.FREEZE_ALL_IN_LIST";
    // If we use the same intent for parent -> profile and profile -> parent, the user will
    // be prompted with the action chooser with only one choice in it when the intent is
    // forwarded by Utility.transferIntentToProfile()
    // This is a bad experience, so we use two to avoid this.
    public static final String START_FILE_SHUTTLE = BuildConfig.APPLICATION_ID + ".action.START_FILE_SHUTTLE";
    public static final String START_FILE_SHUTTLE_2 = BuildConfig.APPLICATION_ID + ".action.START_FILE_SHUTTLE_2";
    public static final String SYNCHRONIZE_PREFERENCE = BuildConfig.APPLICATION_ID + ".action.SYNCHRONIZE_PREFERENCE";
    public static final String PACKAGEINSTALLER_CALLBACK = BuildConfig.APPLICATION_ID + ".action.PACKAGEINSTALLER_CALLBACK";

    // Only these actions are allowed to be called from the same process (pre-registered)
    // without a valid signature
    private static final List<String> ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS = Arrays.asList(
            INSTALL_PACKAGE,
            UNINSTALL_PACKAGE,
            UNFREEZE_AND_LAUNCH,
            FINALIZE_PROVISION);

    private static final int REQUEST_INSTALL_PACKAGE = 1;
    private static final int REQUEST_PERMISSION_POST_NOTIFICATIONS = 3;
    private static final String INSTALL_LEDGER = "pocket_pending_install_v1";
    private static final String STATE_BUSY = "pocket_operation_busy";
    private static final String STATE_CALLBACK = "pocket_operation_callback";

    private static boolean sHasRequestedPermission = false;

    // One-use local capabilities restore the original payload instead of trusting forwarded extras.
    private static final java.util.Map<String,LocalRequest> localRequests=new java.util.HashMap<>();
    private static final class LocalRequest {
        final Intent intent; final long expires;
        LocalRequest(Intent value){intent=new Intent(value);expires=android.os.SystemClock.elapsedRealtime()+10000;}
    }
    public static synchronized void registerSameProcessRequest(Intent intent){
        long now=android.os.SystemClock.elapsedRealtime();
        localRequests.entrySet().removeIf(entry -> entry.getValue().expires<now);
        if(localRequests.size()>=32)throw new IllegalStateException("Too many pending operations");
        String token=UUID.randomUUID().toString();
        intent.putExtra("_pocket_local_request",token);
        localRequests.put(token,new LocalRequest(intent));
    }
    private static synchronized Intent takeLocalRequest(Intent intent){
        LocalRequest request=localRequests.remove(intent.getStringExtra("_pocket_local_request"));
        return request!=null && request.expires>=android.os.SystemClock.elapsedRealtime()?request.intent:null;
    }
    private boolean mIsProfileOwner = false;
    private DevicePolicyManager mPolicyManager = null;
    private InstallerCallbackGate mInstallerGate;
    private PendingIntent mInstallerResult;
    private PackageInstaller.SessionCallback mInstallerSessionCallback;
    private Thread mInstallThread;
    private boolean mBusy;
    private boolean mPermissionPending;
    private boolean mAwaitingUninstallResult;
    private boolean mCompletionSent;
    private Bundle mOperationCallback;

    static final class InstallerCallbackGate {
        final int sessionId;
        final String token;
        private boolean consumed;
        volatile boolean cancelled;

        InstallerCallbackGate(int sessionId, String token) {
            this.sessionId = sessionId;
            this.token = token;
        }

        synchronized boolean consume(int sessionId, String token) {
            if (cancelled || consumed || this.sessionId != sessionId || !this.token.equals(token)) return false;
            consumed = true;
            return true;
        }

        void cancel() { cancelled = true; }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPolicyManager = getSystemService(DevicePolicyManager.class);
        mIsProfileOwner = mPolicyManager.isProfileOwnerApp(getPackageName());
        try {
            boolean recovered = abandonRecordedInstall();
            if (savedInstanceState != null || PACKAGEINSTALLER_CALLBACK.equals(getIntent().getAction())) {
                if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_BUSY)) {
                    notifyInstallCallback(savedInstanceState.getBundle(STATE_CALLBACK), RESULT_CANCELED);
                }
                Toast.makeText(this, "Операция прервана. Проверь список приложений в Pocket и повтори её при необходимости.", Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
            if (recovered) {
                Toast.makeText(this, "Предыдущая установка прервана. Проверь список приложений в Pocket и повтори её при необходимости.", Toast.LENGTH_LONG).show();
            }
            receiveCommand(getIntent());
        } catch (RuntimeException e) {
            Toast.makeText(this, "Не удалось завершить предыдущую операцию. Открой Pocket и повтори позже.", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private Intent authorizeCommand(Intent intent) {
        try {
            if (PUBLIC_UNFREEZE_AND_LAUNCH.equals(intent.getAction())) {
                return Utility.resolveWorkShortcut(this, intent.getStringExtra("_pocket_shortcut"));
            }
            Intent local = takeLocalRequest(intent);
            if (local != null && ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS.contains(local.getAction())) return local;
            return AuthenticationUtility.checkIntent(intent) ? intent : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void receiveCommand(Intent incoming) {
        Intent command = authorizeCommand(incoming);
        if (command == null) {
            Toast.makeText(this, "Команда отклонена. Для рабочего ярлыка может потребоваться пересоздание в Pocket.", Toast.LENGTH_LONG).show();
            if (!mBusy) { setResult(RESULT_CANCELED); finish(); }
            return;
        }
        if (mBusy || isFinishing()) {
            // A rejected command must never replace the active operation or its callback.
            notifyInstallCallback(command.getBundleExtra("callback"), RESULT_CANCELED);
            Toast.makeText(this, "Pocket занят другой операцией. Дождись её завершения и повтори.", Toast.LENGTH_LONG).show();
            return;
        }
        setIntent(command);
        mOperationCallback = command.getBundleExtra("callback");
        mCompletionSent = false;
        mBusy = true;
        if (mIsProfileOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !sHasRequestedPermission && !FINALIZE_PROVISION.equals(command.getAction())) {
            sHasRequestedPermission = true;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                mPermissionPending = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSION_POST_NOTIFICATIONS);
                return;
            }
        }
        dispatchCommand();
    }

    private void dispatchCommand() {
        Intent intent = getIntent();
        if (START_SERVICE.equals(intent.getAction())) {
            actionStartService();
        } else if (TRY_START_SERVICE.equals(intent.getAction())) {
            setResult(RESULT_OK);
            finish();
        } else if (INSTALL_PACKAGE.equals(intent.getAction())) {
            actionInstallPackage();
        } else if (UNINSTALL_PACKAGE.equals(intent.getAction())) {
            actionUninstallPackage();
        } else if (FINALIZE_PROVISION.equals(intent.getAction())) {
            actionFinalizeProvision();
        } else if (UNFREEZE_AND_LAUNCH.equals(intent.getAction())) {
            actionUnfreezeAndLaunch();
        } else if (SYNCHRONIZE_PREFERENCE.equals(intent.getAction())) {
            actionSynchronizePreference();
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (!PACKAGEINSTALLER_CALLBACK.equals(intent.getAction())) {
            receiveCommand(intent);
            return;
        }
        if (mInstallerGate == null) return;
        try {
            if (!mInstallerGate.consume(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                    intent.getStringExtra("_pocket_install_token"))) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation == null) throw new IllegalArgumentException("Нет подтверждения установки");
                startActivity(confirmation);
            } else if (status == PackageInstaller.STATUS_FAILURE_STORAGE) {
                appInstallFinished(net.typeblog.shelter.services.ShelterService.RESULT_NO_SPACE);
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                appInstallFinished(Activity.RESULT_CANCELED);
            }
            // The one-use intent token authenticates confirmation; only Android's session callback reports success.
        } catch (RuntimeException e) {
            appInstallFinished(Activity.RESULT_CANCELED);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_INSTALL_PACKAGE && mAwaitingUninstallResult && mBusy) {
            mAwaitingUninstallResult = false;
            appInstallFinished(resultCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_POST_NOTIFICATIONS && mPermissionPending && mBusy) {
            mPermissionPending = false;
            // The command was authenticated before the permission dialog; never consume its capability twice.
            dispatchCommand();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void actionFinalizeProvision() {
        // On API 29+, returning to Android provisioning resumes SetupWizard's availability check.
        setResult(mIsProfileOwner ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    private void actionStartService() {
        // This needs to be foreground because this activity won't be able to hold
        // the ServiceConnection to it.
        ((ShelterApplication) getApplication()).bindShelterService(new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (!mBusy || isFinishing() || isDestroyed()) return;
                Intent data = new Intent();
                Bundle bundle = new Bundle();
                bundle.putBinder("service", service);
                data.putExtra("extra", bundle);
                setResult(RESULT_OK, data);
                finish();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // dummy
            }
        }, true);
    }

    private void actionInstallPackage() {
        try {
            if (mIsProfileOwner) ProtectionPolicy.requireConfirmed(this);
            Uri uri;
            if (getIntent().hasExtra("apk")) {
                String path = getIntent().getStringExtra("apk");
                if (path == null || path.isEmpty()) throw new IOException("APK unavailable");
                uri = Uri.fromFile(new File(path));
            } else {
                uri = getIntent().getParcelableExtra("direct_install_apk");
            }
            if (uri == null) throw new IOException("APK unavailable");
            actionInstallPackageQ(uri, getIntent().getStringArrayExtra("split_apks"));
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Установка не выполнена. Проверь доступность APK и защиту рабочего профиля.", Toast.LENGTH_LONG).show();
            appInstallFinished(RESULT_CANCELED);
        }
    }

    private void actionInstallPackageQ(Uri baseUri, String[] splitApks) throws IOException {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        int sessionId = installer.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        PackageInstaller.Session session;
        try {
            session = installer.openSession(sessionId);
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
        try {
            InstallerCallbackGate gate = new InstallerCallbackGate(sessionId, UUID.randomUUID().toString());
            mInstallerGate = gate;
            SharedPreferences ledger = getSharedPreferences(INSTALL_LEDGER, MODE_PRIVATE);
            if (ledger.getInt("session", -1) >= 0 || !ledger.edit().putInt("session", sessionId)
                    .putString("token", gate.token).commit()) {
                throw new IllegalStateException("Previous installation is pending");
            }
            mInstallerSessionCallback = new PackageInstaller.SessionCallback() {
                @Override public void onCreated(int id) { }
                @Override public void onBadgingChanged(int id) { }
                @Override public void onActiveChanged(int id, boolean active) { }
                @Override public void onProgressChanged(int id, float progress) { }
                @Override public void onFinished(int id, boolean success) {
                    if (id == gate.sessionId && mInstallerGate == gate && !gate.cancelled && !isDestroyed()) {
                        appInstallFinished(success ? RESULT_OK : RESULT_CANCELED);
                    }
                }
            };
            installer.registerSessionCallback(mInstallerSessionCallback, new Handler(Looper.getMainLooper()));
            Intent result = installerResultIntent(sessionId, gate.token);
            mInstallerResult = PendingIntent.getActivity(this, sessionId, result,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
            PendingIntent statusReceiver = mInstallerResult;
            Toast.makeText(this, R.string.app_installing, Toast.LENGTH_SHORT).show();
            mInstallThread = new Thread(() -> {
                try (PackageInstaller.Session opened = session) {
                    ArrayList<Uri> uris = new ArrayList<>();
                    uris.add(baseUri);
                    if (splitApks != null) {
                        for (String path : splitApks) {
                            if (path == null || path.isEmpty()) throw new IOException("Split APK unavailable");
                            uris.add(Uri.fromFile(new File(path)));
                        }
                    }
                    for (int i = 0; i < uris.size(); i++) {
                        requireInstallActive(gate);
                        try (InputStream input = getContentResolver().openInputStream(uris.get(i))) {
                            if (input == null) throw new IOException("APK unavailable");
                            try (OutputStream output = opened.openWrite(i == 0 ? "base.apk" : "split-" + i + ".apk", 0, -1)) {
                                copyApk(input, output, gate);
                                opened.fsync(output);
                            }
                        }
                    }
                    requireInstallActive(gate);
                    if (mIsProfileOwner) ProtectionPolicy.requireConfirmed(this);
                    opened.setStagingProgress(0.1f);
                    opened.commit(statusReceiver.getIntentSender());
                } catch (IOException | RuntimeException e) {
                    gate.cancel();
                    try { installer.abandonSession(sessionId); } catch (RuntimeException ignored) { }
                    runOnUiThread(() -> {
                        if (mInstallerGate == gate) appInstallFinished(RESULT_CANCELED);
                    });
                }
            }, "Pocket APK install");
            mInstallThread.start();
        } catch (RuntimeException e) {
            try { session.abandon(); } catch (RuntimeException ignored) { }
            session.close();
            throw e;
        }
    }

    private void actionUninstallPackage() {
        if (!mIsProfileOwner) { appInstallFinished(RESULT_CANCELED); return; }
        // Activity results are delivered by Android directly, without an exported status-intent endpoint.
        Uri uri = Uri.fromParts("package", getIntent().getStringExtra("package"), null);
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            mAwaitingUninstallResult = true;
            startActivityForResult(intent, REQUEST_INSTALL_PACKAGE);
        } catch (RuntimeException e) {
            mAwaitingUninstallResult = false;
            appInstallFinished(RESULT_CANCELED);
        }
    }

    private void appInstallFinished(int resultCode) {
        if (mCompletionSent) return;
        cleanupInstall(resultCode != RESULT_OK);
        mCompletionSent = true;
        notifyInstallCallback(mOperationCallback, resultCode);
        finish();
    }

    private static void requireInstallActive(InstallerCallbackGate gate) throws IOException {
        if (gate.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Installation cancelled");
    }

    private static void copyApk(InputStream input, OutputStream output, InstallerCallbackGate gate) throws IOException {
        byte[] buffer = new byte[65536];
        while (true) {
            requireInstallActive(gate);
            int count = input.read(buffer);
            if (count == -1) return;
            requireInstallActive(gate);
            output.write(buffer, 0, count);
        }
    }

    private Intent installerResultIntent(int sessionId, String token) {
        return new Intent(this, DummyActivity.class).setAction(PACKAGEINSTALLER_CALLBACK)
                .setData(Uri.parse("pocket-install://session/" + sessionId + "/" + token))
                .putExtra("_pocket_install_token", token).putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
    }

    private boolean abandonRecordedInstall() {
        SharedPreferences ledger = getSharedPreferences(INSTALL_LEDGER, MODE_PRIVATE);
        int sessionId = ledger.getInt("session", -1);
        String token = ledger.getString("token", "");
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        // Dummy is this app's only session owner; this also covers death between createSession and ledger commit.
        List<PackageInstaller.SessionInfo> sessions = installer.getMySessions();
        boolean recovered = sessionId >= 0 || !sessions.isEmpty();
        for (PackageInstaller.SessionInfo session : sessions) {
            try { installer.abandonSession(session.getSessionId()); }
            catch (RuntimeException e) {
                if (installer.getSessionInfo(session.getSessionId()) != null) throw e;
            }
        }
        if (sessionId >= 0) {
            PendingIntent result = PendingIntent.getActivity(this, sessionId, installerResultIntent(sessionId, token),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE);
            if (result != null) result.cancel();
        }
        if (!ledger.edit().clear().commit()) throw new IllegalStateException("Installation cleanup unavailable");
        if (recovered) FileProviderProxy.clearForwardProxy();
        return recovered;
    }

    private void cleanupInstall(boolean cancel) {
        InstallerCallbackGate gate = mInstallerGate;
        mInstallerGate = null;
        boolean abandoned = !cancel;
        if (gate != null) {
            gate.cancel();
            if (cancel) {
                if (mInstallThread != null) mInstallThread.interrupt();
                try {
                    getPackageManager().getPackageInstaller().abandonSession(gate.sessionId);
                    abandoned = true;
                } catch (RuntimeException ignored) {
                    // Keep the private ledger so the next creation retries cleanup.
                }
            }
            SharedPreferences ledger = getSharedPreferences(INSTALL_LEDGER, MODE_PRIVATE);
            if (abandoned && gate.sessionId == ledger.getInt("session", -1)
                    && gate.token.equals(ledger.getString("token", null))) ledger.edit().clear().commit();
        }
        mInstallThread = null;
        if (mInstallerResult != null) mInstallerResult.cancel();
        mInstallerResult = null;
        if (mInstallerSessionCallback != null) {
            try { getPackageManager().getPackageInstaller().unregisterSessionCallback(mInstallerSessionCallback); }
            catch (RuntimeException ignored) { }
            mInstallerSessionCallback = null;
        }
        if (gate != null || INSTALL_PACKAGE.equals(getIntent().getAction())) FileProviderProxy.clearForwardProxy();
    }

    private static void notifyInstallCallback(Bundle extra, int resultCode) {
        if (extra != null) {
            IAppInstallCallback callback = IAppInstallCallback.Stub.asInterface(extra.getBinder("callback"));
            if (callback != null) {
                try { callback.callback(resultCode); } catch (RemoteException | RuntimeException ignored) { }
            }
        }
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putBoolean(STATE_BUSY, mBusy);
        if (mBusy && mOperationCallback != null) state.putBundle(STATE_CALLBACK, mOperationCallback);
        super.onSaveInstanceState(state);
    }

    @Override public void finish() {
        mBusy = false;
        super.finish();
    }

    @Override protected void onDestroy() {
        cleanupInstall(true);
        if (isFinishing() && !mCompletionSent && mOperationCallback != null) {
            mCompletionSent = true;
            notifyInstallCallback(mOperationCallback, RESULT_CANCELED);
        }
        mBusy = false;
        super.onDestroy();
    }

    private void actionUnfreezeAndLaunch() {
        // Unfreeze and launch an app
        // (actually this also works if the app is not frozen at all)
        // For now we only support apps in Work profile,
        // so we just check if we are profile owner here
        if (!mIsProfileOwner) {
            // Forward it to work profile
            Intent intent = new Intent(UNFREEZE_AND_LAUNCH);
            String packageName = getIntent().getStringExtra("packageName");
            intent.putExtra("packageName", packageName);
            long serial = getIntent().getLongExtra("profileSerial", -1);
            if (serial < 0) { finish(); return; }
            intent.putExtra("profileSerial", serial);
            if (getIntent().hasExtra("linkedPackages")) {
                String[] packages = getIntent().getStringArrayExtra("linkedPackages");
                intent.putExtra("linkedPackages", packages);
            }
            try {
                Utility.transferIntentToProfile(this, intent);
                startActivity(intent);
            } catch (RuntimeException e) {
                Toast.makeText(this, "Рабочий профиль недоступен. Личная копия не запускается.", Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }

        long serial = getSystemService(UserManager.class).getSerialNumberForUser(Process.myUserHandle());
        if (serial < 0 || serial != getIntent().getLongExtra("profileSerial", -1)) {
            Toast.makeText(this, "Ярлык относится к другому рабочему профилю. Пересоздай его в Pocket.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            ProtectionPolicy.requireConfirmed(this);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Сначала настрой защиту рабочего профиля в Pocket.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String packageName = getIntent().getStringExtra("packageName");
        ArrayList<String> packages = new ArrayList<>();
        packages.add(packageName);
        String[] linked = getIntent().getStringArrayExtra("linkedPackages");
        if (linked != null) packages.addAll(Arrays.asList(linked));
        try {
            for (String name : packages) {
                android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(name,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
                if ((info.flags & android.content.pm.ApplicationInfo.FLAG_INSTALLED) == 0
                        || !android.os.UserHandle.getUserHandleForUid(info.uid).equals(Process.myUserHandle())) {
                    throw new IllegalArgumentException("Рабочая копия отсутствует");
                }
            }
            for (String name : packages) {
                mPolicyManager.setApplicationHidden(new ComponentName(this, ShelterDeviceAdminReceiver.class), name, false);
            }
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null || launchIntent.getComponent() == null
                    || !packageName.equals(launchIntent.getComponent().getPackageName())) {
                throw new IllegalArgumentException("Рабочая копия не запускается");
            }
            startActivity(launchIntent);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Toast.makeText(this, "Рабочая копия недоступна. Открой Pocket и пересоздай ярлык.", Toast.LENGTH_LONG).show();
        }

        finish();
    }

    private void actionSynchronizePreference() {
        String name = getIntent().getStringExtra("name");
        if (getIntent().hasExtra("boolean")) {
            LocalStorageManager.getInstance()
                    .setBoolean(name, getIntent().getBooleanExtra("boolean", false));
        } else if (getIntent().hasExtra("int")) {
            LocalStorageManager.getInstance()
                    .setInt(name, getIntent().getIntExtra("int", Integer.MIN_VALUE));
        }
        // TODO: Cases for other types
        if (mIsProfileOwner) {
            // Refresh profile policies because
            // settings may have been changed
            Utility.enforceWorkProfilePolicies(this);
        }
        finish();
    }
}
