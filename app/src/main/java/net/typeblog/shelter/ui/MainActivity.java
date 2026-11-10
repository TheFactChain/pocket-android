package net.typeblog.shelter.ui;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.typeblog.shelter.R;
import net.typeblog.shelter.ShelterApplication;
import net.typeblog.shelter.services.IAppInstallCallback;
import net.typeblog.shelter.services.IShelterService;
import net.typeblog.shelter.services.IStartActivityProxy;
import net.typeblog.shelter.services.KillerService;
import net.typeblog.shelter.services.ShelterService;
import net.typeblog.shelter.util.AuthenticationUtility;
import net.typeblog.shelter.util.LocalStorageManager;
import net.typeblog.shelter.util.UriForwardProxy;
import net.typeblog.shelter.util.Utility;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Host of the Pocket Windows style pages: links both profiles, keeps the state fresh and owns navigation. */
public class MainActivity extends AppCompatActivity {
    private final ActivityResultLauncher<Void> mStartSetup =
            registerForActivityResult(new SetupWizardActivity.SetupWizardContract(), this::setupWizardCb);
    private final ActivityResultLauncher<Void> mResumeSetup =
            registerForActivityResult(new SetupWizardActivity.ResumeSetupContract(), this::setupWizardCb);
    private final ActivityResultLauncher<Void> mSelectApk =
            registerForActivityResult(
                    new Utility.ActivityResultContractInputWrapper<>(
                            new ActivityResultContracts.OpenDocument(),
                            new String[]{"application/vnd.android.package-archive"}),
                    this::onApkSelected);
    private final ActivityResultLauncher<Intent> mTryStartWorkService =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::tryStartWorkServiceCb);
    private final ActivityResultLauncher<Intent> mBindWorkService =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::bindWorkServiceCb);
    private final ActivityResultLauncher<Intent> mRefreshWorkProfile = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                pocketPairing = false;
                authenticateWorkProfile();
            });
    private final ActivityResultLauncher<Intent> mAuthenticateWork = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                pocketPairing = false;
                if (result.getResultCode() == RESULT_OK) bindServices();
                else setLinkProblem("Не удалось связать рабочий профиль. Включи его и открой Pocket снова.");
            });
    // Document pickers are registered here because they must exist before onStart; pages receive the result.
    final ActivityResultLauncher<String[]> openConnectionFile = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) connections().readFile(uri); });
    final ActivityResultLauncher<String> createExportFile = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> connections().writeExport(uri));
    final ActivityResultLauncher<Intent> scanQr = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String text = result.getData().getStringExtra(ConnectionScannerActivity.EXTRA_QR_TEXT);
                    if (text != null) connections().importText(text, null);
                }
            });
    final ActivityResultLauncher<Intent> installUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> { });

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    private boolean visible, refreshing, pocketPairing, pocketBootstrapTried;
    private final Runnable poll = this::refreshState;
    private final Runnable hideToast = this::hideToast;
    private final android.content.BroadcastReceiver profileAvailable = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (mServiceWork == null && mStorage != null && mStorage.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
                main.postDelayed(MainActivity.this::authenticateWorkProfile, 1500);
            }
        }
    };
    private LocalStorageManager mStorage;
    private boolean mRestarting;

    IShelterService mServiceMain, mServiceWork;
    /** Last state from the work profile VPN process; null until linked. */
    JSONObject state;
    JSONArray connectionList = new JSONArray();
    String linkProblem = "Связываем рабочий профиль…";
    int copiesCount = -1;
    net.typeblog.shelter.update.AppUpdater.Manifest updateOffer;

    private FrameLayout host;
    private final List<Page> stack = new ArrayList<>();
    private HomePage home;
    private ConnectionsPage proxy;
    /** Sections open as sheets over the home screen. */
    final List<Page> sheets = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.pocket_main_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        host = findViewById(R.id.page_host);
        findViewById(R.id.toast_close).setOnClickListener(v -> hideToast());
        home = new HomePage(this);
        push(home, false);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (stack.size() > 1) back();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
        mStorage = LocalStorageManager.getInstance();
        // Link again as soon as work apps are switched back on.
        androidx.core.content.ContextCompat.registerReceiver(this, profileAvailable,
                new android.content.IntentFilter(Intent.ACTION_MANAGED_PROFILE_AVAILABLE),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        if (getSystemService(DevicePolicyManager.class).isProfileOwnerApp(getPackageName())) {
            // We are in our own profile: the interface lives in the personal profile only.
            finish();
        } else {
            init();
        }
    }

    // ---- Navigation ----

    void open(Page page) { page.showAsSheet(); }

    private void push(Page page, boolean animate) {
        Page previous = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        stack.add(page);
        View view = page.view();
        if (view.getParent() == null) host.addView(view);
        page.onShow();
        if (previous != null) {
            View old = previous.view();
            if (animate) {
                // Incoming slides in from the right, outgoing drifts left (MainWindow.xaml.cs page push).
                view.setTranslationX(dp(90)); view.setAlpha(0f);
                view.animate().translationX(0).alpha(1f).setDuration(340).setInterpolator(new DecelerateInterpolator(2.5f)).start();
                old.animate().translationX(-dp(40)).alpha(0f).setDuration(200)
                        .withEndAction(() -> old.setVisibility(View.GONE)).start();
            } else old.setVisibility(View.GONE);
        }
    }

    void back() {
        if (stack.size() < 2) return;
        Page top = stack.remove(stack.size() - 1);
        Page under = stack.get(stack.size() - 1);
        View old = top.view(), view = under.view();
        view.setVisibility(View.VISIBLE);
        view.animate().translationX(0).alpha(1f).setDuration(260).start();
        old.animate().translationX(dp(90)).alpha(0f).setDuration(220).withEndAction(() -> host.removeView(old)).start();
        top.onHide();
        under.onShow();
    }

    void backToHome() { while (stack.size() > 1) back(); }

    Page top() { return stack.get(stack.size() - 1); }

    ConnectionsPage connections() {
        if (proxy == null) proxy = new ConnectionsPage(this);
        return proxy;
    }

    void openConnections() { connections().showAsSheet(); }

    // ---- Toast with the current operation (MainWindow.xaml OperationBar) ----

    void toast(String text) { toast(text, false); }

    void toast(String text, boolean busy) {
        View bar = findViewById(R.id.toast);
        ((TextView) findViewById(R.id.toast_text)).setText(text);
        findViewById(R.id.toast_busy).setVisibility(busy ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideToast);
        if (bar.getVisibility() != View.VISIBLE) {
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f); bar.setTranslationY(dp(24));
            bar.animate().alpha(1f).translationY(0).setDuration(240).start();
        }
        if (!busy) main.postDelayed(hideToast, 6000);
    }

    void hideToast() {
        View bar = findViewById(R.id.toast);
        main.removeCallbacks(hideToast);
        bar.animate().alpha(0f).translationY(dp(24)).setDuration(200).withEndAction(() -> bar.setVisibility(View.GONE)).start();
    }

    // ---- Shared helpers ----

    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    int color(int res) { return androidx.core.content.ContextCompat.getColor(this, res); }

    void openUrl(String url) {
        if (url == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException e) { toast("Не удалось открыть ссылку. Установи Telegram или браузер."); }
    }

    interface Remote<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }

    /** Runs a call to the work profile off the main thread; failures become one readable toast. */
    <T> void remote(Remote<T> call, Done<T> done, String failure) {
        if (mServiceWork == null) { toast(linkProblem); authenticateWorkProfile(); return; }
        worker.execute(() -> {
            try {
                T value = call.run();
                post(() -> { if (done != null) done.accept(value); });
            } catch (Exception e) {
                String message = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : failure;
                post(() -> toast(message));
            }
        });
    }

    void post(Runnable action) {
        if (!isDestroyed()) main.post(() -> { if (!isDestroyed()) action.run(); });
    }

    boolean protectionConfirmed() { return state != null && "CONFIRMED".equals(state.optString("policyState")); }

    // ---- State ----

    void refreshState() {
        main.removeCallbacks(poll);
        if (!visible || mServiceWork == null || refreshing || worker.isShutdown()) return;
        refreshing = true;
        worker.execute(() -> {
            JSONObject value = null;
            JSONArray list = null;
            try {
                value = new JSONObject(mServiceWork.getPocketState());
                list = new JSONArray(mServiceWork.listConnections());
            } catch (Exception ignored) { }
            final JSONObject fresh = value;
            final JSONArray fetched = list;
            post(() -> {
                refreshing = false;
                if (fresh == null) setLinkProblem("Профиль недоступен. Включи рабочий профиль и открой Pocket снова.");
                else {
                    state = fresh;
                    if (fetched != null) connectionList = fetched;
                    linkProblem = null;
                    notifyState();
                }
                if (visible) main.postDelayed(poll, 3000);
            });
        });
    }

    void setLinkProblem(String message) {
        linkProblem = message;
        state = null;
        notifyState();
    }

    void notifyState() {
        for (Page page : stack) page.onState();
        for (Page page : new ArrayList<>(sheets)) page.onState();
    }

    // ---- Linking the profiles (kept from the Shelter-based flow) ----

    private void init() {
        if (mStorage.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) && !Utility.isWorkProfileAvailable(this)) {
            mResumeSetup.launch(null);
        } else if (!mStorage.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            mStartSetup.launch(null);
        } else {
            authenticateWorkProfile();
        }
    }

    private void setupWizardCb(Boolean result) {
        if (result) init();
        else finish();
    }

    static final String WORK_PAUSED = "Рабочие приложения выключены. Включи их, и Pocket свяжется сам.";

    /** Starting Pocket inside a paused profile makes Android ask «Включить рабочие приложения?». */
    void turnOnWorkApps() {
        android.os.UserHandle paused = pausedProfile();
        if (paused == null) { authenticateWorkProfile(); return; }
        try {
            Intent wake = new Intent(DummyActivity.TRY_START_SERVICE);
            Utility.transferIntentToProfileUnsigned(this, wake);
            startActivity(wake);
            // Some firmware (MIUI) drops the system «turn on work apps» prompt: tell the user where the switch is.
            main.postDelayed(() -> {
                if (pausedProfile() != null) toast("Телефон не показал запрос. Включи рабочий профиль в шторке или настройках телефона, Pocket свяжется сам.");
            }, 2500);
        } catch (RuntimeException e) {
            android.util.Log.w("Pocket", "Work apps prompt failed: " + e.getClass().getSimpleName());
            toast("Включи рабочие приложения в шторке уведомлений, затем вернись в Pocket.");
        }
    }

    /** The work profile when the user switched work apps off, otherwise null. */
    private android.os.UserHandle pausedProfile() {
        android.os.UserManager users = getSystemService(android.os.UserManager.class);
        for (android.os.UserHandle profile : users.getUserProfiles()) {
            if (!profile.equals(android.os.Process.myUserHandle()) && users.isQuietModeEnabled(profile)) return profile;
        }
        return null;
    }

    void authenticateWorkProfile() {
        if (pocketPairing) return;
        android.os.UserHandle paused = pausedProfile();
        if (paused != null) {
            setLinkProblem(WORK_PAUSED);
            return;
        }
        pocketPairing = true;
        setLinkProblem("Связываем рабочий профиль…");
        Intent intent = new Intent(DummyActivity.AUTHENTICATE);
        AuthenticationUtility.putPairingRequest(intent);
        try {
            Utility.transferIntentToProfileUnsigned(this, intent);
            mAuthenticateWork.launch(intent);
        } catch (RuntimeException e) {
            pocketPairing = false;
            if (!pocketBootstrapTried) {
                pocketBootstrapTried = true;
                try {
                    Intent refresh = new Intent(DummyActivity.TRY_START_SERVICE);
                    Utility.transferIntentToProfileUnsigned(this, refresh);
                    pocketPairing = true;
                    mRefreshWorkProfile.launch(refresh);
                    return;
                } catch (RuntimeException unavailable) { pocketPairing = false; }
            }
            setLinkProblem("Включи рабочий профиль Pocket и открой приложение снова.");
        }
    }

    private void preparePocketConnection() {
        worker.execute(() -> {
            // Debug test bootstrap from tools/Install-TestProfile.ps1; the file is removed once applied.
            java.io.File seed = new java.io.File(getFilesDir(), "pending-test-profile.json");
            // Release never switches the user's connection from a leftover test file.
            if (net.typeblog.shelter.BuildConfig.DEBUG && seed.isFile()) {
                try {
                    if (seed.length() > 1048576) throw new IllegalArgumentException();
                    String json = new String(java.nio.file.Files.readAllBytes(seed.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    String id = mServiceWork.saveConnection(json);
                    mServiceWork.activateConnection(id);
                    if (!seed.delete()) throw new java.io.IOException();
                } catch (Exception e) {
                    post(() -> toast("Не удалось применить тестовое подключение. Открой «Подключение»."));
                }
            }
            post(this::refreshState);
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mServiceWork != null && new java.io.File(getFilesDir(), "pending-test-profile.json").isFile()) preparePocketConnection();
    }

    private void bindServices() {
        ((ShelterApplication) getApplication()).bindShelterService(new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceMain = IShelterService.Stub.asInterface(service);
                tryStartWorkService();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        }, false);
    }

    private void tryStartWorkService() {
        // A dummy intent tells whether work mode is enabled and something CAN start in that profile.
        Intent intent = new Intent(DummyActivity.TRY_START_SERVICE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            Utility.transferIntentToProfile(this, intent);
        } catch (IllegalStateException e) {
            // The work profile does not exist at all.
            mStorage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false);
            Toast.makeText(this, getString(R.string.work_profile_not_found), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mTryStartWorkService.launch(intent);
    }

    private void tryStartWorkServiceCb(ActivityResult result) {
        if (result.getResultCode() == RESULT_OK) bindWorkService();
        else {
            Toast.makeText(this, getString(R.string.work_mode_disabled), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void bindWorkService() {
        Intent intent = new Intent(DummyActivity.START_SERVICE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Utility.transferIntentToProfile(this, intent);
        mBindWorkService.launch(intent);
    }

    private void bindWorkServiceCb(ActivityResult result) {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            Bundle extra = result.getData().getBundleExtra("extra");
            IBinder binder = extra.getBinder("service");
            mServiceWork = IShelterService.Stub.asInterface(binder);
            String challenge = java.util.UUID.randomUUID().toString();
            try {
                if (!AuthenticationUtility.verifyAttestation(challenge, "work", mServiceWork.attest(challenge))) throw new SecurityException();
            } catch (Exception e) {
                mServiceWork = null;
                setLinkProblem("Рабочий профиль не подтвердил свою подлинность");
                return;
            }
            registerStartActivityProxies();
            startKiller();
            preparePocketConnection();
            UpdatesPage.checkInBackground(this);
        }
    }

    private void startKiller() {
        // The sticky KillerService stops ShelterService when the task is removed from recents.
        Intent intent = new Intent(this, KillerService.class);
        Bundle bundle = new Bundle();
        bundle.putBinder("main", mServiceMain.asBinder());
        bundle.putBinder("work", mServiceWork.asBinder());
        intent.putExtra("extra", bundle);
        startService(intent);
    }

    boolean servicesAlive() {
        try { mServiceMain.ping(); } catch (Exception e) { return false; }
        try { mServiceWork.ping(); } catch (Exception e) { return false; }
        return true;
    }

    private void registerStartActivityProxies() {
        try {
            mServiceMain.setStartActivityProxy(new IStartActivityProxy.Stub() {
                @Override public void startActivity(Intent intent) {
                    AuthenticationUtility.requireTrustedBinderCaller();
                    MainActivity.this.startActivity(intent);
                }
            });
            mServiceWork.setStartActivityProxy(new IStartActivityProxy.Stub() {
                @Override public void startActivity(Intent intent) {
                    AuthenticationUtility.requireTrustedBinderCaller();
                    // The full intent may not resolve DummyActivity inside the profile; resolve by action only.
                    Intent dummyIntent = new Intent(intent.getAction());
                    Utility.transferIntentToProfileUnsigned(MainActivity.this, dummyIntent);
                    intent.setComponent(dummyIntent.getComponent());
                    MainActivity.this.startActivity(intent);
                }
            });
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    void selectApk() { mSelectApk.launch(null); }

    private void onApkSelected(Uri uri) {
        if (uri == null) return;
        if (mServiceWork == null) { toast("Сначала включи рабочий профиль Pocket"); return; }
        UriForwardProxy proxy = new UriForwardProxy(getApplicationContext(), uri);
        toast("Устанавливаем APK в Pocket…", true);
        try {
            mServiceWork.installApk(proxy, new IAppInstallCallback.Stub() {
                @Override public void callback(int result) {
                    AuthenticationUtility.requireTrustedBinderCaller();
                    post(() -> {
                        if (result == RESULT_OK) { toast("Готово: приложение установлено в Pocket."); notifyAppsChanged(); }
                        else toast(result == ShelterService.RESULT_PROTECTION_REQUIRED
                                ? "Добавление недоступно: защита профиля не подтверждена. Открой «Проверка»."
                                : "Приложение не установлено. Проверь подтверждение на экране и повтори.");
                    });
                }
            });
        } catch (RemoteException | RuntimeException e) {
            toast("Не удалось передать APK в рабочий профиль. Открой Pocket снова.");
        }
    }

    void notifyAppsChanged() {
        home.apps.reload();
    }

    // ---- Lifecycle ----

    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        refreshState();
        if (mServiceMain != null && mServiceWork != null && !servicesAlive()) {
            // Kill the old services first so the restarted activity does not reuse them.
            doOnDestroy();
            mRestarting = true;
            Intent intent = getIntent();
            finish();
            startActivity(intent);
        }
    }

    @Override protected void onDestroy() {
        visible = false;
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        try { unregisterReceiver(profileAvailable); } catch (RuntimeException ignored) { }
        super.onDestroy();
        if (!mRestarting && !isChangingConfigurations()) doOnDestroy();
    }

    private void doOnDestroy() {
        stopService(new Intent(this, KillerService.class));
        Utility.killShelterServices(mServiceMain, mServiceWork);
    }
}
