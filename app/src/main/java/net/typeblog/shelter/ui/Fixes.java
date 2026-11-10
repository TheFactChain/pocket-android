package net.typeblog.shelter.ui;

import android.content.Intent;
import android.provider.Settings;

/** The one repair offered next to the status line. */
final class Fixes {
    private Fixes() { }

    static void run(MainActivity a, PocketStatus.Fix fix) {
        switch (fix) {
            case LINK:
                a.turnOnWorkApps();
                break;
            case ADD_CONNECTION:
                a.openConnections();
                a.connections().showAdd();
                break;
            case CHECK:
                a.toast("Проверяем прокси…", true);
                a.remote(() -> { a.mServiceWork.checkConnectionNow(); return null; },
                        v -> a.main.postDelayed(() -> { a.refreshState(); a.toast("Проверка запущена, результат появится в строке статуса."); }, 1500),
                        "Не удалось запустить проверку. Открой Pocket снова.");
                break;
            case PROTECTION:
                String selected = a.state == null ? "" : a.state.optString("selectedId");
                a.toast("Включаем защиту…", true);
                // Re-activating runs ProtectionPolicy.ensure inside the work profile.
                a.remote(() -> {
                    if (!selected.isEmpty()) a.mServiceWork.activateConnection(selected);
                    return null;
                }, v -> a.main.postDelayed(() -> {
                    a.refreshState();
                    a.main.postDelayed(() -> {
                        if (a.protectionConfirmed()) { a.toast("Защита включена."); return; }
                        a.toast("Включи для Pocket «Постоянная VPN» и «Блокировать без VPN» в рабочем профиле.");
                        try { a.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
                        catch (RuntimeException ignored) { }
                    }, 1500);
                }, 1500), "Не удалось включить защиту. Открой Pocket снова.");
                break;
            default:
                break;
        }
    }
}
