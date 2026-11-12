package io.github.nekontritce11123.pocketandroid.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;

import java.net.URL;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            showResult();
            handler.postDelayed(this, 500);
        }
    };
    private TextView results;
    private String lastResult = "Выбери проверку. Запросы идут через обычную сеть текущего профиля.";
    private URL target;
    private String resolveHost, udpHost, leakUrl, salt;
    private int udpPort, interval, durationMinutes = 5;
    private boolean configured;
    private String launchError;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("PocketProbe", "uid=" + Process.myUid() + " resource=" + getString(R.string.split_marker));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding + insets.getSystemWindowInsetLeft(),
                    padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(),
                    padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("Pocket Probe");
        title.setTextSize(24);
        root.addView(title);
        TextView identity = new TextView(this);
        identity.setText("UID: " + Process.myUid() + "   " + Process.myUserHandle()
                + "\nТестовый клиент обычной сети своего профиля.");
        identity.setTextIsSelectable(true);
        root.addView(identity);
        LinearLayout firstRow = new LinearLayout(this);
        firstRow.addView(button("Внешний IP", v -> startChecks(false, true)), buttonLayout());
        firstRow.addView(button("Проверить цель", v -> startChecks(false, false)), buttonLayout());
        root.addView(firstRow);
        LinearLayout secondRow = new LinearLayout(this);
        secondRow.addView(button("Повторять", v -> startChecks(true, false)), buttonLayout());
        secondRow.addView(button("Остановить", v -> {
            startService(new Intent(this, ProbeService.class).setAction(ProbeService.STOP));
        }), buttonLayout());
        root.addView(secondRow);
        results = new TextView(this);
        results.setTextSize(14);
        results.setTextIsSelectable(true);
        results.setText(lastResult);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        configure(getIntent(), savedInstanceState == null);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && savedInstanceState == null) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        return button;
    }

    private LinearLayout.LayoutParams buttonLayout() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        configure(intent, true);
    }

    private void configure(Intent intent, boolean launchChecks) {
        configured = false;
        try {
            java.util.Properties localTarget = TargetValidation.readTarget(new java.io.File(getFilesDir(), "test-target.properties"));
            String url = intent.getStringExtra("url");
            if (url == null) url = localTarget.getProperty("url");
            target = TargetValidation.url(url == null ? ProbeChecks.IP_URL : url);
            String dns = intent.getStringExtra("resolveHost");
            if (dns == null) dns = localTarget.getProperty("resolveHost");
            resolveHost = TargetValidation.host(dns == null ? "example.com" : dns);
            interval = TargetValidation.interval(intent.getIntExtra("interval", Integer.parseInt(localTarget.getProperty("interval", "2"))));
            udpHost = intent.getStringExtra("udpHost");
            if (udpHost == null) udpHost = localTarget.getProperty("udpHost");
            udpPort = intent.getIntExtra("udpPort", Integer.parseInt(localTarget.getProperty("udpPort", "0")));
            if (udpHost != null || intent.hasExtra("udpPort")) {
                udpHost = TargetValidation.host(udpHost);
                udpPort = TargetValidation.port(udpPort);
            }
            leakUrl = localTarget.getProperty("leakUrl");
            salt = localTarget.getProperty("salt");
            durationMinutes = Integer.parseInt(localTarget.getProperty("durationMinutes", "5"));
            configured = true;
            lastResult = "URL: " + target + "\nDNS: " + resolveHost
                    + (udpHost == null ? "\nUDP: не задан" : "\nUDP: " + udpHost + ":" + udpPort)
                    + "\nИнтервал повторов: " + interval + " с"
                    + (salt == null ? "" : "\nРежим стенда: " + durationMinutes + " мин, журнал " + ProbeService.EVIDENCE);
            showResult();
            // The stand cannot pass extras into the work profile, so the private file can request a series on launch.
            boolean repeat = intent.getBooleanExtra("repeat", false)
                    || TargetValidation.bool(localTarget.getProperty("repeat", "false"));
            if (launchChecks && !ProbeService.isRunning(salt) && (repeat || intent.getBooleanExtra("autostart", false))) startChecks(repeat, false);
        } catch (IllegalArgumentException e) {
            stopService(new Intent(this, ProbeService.class));
            lastResult = "Ошибка параметров: " + e.getMessage();
            results.setText(lastResult);
        }
    }

    private void startChecks(boolean repeat, boolean ipOnly) {
        if (!configured) return;
        launchError = null;
        Intent service = new Intent(this, ProbeService.class)
                .putExtra("url", target.toString()).putExtra("resolveHost", resolveHost)
                .putExtra("udpHost", udpHost).putExtra("udpPort", udpPort)
                .putExtra("interval", interval).putExtra("repeat", repeat).putExtra("ipOnly", ipOnly)
                .putExtra("durationMinutes", durationMinutes);
        if (salt != null) service.putExtra("salt", salt).putExtra("leakUrl", leakUrl);
        try {
            startForegroundService(service);
            results.setText(repeat ? "Запуск повторных проверок…" : "Запуск проверки…");
        } catch (IllegalStateException | SecurityException e) {
            launchError = "Не удалось запустить службу: " + e.getClass().getSimpleName();
            results.setText(launchError);
        }
    }

    private void showResult() {
        results.setText(launchError != null ? launchError
                : configured && ProbeService.hasResult() ? ProbeService.snapshot() : lastResult);
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }
}
