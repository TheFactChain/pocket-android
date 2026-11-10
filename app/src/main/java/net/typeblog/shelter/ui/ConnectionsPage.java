package net.typeblog.shelter.ui;

import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;

import net.typeblog.shelter.R;
import net.typeblog.shelter.connections.ConnectionProfile;
import net.typeblog.shelter.connections.ProfileImporter;
import net.typeblog.shelter.shop.ShopCatalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** «Подключения»: just the list and one row to add; adding and editing open in a card (bottom sheet). */
final class ConnectionsPage extends Page {
    private static final String[] TYPES = {"hysteria2", "socks5", "http", "https"};
    private LinearLayout list;
    private TextView stateText, buyPrice;
    private String shownList = "";
    private BottomSheetDialog sheet;
    private TextView addError;
    /** Export waiting for the system file picker. */
    private ConnectionProfile pendingExport;
    private boolean pendingSecrets;

    ConnectionsPage(MainActivity activity) { super(activity); }

    @Override String title() { return "Прокси"; }

    @Override boolean secure() { return true; }

    @Override void build() {
        LinearLayout group = group();
        RowView buy = row(group, R.drawable.ic_shield, R.color.accent, "Купить Proxy", "Личный, настроен, с поддержкой");
        buy.setTitleColor(a.color(R.color.accent));
        buyPrice = buy.setPill(proxyPrice(), a.color(R.color.blue_soft), a.color(R.color.blue_ink));
        buy.setOnClickListener(v -> a.openUrl(ShopCatalog.buyProxyUrl()));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        group.addView(list, new LinearLayout.LayoutParams(-1, -2));
        addRow(group, "Добавить подключение").setOnClickListener(v -> showAdd());
        stateText = caption(content, "");
    }

    private String proxyPrice() {
        for (ShopCatalog.Item item : ShopCatalog.load(a)) if (item.id.equals("proxy")) return item.priceText();
        return "Магазин";
    }

    @Override void onState() {
        view();
        String json = a.connectionList.toString() + (a.state == null ? "" : a.state.optString("selectedId"));
        if (!json.equals(shownList)) { shownList = json; renderList(); }
        if (a.state == null) stateText.setText(a.linkProblem);
        else {
            String type = a.state.optString("selectedType");
            stateText.setText(type.isEmpty() ? "Пока подключение не выбрано, интернет копий закрыт."
                    : type.equals("Hysteria2") ? "TCP и UDP. Интернет: только IPv4." : "Только TCP. Интернет: только IPv4.");
        }
    }

