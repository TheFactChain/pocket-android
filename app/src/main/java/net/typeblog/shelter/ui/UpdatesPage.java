package net.typeblog.shelter.ui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.update.AppUpdater;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** «Обновления»: manifest check, verified download (SHA-256, size) and install over the same signature. */
final class UpdatesPage extends Page {
    private static final String ACTION_STATUS = BuildConfig.APPLICATION_ID + ".action.UPDATE_STATUS";
    private RowView version;
    private TextView status, action;
    private ProgressBar progress;
    private AtomicBoolean cancel;
    private BroadcastReceiver receiver;

    UpdatesPage(MainActivity activity) { super(activity); }

    @Override String title() { return "Обновления"; }

    private static String feed() { return BuildConfig.DEBUG ? AppUpdater.DEBUG_FEED : AppUpdater.FEED; }

    /** Quiet check after linking: only sets the «Доступно» pill on the home row. */
    static void checkInBackground(MainActivity a) {
        a.worker.execute(() -> {
            try {
                AppUpdater.Manifest m = AppUpdater.fetchManifest(feed(), BuildConfig.DEBUG);
                if (AppUpdater.isNewer(m, BuildConfig.VERSION_CODE)) a.post(() -> { a.updateOffer = m; a.notifyState(); });
            } catch (Exception ignored) { /* offline or no feed yet: nothing to offer */ }
        });
    }

    @Override void build() {
        LinearLayout group = group();
        version = row(group, R.drawable.ic_download, R.color.green, "Pocket " + BuildConfig.VERSION_NAME, "Установлена эта версия");
        status = caption(content, "");
        progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.bottomMargin = a.dp(14);
        content.addView(progress, pp);
        action = pill(content, R.style.Pill, R.drawable.bg_pill_accent, "Проверить", v -> onAction());
        caption(content, "Обновление принимается, только если подписано тем же ключом, что установленный Pocket. Копии и подключения сохраняются.");
        if (a.updateOffer != null) offer(a.updateOffer); else check();
    }

    private void onAction() {
        if (cancel != null) { cancel.set(true); return; }
        if (a.updateOffer != null) download(a.updateOffer); else check();
    }

    private void check() {
        status.setText("Проверяем…");
        action.setEnabled(false);
        a.worker.execute(() -> {
            try {
                AppUpdater.Manifest m = AppUpdater.fetchManifest(feed(), BuildConfig.DEBUG);
                a.post(() -> {
                    action.setEnabled(true);
                    if (AppUpdater.isNewer(m, BuildConfig.VERSION_CODE)) { a.updateOffer = m; offer(m); a.notifyState(); }
                    else { status.setText("Новых версий нет."); action.setText("Проверить"); }
                });
            } catch (Exception e) {
                a.post(() -> { action.setEnabled(true); status.setText("Источник обновлений недоступен. Проверь позже."); action.setText("Проверить"); });
            }
        });
    }

    private void offer(AppUpdater.Manifest m) {
        version.setPill("Доступно " + m.versionName, a.color(R.color.green_soft), a.color(R.color.green_ink));
        status.setText(m.notes.isEmpty() ? "Доступна версия " + m.versionName + "." : m.notes);
        action.setText("Обновить");
    }

    private void download(AppUpdater.Manifest m) {
        AtomicBoolean cancelled = new AtomicBoolean();
        cancel = cancelled;
        action.setText("Отменить");
        progress.setProgress(0);
        progress.setVisibility(android.view.View.VISIBLE);
        status.setText("Скачиваем " + m.versionName + "…");
        File dir = new File(a.getCacheDir(), "update");
        a.worker.execute(() -> {
            try {
                File apk = AppUpdater.download(m, dir, (done, total) -> a.post(() ->
                        progress.setProgress(total <= 0 ? 0 : (int) (done * 1000 / total))), cancelled, BuildConfig.DEBUG);
                a.post(() -> install(apk));
            } catch (Exception e) {
                a.post(() -> finish(cancelled.get() ? "Загрузка отменена." : e.getMessage() == null ? "Не удалось скачать обновление." : e.getMessage()));
            }
        });
    }

    private void install(File apk) {
        status.setText("Устанавливаем…");
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            try { a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                            catch (RuntimeException ignored) { }
                        }
                        finish("Подтверждение установки не открылось.");
                    } else if (code == PackageInstaller.STATUS_SUCCESS) {
                        finish("Обновление установлено.");
                    } else {
                        finish("Обновление не установлено. Подпись должна совпадать с установленным Pocket.");
                    }
                }
            };
            ContextCompat.registerReceiver(a, receiver, new IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        a.worker.execute(() -> {
            try {
                Intent intent = new Intent(ACTION_STATUS).setPackage(a.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(a, 7, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                AppUpdater.install(a, apk, pending.getIntentSender());
            } catch (Exception e) {
                a.post(() -> finish("Не удалось начать установку обновления."));
            }
        });
    }

    private void finish(String message) {
        cancel = null;
        unregister();
        progress.setVisibility(android.view.View.GONE);
        status.setText(message);
        action.setText(a.updateOffer != null ? "Обновить" : "Проверить");
    }

    @Override void onHide() {
        if (cancel != null) cancel.set(true);
        // An install in flight keeps its receiver: the confirmation must still open after leaving the page.
    }

    private void unregister() {
        if (receiver != null) { try { a.unregisterReceiver(receiver); } catch (RuntimeException ignored) { } receiver = null; }
    }
}
