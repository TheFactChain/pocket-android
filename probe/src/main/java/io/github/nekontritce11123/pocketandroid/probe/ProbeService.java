package io.github.nekontritce11123.pocketandroid.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

public final class ProbeService extends Service {
    static final String STOP = "io.github.nekontritce11123.pocketandroid.probe.STOP";
    private static final String CHANNEL = "probe_checks";
    private static final int NOTIFICATION = 1;
    // Reuse one worker even if Android recreates the service while old DNS unwinds.
    private static final ProbeRunner RUNNER = new ProbeRunner();
    private final Handler handler = new Handler(Looper.getMainLooper());

    static boolean hasResult() { return RUNNER.hasResult(); }
    static String snapshot() { return RUNNER.snapshot(); }
    static boolean isRunning() { return RUNNER.isRunning(); }
    private static volatile String runSalt;
    /** A stand run with a new salt must replace an older series instead of joining it. */
    static boolean isRunning(String salt) { return RUNNER.isRunning() && java.util.Objects.equals(runSalt, salt); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Проверки Pocket Probe",
                NotificationManager.IMPORTANCE_LOW));
        RUNNER.setListener(() -> handler.post(() -> {
            if (!RUNNER.isRunning()) {
                Log.i("PocketProbe", "uid=" + Process.myUid() + " service stopped");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            RUNNER.stop("Проверки остановлены.");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ProbeService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Pocket Probe: проверка сети")
                .setContentText("Работает в фоне ограниченное время")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .build();
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        try {
            if (intent == null) throw new IllegalArgumentException("Нет параметров проверки.");
            boolean ipOnly = intent.getBooleanExtra("ipOnly", false);
            java.net.URL url = TargetValidation.url(ipOnly ? ProbeChecks.IP_URL : intent.getStringExtra("url"));
            String dns = TargetValidation.host(intent.getStringExtra("resolveHost"));
            String udp = intent.getStringExtra("udpHost");
            int port = intent.getIntExtra("udpPort", 0);
            if (udp != null || port != 0) {
                udp = TargetValidation.host(udp);
                port = TargetValidation.port(port);
            }
            int interval = TargetValidation.interval(intent.getIntExtra("interval", 2));
            ProbeChecks checks = new ProbeChecks(url, dns, udp, port, ipOnly, Process.myUid(),
                    "UID: " + Process.myUid() + "  " + Process.myUserHandle(),
                    message -> Log.i("PocketProbe", message));
            String salt = intent.getStringExtra("salt");
            runSalt = salt;
            if (salt != null) {
                String leak = intent.getStringExtra("leakUrl");
                checks.stand(leak == null ? null : TargetValidation.leakUrl(leak), TargetValidation.salt(salt),
                        line -> appendEvidence(new java.io.File(getFilesDir(), EVIDENCE), line),
                        new java.io.File(getFilesDir(), "test-target.properties"),
                        // A late stop from an older series must not end a newer one started with another salt.
                        () -> handler.post(() -> { if (isRunning(salt)) RUNNER.stop("Стенд завершил прогон."); }));
            }
            int minutes = intent.hasExtra("durationMinutes") ? TargetValidation.minutes(intent.getIntExtra("durationMinutes", 5)) : 5;
            RUNNER.start(checks, intent.getBooleanExtra("repeat", false), interval, minutes * 60000L);
            Log.i("PocketProbe", "uid=" + Process.myUid() + " service start repeat="
                    + intent.getBooleanExtra("repeat", false));
        } catch (IllegalArgumentException e) {
            RUNNER.stop("Ошибка параметров: " + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    static final String EVIDENCE = "checks.jsonl";
    private static final long MAX_EVIDENCE_BYTES = 2 * 1024 * 1024;

    /** Read by the PC stand through run-as; capped so a forgotten series cannot fill storage. */
    static synchronized void appendEvidence(java.io.File file, String line) {
        if (file.length() > MAX_EVIDENCE_BYTES) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            Log.w("PocketProbe", "evidence write failed: " + e.getClass().getSimpleName());
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        RUNNER.setListener(null);
        handler.removeCallbacksAndMessages(null);
        if (RUNNER.isRunning()) RUNNER.stop("Служба остановлена; проверки прекращены.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