    private void renderList() {
        list.removeAllViews();
        String selected = a.state == null ? "" : a.state.optString("selectedId");
        JSONArray items = a.connectionList;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id"), name = item.optString("name"), type = item.optString("type");
            boolean isSelected = id.equals(selected);
            boolean editable = ConnectionProfile.UI_TYPES.contains(type);
            RowView row = new RowView(a);
            if (isSelected) row.setIcon(R.drawable.ic_check, a.color(R.color.accent), 0xFFFFFFFF);
            else row.setIcon(R.drawable.ic_globe, a.color(R.color.grey_soft), a.color(R.color.muted));
            row.setTitle(name).setTitleBold(isSelected)
                    .setCaption(item.optString("typeLabel", type) + ", " + item.optString("server")
                            + (editable ? "" : ", не поддерживается"));
            row.setEnd(textAction(editable ? "Изменить" : "Удалить", v -> {
                if (editable) edit(id); else confirmDelete(id, name, isSelected);
            }));
            row.setContentDescription(name + (isSelected ? ", используется" : ", нажми, чтобы использовать"));
            row.setOnClickListener(v -> {
                if (isSelected || !editable) return;
                a.toast("Переключаем на «" + name + "»…", true);
                a.remote(() -> { a.mServiceWork.activateConnection(id); return null; },
                        x -> { a.toast("Готово: «" + name + "» используется."); a.refreshState(); },
                        "Не удалось выбрать подключение.");
            });
            // Each connection brings its own top line; «Добавить» adds the line under the last one.
            separator(list);
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // ---- Sheets ----

    private BottomSheetDialog openSheet(View body, String title) {
        if (sheet != null) sheet.dismiss();
        NestedScrollView scroll = new NestedScrollView(a);
        scroll.addView(body);
        sheet = new BottomSheetDialog(a);
        sheet.setContentView(scroll);
        // Passwords may be on screen: keep them out of screenshots and the recents preview.
        if (sheet.getWindow() != null) sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ((TextView) body.findViewById(R.id.sheet_title)).setText(title);
        BottomSheetDialog current = sheet;
        body.findViewById(R.id.sheet_close).setOnClickListener(v -> current.dismiss());
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.show();
        return sheet;
    }

    void showAdd() {
        View body = LayoutInflater.from(a).inflate(R.layout.sheet_add, null);
        EditText input = body.findViewById(R.id.link_input);
        addError = body.findViewById(R.id.add_error);
        // The link the bot just gave is usually in the clipboard.
        ClipboardManager clipboard = a.getSystemService(ClipboardManager.class);
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).coerceToText(a);
            String text = clip == null ? "" : clip.toString().trim();
            if (text.matches("(?i)(hy2|hysteria2|socks5|http|https)://\\S+") && text.length() <= 8192) input.setText(text);
        }
        body.findViewById(R.id.add_button).setOnClickListener(v -> importText(input.getText().toString(), addError));
        input.setOnEditorActionListener((v, id, e) -> { importText(input.getText().toString(), addError); return true; });
        body.findViewById(R.id.add_file).setOnClickListener(v -> a.openConnectionFile.launch(new String[]{"application/json", "text/*", "application/octet-stream"}));
        body.findViewById(R.id.add_qr).setOnClickListener(v -> {
            try { a.scanQr.launch(new Intent(a, ConnectionScannerActivity.class)); }
            catch (RuntimeException e) { showError(addError, "Сканер QR-кода недоступен."); }
        });
        body.findViewById(R.id.add_manual).setOnClickListener(v -> showEditor(null, null));
        openSheet(body, "Новое подключение");
    }

    private void showError(TextView target, String message) {
        if (target != null && sheet != null && sheet.isShowing()) {
            target.setText(message);
            target.setVisibility(View.VISIBLE);
        } else a.toast(message);
    }

    /** Link, list of links, a Pocket Windows file or a sing-box config: saved, and the first one is used. */
    void importText(String text, TextView errorView) {
        a.worker.execute(() -> {
            ProfileImporter.Result result;
            try { result = ProfileImporter.parseForInterface(text); }
            catch (IllegalArgumentException e) { a.post(() -> showError(errorView, e.getMessage())); return; }
            if (result.draft != null) { a.post(() -> showEditor(null, result.draft)); return; }
            try {
                String first = null;
                for (ConnectionProfile profile : result.profiles) {
                    String id = a.mServiceWork.saveConnection(profile.toJson().toString());
                    if (first == null) first = id;
                }
                a.mServiceWork.activateConnection(first);
                String name = result.profiles.get(0).name;
                int count = result.profiles.size();
                String warning = result.warnings.isEmpty() ? "" : " " + result.warnings.get(0);
                a.post(() -> {
                    if (sheet != null) sheet.dismiss();
                    a.openConnections();
                    a.toast((count == 1 ? "Готово: «" + name + "» используется." : "Добавлено подключений: " + count + ". Используется «" + name + "».") + warning);
                    a.refreshState();
                });
            } catch (Exception e) {
                a.post(() -> showError(errorView, e instanceof IllegalArgumentException && e.getMessage() != null
                        ? e.getMessage() : "Не удалось сохранить подключение. Открой Pocket снова."));
            }
        });
    }

    void readFile(Uri uri) {
        a.worker.execute(() -> {
            try (InputStream input = a.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException();
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > ProfileImporter.MAX_INPUT_BYTES) throw new IllegalArgumentException();
                    output.write(buffer, 0, count);
                }
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray())).toString();
                a.post(() -> importText(text, addError));
            } catch (Exception e) {
                a.post(() -> showError(addError, "Файл не прочитан. Нужен файл подключения не больше 1 МиБ."));
            }
        });
    }

    private void edit(String id) {
        a.remote(() -> new JSONObject(a.mServiceWork.readConnection(id)), json -> showEditor(id, json),
                "Не удалось открыть подключение.");
    }

    /** @param id existing connection, or null for a new one; @param values its fields (may be a draft without secrets). */
    private void showEditor(String id, JSONObject values) {
        View body = LayoutInflater.from(a).inflate(R.layout.sheet_editor, null);
        EditText name = body.findViewById(R.id.edit_name), host = body.findViewById(R.id.edit_host),
                port = body.findViewById(R.id.edit_port), sni = body.findViewById(R.id.edit_sni),
                auth = body.findViewById(R.id.edit_auth), obfsPassword = body.findViewById(R.id.edit_obfs_password),
                user = body.findViewById(R.id.edit_user), password = body.findViewById(R.id.edit_password);
        RadioGroup type = body.findViewById(R.id.edit_type);
        SwitchMaterial obfs = body.findViewById(R.id.edit_obfs), login = body.findViewById(R.id.edit_login);
        TextView error = body.findViewById(R.id.edit_error), caption = body.findViewById(R.id.edit_caption);
        int[] ids = {R.id.type_hysteria2, R.id.type_socks5, R.id.type_http, R.id.type_https};
        String current = values == null ? "hysteria2" : values.optString("type", "hysteria2");
        int index = Math.max(0, java.util.Arrays.asList(TYPES).indexOf(current));
        type.check(ids[index]);
        if (values != null) {
            name.setText(values.optString("name"));
            host.setText(values.optString("server"));
            if (values.has("port")) port.setText(String.valueOf(values.optInt("port")));
            String server = values.optString("server"), tls = values.optString("serverName");
            if (!tls.equals(server)) sni.setText(tls);
            if (current.equals("hysteria2")) auth.setText(values.optString("password"));
            else { user.setText(values.optString("username")); password.setText(values.optString("password")); }
            obfsPassword.setText(values.optString("obfsPassword"));
            obfs.setChecked(!values.optString("obfsPassword").isEmpty() || values.has("obfs"));
            login.setChecked(!values.optString("username").isEmpty());
        }
        Runnable layout = () -> {
            String t = TYPES[indexOf(ids, type.getCheckedRadioButtonId())];
            boolean hy2 = t.equals("hysteria2");
            body.findViewById(R.id.sni_row).setVisibility(hy2 || t.equals("https") ? View.VISIBLE : View.GONE);
            body.findViewById(R.id.auth_row).setVisibility(hy2 ? View.VISIBLE : View.GONE);
            body.findViewById(R.id.hysteria_rows).setVisibility(hy2 ? View.VISIBLE : View.GONE);
            body.findViewById(R.id.obfs_row).setVisibility(hy2 && obfs.isChecked() ? View.VISIBLE : View.GONE);
            body.findViewById(R.id.proxy_rows).setVisibility(hy2 ? View.GONE : View.VISIBLE);
            body.findViewById(R.id.login_rows).setVisibility(!hy2 && login.isChecked() ? View.VISIBLE : View.GONE);
            port.setHint(t.equals("socks5") ? "1080" : t.equals("http") ? "80" : "443");
            caption.setText(hy2 ? "TCP и UDP. Интернет: только IPv4." : t.equals("https") ? "HTTPS CONNECT, только TCP." : "Только TCP.");
        };
        type.setOnCheckedChangeListener((g, checked) -> layout.run());
        obfs.setOnCheckedChangeListener((b, c) -> layout.run());
        login.setOnCheckedChangeListener((b, c) -> layout.run());
        layout.run();
        body.findViewById(R.id.edit_extras).setVisibility(id == null ? View.GONE : View.VISIBLE);
        body.findViewById(R.id.edit_save).setOnClickListener(v -> {
            error.setVisibility(View.GONE);
            ConnectionProfile profile;
            try {
                String t = TYPES[indexOf(ids, type.getCheckedRadioButtonId())];
                boolean hy2 = t.equals("hysteria2");
                String portText = port.getText().toString().trim();
                if (portText.isEmpty()) portText = port.getHint().toString();
                JSONObject flat = new JSONObject().put("type", t).put("server", host.getText().toString().trim())
                        .put("port", Integer.parseInt(portText));
                if (id != null) flat.put("id", id);
                if (!name.getText().toString().trim().isEmpty()) flat.put("name", name.getText().toString().trim());
                if (hy2 || t.equals("https")) flat.put("serverName", sni.getText().toString().trim());
                if (hy2) {
                    flat.put("password", auth.getText().toString());
                    if (obfs.isChecked()) {
                        if (obfsPassword.getText().length() == 0) throw new IllegalArgumentException("Введи пароль маскировки.");
                        flat.put("obfsPassword", obfsPassword.getText().toString());
                    }
                } else if (login.isChecked()) {
                    flat.put("username", user.getText().toString()).put("password", password.getText().toString());
                }
                if (host.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("Укажи адрес сервера.");
                profile = ConnectionProfile.fromJson(flat);
            } catch (NumberFormatException e) {
                error.setText("Порт должен быть числом от 1 до 65535."); error.setVisibility(View.VISIBLE); return;
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage()); error.setVisibility(View.VISIBLE); return;
            } catch (org.json.JSONException e) {
                error.setText("Проверь поля подключения."); error.setVisibility(View.VISIBLE); return;
            }
            a.toast("Сохраняем и подключаемся…", true);
            a.remote(() -> {
                String saved = a.mServiceWork.saveConnection(profile.toJson().toString());
                // Saving the selected connection already restarts the core.
                if (a.state == null || !saved.equals(a.state.optString("selectedId"))) a.mServiceWork.activateConnection(saved);
                return saved;
            }, saved -> {
                if (sheet != null) sheet.dismiss();
                a.openConnections();
                a.toast("Готово: «" + profile.name + "» используется.");
                a.refreshState();
            }, "Не удалось сохранить подключение.");
        });
        if (id != null) {
            body.findViewById(R.id.edit_test).setOnClickListener(v -> {
                boolean active = a.state != null && id.equals(a.state.optString("selectedId"));
                if (active) Fixes.run(a, PocketStatus.Fix.CHECK);
                else a.toast("Проверяется используемое подключение. Нажми «Сохранить и использовать», затем «Проверить».");
            });
            body.findViewById(R.id.edit_export).setOnClickListener(v -> {
                try { showExport(ConnectionProfile.fromJson(values)); }
                catch (IllegalArgumentException e) { a.toast(e.getMessage()); }
            });
            body.findViewById(R.id.edit_delete).setOnClickListener(v -> {
                boolean active = a.state != null && id.equals(a.state.optString("selectedId"));
                confirmDelete(id, values.optString("name"), active);
            });
        }
        openSheet(body, id != null ? "Изменить подключение" : values != null ? "Введи пароль" : "Новое подключение");
        if (values != null && id == null) (current.equals("hysteria2") ? auth : password).requestFocus();
    }

    private static int indexOf(int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
        return 0;
    }

    private void confirmDelete(String id, String name, boolean active) {
        new AlertDialog.Builder(a)
                .setTitle("Удалить «" + name + "»?")
                .setMessage(active ? "Это подключение используется. Пока не выбрано другое, интернет копий будет закрыт."
                        : "Подключение и его пароли будут удалены с телефона.")
                .setPositiveButton("Удалить", (d, w) -> a.remote(() -> { a.mServiceWork.deleteConnection(id); return null; },
                        x -> { if (sheet != null) sheet.dismiss(); a.toast("Подключение «" + name + "» удалено."); a.refreshState(); },
                        "Не удалось удалить подключение."))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showExport(ConnectionProfile profile) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(16));
        LayoutInflater.from(a).inflate(R.layout.view_sheet_header, body, true);
        LinearLayout saved = content;
        content = body;
        LinearLayout group = group();
        content = saved;
        LinearLayout line = new LinearLayout(a);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setPadding(a.dp(16), a.dp(12), a.dp(12), a.dp(12));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(a);
        title.setTextAppearance(R.style.RowText);
        title.setText("Включить пароли");
        TextView note = new TextView(a);
        note.setTextAppearance(R.style.Caption);
        note.setText("Пароли попадут в файл открытым текстом. Передавай его только себе или доверенному человеку.");
        texts.addView(title);
        texts.addView(note);
        line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        SwitchMaterial secrets = new SwitchMaterial(a);
        secrets.setContentDescription("Включить пароли");
        line.addView(secrets);
        group.addView(line);
        TextView caption = new TextView(a);
        caption.setTextAppearance(R.style.Caption);
        caption.setText("Файл в формате Pocket Windows: его можно открыть и на ПК.");
        caption.setPadding(a.dp(4), 0, a.dp(4), a.dp(12));
        body.addView(caption);
        pill(body, R.style.Pill, R.drawable.bg_pill_accent, "Сохранить файл", v -> {
            pendingExport = profile;
            pendingSecrets = secrets.isChecked();
            a.createExportFile.launch(profile.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json");
        });
        openSheet(body, "Экспорт подключения");
    }

    void writeExport(Uri uri) {
        ConnectionProfile profile = pendingExport;
        boolean secrets = pendingSecrets;
        pendingExport = null;
        if (profile == null || uri == null) return;
        a.worker.execute(() -> {
            try (OutputStream output = a.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException();
                output.write(profile.toWindowsJson(secrets).toString(2).getBytes(StandardCharsets.UTF_8));
                a.post(() -> { if (sheet != null) sheet.dismiss(); a.toast("Файл подключения сохранён" + (secrets ? " с паролями." : " без паролей.")); });
            } catch (Exception e) {
                a.post(() -> a.toast("Не удалось сохранить файл. Выбери другую папку."));
            }
        });
    }

    @Override void onHide() { if (sheet != null) sheet.dismiss(); }
}
