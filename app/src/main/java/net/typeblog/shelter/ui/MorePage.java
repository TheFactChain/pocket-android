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
    private SwitchMaterial directRu, providerRules, ipControl;
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

        RowView provider = row(routing, R.drawable.ic_route, R.color.teal, "Правила провайдера", "Из подписки: что открыть напрямую, что закрыть");
        providerRules = new SwitchMaterial(a);
        providerRules.setContentDescription("Правила провайдера");
        provider.setEnd(providerRules);
        provider.setOnClickListener(v -> providerRules.toggle());
        providerRules.setOnCheckedChangeListener((b, on) -> {
            if (applying) return;
            if (a.state == null) { applying = true; providerRules.setChecked(!on); applying = false; a.toast(a.linkProblem); return; }
            if (a.state.optBoolean("providerRules", false) == on) return;
            a.toast(on ? "Включаем правила провайдера…" : "Выключаем правила провайдера…", true);
            a.remote(() -> { a.mServiceWork.setProviderRules(on); return null; },
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

        LinearLayout transfer = group();
        row(transfer, R.drawable.ic_info, R.color.accent, "Сохранить в файл", "Подключения, подписки, приложения и маршруты под паролем")
                .setOnClickListener(v -> askPassword("Сохранить настройки", true, this::pickSaveFile));
        row(transfer, R.drawable.ic_info, R.color.accent, "Загрузить из файла", "Добавит то, чего ещё нет; текущий сервер не сменится")
                .setOnClickListener(v -> {
                    a.settingsFileResult = uri -> askPassword("Загрузить настройки", false, pw -> importFile(uri, pw));
                    a.openSettingsFile.launch(new String[]{"*/*"});
                });

        LinearLayout help = group();
        row(help, R.drawable.ic_warning, R.color.orange, "Диагностика подключения", "Пошаговая проверка и отчёт")
                .setOnClickListener(v -> new HomePage.DiagnosticsPage(a).showAsSheet());
        row(help, R.drawable.ic_chat, R.color.accent, "Тех. поддержка", "Ответим в Telegram")
                .setOnClickListener(v -> a.openUrl(ShopCatalog.supportUrl()));
        row(help, R.drawable.ic_info, R.color.violet, "О Pocket", "Версия, лицензии")
                .setOnClickListener(v -> a.startActivity(new Intent(a, SettingsActivity.class)));
    }

    private EditText passwordField(String hint) {
        EditText field = new EditText(a);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint(hint);
        field.setContentDescription(hint);
        return field;
    }

    /** Asks for the file password (twice when saving); the caller gets it only if it is valid. */
    private void askPassword(String title, boolean twice, java.util.function.Consumer<char[]> done) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText first = passwordField("Пароль от файла"), second = twice ? passwordField("Пароль ещё раз") : null;
        box.addView(first);
        if (twice) box.addView(second);
        new AlertDialog.Builder(a).setTitle(title)
                .setMessage(twice ? "Не короче 12 символов. Без пароля файл не открыть, восстановить его нельзя." : "Пароль, который ты задавал при сохранении.")
                .setView(box)
                .setPositiveButton(twice ? "Далее" : "Загрузить", (d, w) -> {
                    char[] pw = new char[first.length()];
                    first.getText().getChars(0, pw.length, pw, 0);
                    int min = twice ? 12 : 8; // files saved earlier with 8 characters still open
                    if (pw.length < min) { java.util.Arrays.fill(pw, '\0'); a.toast("Пароль — не короче " + min + " символов."); return; }
                    if (twice && !first.getText().toString().equals(second.getText().toString())) {
                        java.util.Arrays.fill(pw, '\0'); a.toast("Пароли не совпали. Введи их ещё раз."); return;
                    }
                    done.accept(pw);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void pickSaveFile(char[] password) {
        a.settingsFileResult = uri -> a.remote(() -> {
            String file = a.mServiceWork.exportSettings(new String(password));
            try (java.io.OutputStream out = a.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException();
                out.write(file.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Не удалось сохранить файл. Выбери другую папку.");
            } finally { java.util.Arrays.fill(password, '\0'); }
            return null;
        }, x -> a.toast("Настройки сохранены в файл."), "Не удалось сохранить настройки. Открой Pocket снова.");
        a.createSettingsFile.launch("Pocket-настройки.pocket");
    }

    private void importFile(android.net.Uri uri, char[] password) {
        a.remote(() -> {
            String text;
            try (java.io.InputStream in = a.getContentResolver().openInputStream(uri); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                if (in == null) throw new java.io.IOException();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (out.size() + n > 512 * 1024) throw new IllegalArgumentException("Файл слишком большой. Выбери файл настроек Pocket.");
                    out.write(buffer, 0, n);
                }
                text = out.toString("UTF-8");
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Файл не прочитан. Выбери его ещё раз.");
            }
            try { return new org.json.JSONObject(a.mServiceWork.importSettings(text, new String(password))); }
            finally { java.util.Arrays.fill(password, '\0'); }
        }, r -> { a.toast("Добавлено: " + r.optInt("connections") + " подключений, " + r.optInt("subscriptions") + " подписок"); a.refreshState(); },
                "Не удалось загрузить настройки. Открой Pocket снова.");
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
        providerRules.setChecked(a.state.optBoolean("providerRules", false));
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
