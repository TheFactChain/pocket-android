package net.typeblog.shelter.ui;

import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.switchmaterial.SwitchMaterial;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

/** «Настройки» sheet: routing, IP control, support and about, each one row (updates have their own card on the home screen). */
final class MorePage extends Page {
    private SwitchMaterial directRu, ipControl;
    private RowView ipRow, intervalRow;
    private boolean applying;

    MorePage(MainActivity activity) { super(activity); }

    @Override String title() { return "Настройки"; }

    @Override void build() {
        LinearLayout routing = group();
        RowView ru = row(routing, R.drawable.ic_route, R.color.green, "Российские сайты напрямую", "Зарубежные всегда через прокси");
        directRu = new SwitchMaterial(a);
        directRu.setContentDescription("Российские сайты напрямую");
        directRu.setChecked(false);
        ru.setEnd(directRu);
        ru.setOnClickListener(v -> directRu.toggle());
        directRu.setOnCheckedChangeListener((b, on) -> {
            if (applying) return;
            if (a.state == null) { applying = true; directRu.setChecked(!on); applying = false; a.toast(a.linkProblem); return; }
            if (a.state.optBoolean("directRu", false) == on) return;
            a.toast(on ? "Российские сайты пойдут напрямую…" : "Весь интернет копий пойдёт через прокси…", true);
            a.remote(() -> { a.mServiceWork.setDirectRu(on); return null; },
                    x -> { a.toast("Готово, прокси перезапущен."); a.refreshState(); },
                    "Не удалось изменить маршрут. Открой Pocket снова.");
        });

        LinearLayout ip = group();
        ipRow = row(ip, R.drawable.ic_shield, R.color.orange, "Контроль постоянного IP", "Выключен");
        ipControl = new SwitchMaterial(a);
        ipControl.setContentDescription("Контроль постоянного IP");
        ipRow.setEnd(ipControl);
        ipRow.setOnClickListener(v -> {
            // A changed address waits for the user's decision before anything else.
            if (a.state != null && !a.state.optString("ipPending").isEmpty()) confirmIp(); else ipControl.toggle();
        });
        ipControl.setOnCheckedChangeListener((b, on) -> {
            if (applying) return;
            if (a.state == null) { applying = true; ipControl.setChecked(!on); applying = false; a.toast(a.linkProblem); return; }
            applyIp(on, a.state.optInt("ipMinutes", 30));
        });
        intervalRow = row(ip, R.drawable.ic_info, R.color.orange, "Проверять каждые", "30 мин");
        intervalRow.setOnClickListener(v -> askInterval());

        LinearLayout help = group();
        row(help, R.drawable.ic_chat, R.color.accent, "Тех. поддержка", "Ответим в Telegram")
                .setOnClickListener(v -> a.openUrl(ShopCatalog.supportUrl()));
        row(help, R.drawable.ic_info, R.color.violet, "О Pocket", "Версия, лицензии")
                .setOnClickListener(v -> a.startActivity(new Intent(a, SettingsActivity.class)));
    }

    private void applyIp(boolean on, int minutes) {
        a.remote(() -> { a.mServiceWork.setIpControl(on, minutes); return null; },
                x -> { a.toast(on ? "Контроль IP включён: проверка каждые " + minutes + " мин." : "Контроль IP выключен."); a.refreshState(); },
                "Не удалось изменить контроль IP. Открой Pocket снова.");
    }

    private void askInterval() {
        if (a.state == null) { a.toast(a.linkProblem); return; }
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(a.state.optInt("ipMinutes", 30)));
        input.setSelectAllOnFocus(true);
        input.setContentDescription("Интервал проверки в минутах");
        new AlertDialog.Builder(a).setTitle("Интервал проверки IP")
                .setMessage("В минутах, от 1 до 1440. Когда телефон спит, Android может проверить позже.")
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    int minutes;
                    try { minutes = Integer.parseInt(input.getText().toString().trim()); } catch (NumberFormatException e) { minutes = 0; }
                    if (minutes < 1 || minutes > 1440) { a.toast("Укажи от 1 до 1440 минут."); return; }
                    applyIp(a.state.optBoolean("ipControl", false), minutes);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void confirmIp() {
        new AlertDialog.Builder(a).setTitle("Внешний IP изменился")
                .setMessage("Было: " + a.state.optString("ipConfirmed", "неизвестно") + "\nСейчас: " + a.state.optString("ipPending")
                        + "\n\nПока ты не подтвердишь, интернет приложений в Pocket закрыт. Подтвердить новый адрес?")
                .setPositiveButton("Подтвердить", (d, w) -> a.remote(() -> { a.mServiceWork.confirmIp(); return null; },
                        x -> { a.toast("Новый IP подтверждён, подключаемся."); a.refreshState(); }, "Не удалось подтвердить IP."))
                .setNegativeButton("Не сейчас", null).show();
    }

    @Override void onState() {
        view();
        if (a.state == null) return;
        applying = true;
        directRu.setChecked(a.state.optBoolean("directRu", false));
        boolean on = a.state.optBoolean("ipControl", false);
        ipControl.setChecked(on);
        applying = false;
        String status = a.state.optString("ipStatus"), observed = a.state.optString("ipObserved");
        String caption = !on ? "Выключен"
                : !a.state.optString("ipPending").isEmpty() ? "IP изменился, интернет закрыт. Нажми, чтобы подтвердить"
                : status.equals("OK") ? "Подтверждён: " + observed
                : status.equals("UNCONFIRMED") ? "Не подтверждён: сервис проверки недоступен, повторим" : "Проверяем…";
        ipRow.setCaption(caption);
        intervalRow.setCaption(a.state.optInt("ipMinutes", 30) + " мин");
    }
}
