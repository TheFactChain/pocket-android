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
import net.typeblog.shelter.connections.Subscription;
import net.typeblog.shelter.services.IShelterService;
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
        addRow(group, "Добавить подписку или подключение").setOnClickListener(v -> showAdd());
        stateText = caption(content, "");
        subscriptionsHost = new LinearLayout(a);
        subscriptionsHost.setOrientation(LinearLayout.VERTICAL);
        // Subscriptions come first, as in Happ: that is where most servers live.
        content.addView(subscriptionsHost, content.indexOfChild(group), new LinearLayout.LayoutParams(-1, -2));
    }

    @Override void onShow() {
        super.onShow();
        // Opening the list is the moment Happ refreshes stale subscriptions; the work happens in the VPN process.
        IShelterService work = a.mServiceWork;
        if (work != null) a.worker.execute(() -> { try { work.refreshDueSubscriptions(); } catch (Exception ignored) { } });
    }

    private String proxyPrice() {
        for (ShopCatalog.Item item : ShopCatalog.load(a)) if (item.id.equals("proxy")) return item.priceText();
        return "Магазин";
    }

    @Override void onState() {
        view();
        String json = a.connectionList.toString() + a.subscriptionList + (a.state == null ? "" : a.state.optString("selectedId"));
        if (!json.equals(shownList)) { shownList = json; renderList(); }
        if (a.state == null) stateText.setText(a.linkProblem);
        else {
            String type = a.state.optString("selectedType");
            stateText.setText(type.isEmpty() ? "Пока подключение не выбрано, интернет копий закрыт."
                    : type.equals("Hysteria2") || type.equals("VLESS") ? "TCP и UDP. Интернет: только IPv4." : "Только TCP. Интернет: только IPv4.");
        }
    }

    private void renderList() {
        list.removeAllViews();
        subscriptionsHost.removeAllViews();
        String selected = a.state == null ? "" : a.state.optString("selectedId");
        JSONArray items = a.connectionList;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (int i = 0; i < a.subscriptionList.length(); i++) {
            JSONObject sub = a.subscriptionList.optJSONObject(i);
            if (sub != null) known.add(sub.optString("id"));
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || known.contains(item.optString("subscription"))) continue;
            String id = item.optString("id"), name = item.optString("name"), type = item.optString("type");
            boolean isSelected = id.equals(selected);
            boolean editable = ConnectionProfile.UI_TYPES.contains(type);
            RowView row = serverRow(item, isSelected);
            row.setCaption(item.optString("details", type) + ", " + item.optString("server"));
            row.setEnd(textAction(editable ? "Изменить" : "Удалить", v -> {
                if (editable) edit(id); else confirmDelete(id, name, isSelected);
            }));
            // Each connection brings its own top line; «Добавить» adds the line under the last one.
            separator(list);
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < a.subscriptionList.length(); i++) {
            JSONObject sub = a.subscriptionList.optJSONObject(i);
            if (sub != null) renderSubscription(sub, items, selected);
        }
    }

    /** A server line: tap to use it. The caller sets the caption and the end view. */
    private RowView serverRow(JSONObject item, boolean isSelected) {
        String id = item.optString("id"), name = item.optString("name");
        RowView row = new RowView(a);
        if (isSelected) row.setIcon(R.drawable.ic_check, a.color(R.color.accent), 0xFFFFFFFF);
        else row.setIcon(R.drawable.ic_globe, a.color(R.color.grey_soft), a.color(R.color.muted));
        row.setTitle(name).setTitleBold(isSelected);
        row.setContentDescription(name + (isSelected ? ", используется" : ", нажми, чтобы использовать"));
        row.setOnClickListener(v -> {
            if (isSelected) return;
            a.toast("Переключаем на «" + name + "»…", true);
            a.remote(() -> { a.mServiceWork.activateConnection(id); return null; },
                    x -> { a.toast("Готово: «" + name + "» используется."); a.refreshState(); },
                    "Не удалось выбрать подключение.");
        });
        return row;
    }

    // ---- Subscriptions (as Happ shows them: provider, traffic, expiry, servers with ping) ----

    private LinearLayout subscriptionsHost;
    /** Last ping per server id in milliseconds; see ServerPing for the negative codes. */
    private final java.util.Map<String, Long> pings = new java.util.HashMap<>();
    private final java.util.Set<String> pinging = new java.util.HashSet<>(), pinged = new java.util.HashSet<>();
    private final java.util.Set<String> refreshing = new java.util.HashSet<>();

    private void renderSubscription(JSONObject sub, JSONArray items, String selected) {
        String subId = sub.optString("id"), title = sub.optString("title");
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_group);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = a.dp(22);
        subscriptionsHost.addView(card, cardParams);

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(a.dp(16), a.dp(14), a.dp(12), a.dp(10));
        card.addView(header);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(top);
        TextView name = new TextView(a);
        name.setTextAppearance(R.style.RowText);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setText(title);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        boolean busy = refreshing.contains(subId);
        top.addView(textAction(busy ? "Обновляем…" : "Обновить", v -> refreshSubscription(subId, title)));
        top.addView(textAction(pinging.contains(subId) ? "Пинг…" : "Пинг", v -> ping(subId)));
        top.addView(textAction("Ещё", v -> subscriptionMenu(sub)));

        long updated = sub.optLong("updated");
        String meta = (updated > 0 ? "Обновлено " + new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(new java.util.Date(updated)) : "Ещё не обновлялась")
                + " · авто каждые " + sub.optInt("intervalHours", 12) + " ч";
        TextView metaText = small(header, meta, R.color.muted);
        metaText.setPadding(0, a.dp(2), 0, 0);
        if (!sub.optString("error").isEmpty()) small(header, "Не обновилась: " + sub.optString("error") + " Работают прежние серверы.", R.color.danger);

        // Traffic and expiry, the two numbers people check first.
        long used = sub.optLong("upload") + sub.optLong("download"), total = sub.optLong("total"), expire = sub.optLong("expire");
        if (used > 0 || total > 0 || expire > 0) {
            LinearLayout usage = new LinearLayout(a);
            usage.setGravity(android.view.Gravity.CENTER_VERTICAL);
            usage.setPadding(0, a.dp(8), 0, 0);
            header.addView(usage);
            TextView traffic = new TextView(a);
            traffic.setTextAppearance(R.style.Caption);
            traffic.setTextColor(a.color(R.color.ink));
            traffic.setText("Трафик: " + bytes(used) + " / " + (total > 0 ? bytes(total) : "∞"));
            usage.addView(traffic, new LinearLayout.LayoutParams(0, -2, 1));
            if (expire > 0) {
                long left = expire * 1000 - System.currentTimeMillis();
                TextView until = new TextView(a);
                until.setTextAppearance(R.style.Caption);
                String date = new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.ROOT).format(new java.util.Date(expire * 1000));
                until.setText(left <= 0 ? "Истекла " + date : "Истекает " + date);
                until.setTextColor(a.color(left <= 0 ? R.color.danger : left < 3 * 86_400_000L ? R.color.orange_ink : R.color.ink));
                usage.addView(until);
            }
            if (total > 0) {
                android.widget.ProgressBar bar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(1000);
                bar.setProgress((int) Math.min(1000, used * 1000 / total));
                bar.setProgressTintList(ColorStateList.valueOf(a.color(used >= total ? R.color.danger : R.color.green)));
                bar.setContentDescription("Использовано " + bytes(used) + " из " + bytes(total));
                LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, a.dp(6));
                barParams.topMargin = a.dp(6);
                header.addView(bar, barParams);
            }
        }
        if (!sub.optString("announce").isEmpty()) {
            TextView announce = small(header, sub.optString("announce"), R.color.ink);
            announce.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            announce.setBackgroundResource(R.drawable.bg_capsule);
            announce.setBackgroundTintList(ColorStateList.valueOf(a.color(R.color.blue_soft)));
            ((LinearLayout.LayoutParams) announce.getLayoutParams()).topMargin = a.dp(8);
        }

        int count = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !subId.equals(item.optString("subscription"))) continue;
            count++;
            boolean isSelected = item.optString("id").equals(selected);
            RowView row = serverRow(item, isSelected);
            row.setCaption(item.optString("details"));
            Long ms = pings.get(item.optString("id"));
            if (ms != null) {
                boolean good = ms > 0 && ms <= 150, ok = ms > 150 && ms <= 400;
                row.setPill(ms > 0 ? ms + " мс" : ms == -2 ? "скрыт" : "нет ответа",
                        a.color(good ? R.color.green_soft : ok ? R.color.orange_soft : ms > 0 ? R.color.danger_soft : R.color.grey_soft),
                        a.color(good ? R.color.green_ink : ok ? R.color.orange_ink : ms > 0 ? R.color.danger : R.color.muted));
            }
            separator(card);
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (count == 0) {
            separator(card);
            TextView empty = small(card, "В подписке пока нет серверов, которые Pocket умеет подключать.", R.color.muted);
            empty.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(14));
        } else if (!pinged.contains(subId)) ping(subId);
    }

    private TextView small(LinearLayout parent, String text, int color) {
        TextView view = new TextView(a);
        view.setTextAppearance(R.style.Caption);
        view.setTextColor(a.color(color));
        view.setText(text);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    static String bytes(long value) {
        double gb = value / 1073741824.0;
        if (gb >= 10) return Math.round(gb) + " ГБ";
        if (gb >= 0.1) return String.format(java.util.Locale.ROOT, "%.1f ГБ", gb).replace('.', ',');
        return Math.round(value / 1048576.0) + " МБ";
    }

    private void ping(String subId) {
        if (pinging.contains(subId) || a.mServiceWork == null) return;
        pinging.add(subId);
        pinged.add(subId);
        renderList();
        IShelterService work = a.mServiceWork;
        a.worker.execute(() -> {
            JSONObject result = null;
            try { result = new JSONObject(work.pingConnections(subId)); } catch (Exception ignored) { }
            JSONObject values = result;
            a.post(() -> {
                pinging.remove(subId);
                if (values == null) a.toast("Пинг не выполнен. Открой Pocket снова.");
                else for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
                    String key = keys.next();
                    pings.put(key, values.optLong(key, -1));
                }
                renderList();
            });
        });
    }

    private void refreshSubscription(String subId, String title) {
        if (!refreshing.add(subId)) return;
        renderList();
        a.toast("Обновляем «" + title + "»…", true);
        IShelterService work = a.mServiceWork;
        if (work == null) { refreshing.remove(subId); a.toast(a.linkProblem == null ? "Рабочий профиль недоступен." : a.linkProblem); return; }
        a.worker.execute(() -> {
            String message;
            try {
                JSONObject result = new JSONObject(work.refreshSubscription(subId));
                message = "«" + result.optString("title") + "» обновлена: серверов " + result.optInt("count") + ".";
                if (result.optBoolean("selectedGone")) message += " Выбранного сервера больше нет в подписке, он оставлен до выбора другого.";
            } catch (IllegalArgumentException e) {
                message = e.getMessage() + " Работают прежние серверы.";
            } catch (Exception e) {
                message = "Подписка не обновилась. Работают прежние серверы.";
            }
            String text = message;
            a.post(() -> {
                refreshing.remove(subId);
                pinged.remove(subId);
                a.toast(text);
                a.refreshState();
                renderList();
            });
        });
    }

    private void subscriptionMenu(JSONObject sub) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        String support = sub.optString("supportUrl"), web = sub.optString("webUrl"), title = sub.optString("title");
        if (!support.isEmpty()) { labels.add("Поддержка провайдера"); actions.add(() -> a.openUrl(support)); }
        if (!web.isEmpty()) { labels.add("Сайт провайдера"); actions.add(() -> a.openUrl(web)); }
        labels.add("Удалить подписку");
        actions.add(() -> confirmDeleteSubscription(sub.optString("id"), title));
        new AlertDialog.Builder(a).setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Закрыть", null).show();
    }

    private void confirmDeleteSubscription(String subId, String title) {
        boolean active = false;
        String selected = a.state == null ? "" : a.state.optString("selectedId");
        for (int i = 0; i < a.connectionList.length(); i++) {
            JSONObject item = a.connectionList.optJSONObject(i);
            if (item != null && item.optString("id").equals(selected) && subId.equals(item.optString("subscription"))) active = true;
        }
        new AlertDialog.Builder(a)
                .setTitle("Удалить подписку «" + title + "»?")
                .setMessage(active ? "Сейчас используется сервер из этой подписки. Пока не выбрано другое подключение, интернет копий будет закрыт."
                        : "Подписка и её серверы будут удалены с телефона.")
                .setPositiveButton("Удалить", (d, w) -> a.remote(() -> { a.mServiceWork.deleteSubscription(subId); return null; },
                        x -> { a.toast("Подписка «" + title + "» удалена."); a.refreshState(); },
                        "Не удалось удалить подписку."))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** One paste: download the subscription, keep the current connection, or use the first server if none is chosen. */
    private void addSubscription(String url, TextView errorView) {
        String current = a.state == null ? "?" : a.state.optString("selectedId");
        String currentName = a.state == null ? "" : a.state.optString("selectedName");
        if (errorView != null) errorView.setVisibility(View.GONE);
        a.toast("Загружаем подписку…", true);
        IShelterService work = a.mServiceWork;
        if (work == null) { showError(errorView, a.linkProblem == null ? "Рабочий профиль недоступен." : a.linkProblem); return; }
        a.worker.execute(() -> {
            try {
                JSONObject result = new JSONObject(work.addSubscription(url));
                boolean use = current.isEmpty() && !result.optString("firstId").isEmpty();
                if (use) work.activateConnection(result.optString("firstId"));
                int count = result.optInt("count"), skipped = result.optInt("skipped");
                String message = "Подписка «" + result.optString("title") + "»: серверов " + count + "."
                        + (skipped > 0 ? " Пропущено " + skipped + ": " + result.optString("warning") : "")
                        + (use ? " Подключаемся к первому." : current.isEmpty() ? "" : " Текущее подключение"
                        + (currentName.isEmpty() ? "" : " «" + currentName + "»") + " не изменено; выбери сервер в списке.");
                a.post(() -> {
                    if (sheet != null) sheet.dismiss();
                    a.hideToast();
                    a.openConnections();
                    a.toast(message);
                    a.refreshState();
                });
            } catch (IllegalArgumentException e) {
                a.post(() -> { a.hideToast(); showError(errorView, e.getMessage()); });
            } catch (Exception e) {
                a.post(() -> { a.hideToast(); showError(errorView, "Подписка не загрузилась. Открой Pocket снова."); });
            }
        });
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
            if (text.matches("(?i)(hy2|hysteria2|vless|socks5|http|https|happ)://\\S+") && text.length() <= 8192) input.setText(text);
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

    /** Link, list of links, a Pocket Windows file or a sing-box config: saved; the first one is used only if none is selected yet. */
    void importText(String text, TextView errorView) {
        String subscription = Subscription.subscriptionUrl(text);
        if (subscription != null) { addSubscription(subscription, errorView); return; }
        // Unknown state counts as "something may be selected", so nothing is swapped blindly.
        String current = a.state == null ? "?" : a.state.optString("selectedId");
        String currentName = a.state == null ? "" : a.state.optString("selectedName");
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
                // A working connection is never swapped silently: the user picks the new one in the list.
                boolean keep = !current.isEmpty();
                if (!keep) a.mServiceWork.activateConnection(first);
                String name = result.profiles.get(0).name;
                int count = result.profiles.size();
                String warning = result.warnings.isEmpty() ? "" : " " + result.warnings.get(0);
                String added = count == 1 ? "Добавлено «" + name + "»." : "Добавлено подключений: " + count + ".";
                String message = keep
                        ? added + " Текущее подключение" + (currentName.isEmpty() ? "" : " «" + currentName + "»") + " не изменено; выбери новое в списке."
                        : (count == 1 ? "Готово: «" + name + "» используется." : added + " Используется «" + name + "».");
                a.post(() -> {
                    if (sheet != null) sheet.dismiss();
                    a.openConnections();
                    a.toast(message + warning);
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
