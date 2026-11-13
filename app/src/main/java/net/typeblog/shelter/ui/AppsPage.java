package net.typeblog.shelter.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.services.IAppInstallCallback;
import net.typeblog.shelter.services.IGetAppsCallback;
import net.typeblog.shelter.services.ILoadIconCallback;
import net.typeblog.shelter.services.IShelterService;
import net.typeblog.shelter.services.ShelterService;
import net.typeblog.shelter.util.ApplicationInfoWrapper;
import net.typeblog.shelter.util.AuthenticationUtility;
import net.typeblog.shelter.util.LocalStorageManager;
import net.typeblog.shelter.util.Utility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The «Приложения» card of the home screen: copies as an icon grid. Tap opens, hold for more, «+» adds. */
final class AppsPage extends Page {
    private static final int COLUMNS = 4;
    private static final String PREF_SYSTEM_COPIES = "pocket_system_copies";
    private LinearLayout grid;
    private TextView note, count;
    private android.widget.ImageView add;
    private PocketStatus status;
    private final Map<String, Bitmap> icons = new HashMap<>();
    private List<ApplicationInfoWrapper> copies = new ArrayList<>();
    private boolean loaded;
    private int request;
    private BottomSheetDialog sheet;

    AppsPage(MainActivity activity) { super(activity); }

    /** Takes the card views from the home layout. */
    void bind(View card) {
        grid = card.findViewById(R.id.apps_grid);
        note = card.findViewById(R.id.apps_note);
        count = card.findViewById(R.id.apps_count);
        add = card.findViewById(R.id.apps_add);
        add.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(a, R.animator.tap));
        add.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (status != null && status.canAdd) showPicker();
            else a.toast(status == null ? a.linkProblem : status.lockReason);
        });
        render();
    }

    void setStatus(PocketStatus value) {
        status = value;
        if (grid == null) return;
        add.setBackgroundTintList(android.content.res.ColorStateList.valueOf(a.color(status.canAdd ? R.color.ink : R.color.grey_disabled)));
        add.setContentDescription(status.canAdd ? "Добавить приложение" : "Добавить приложение, недоступно: " + status.title);
        if (a.mServiceWork != null && !loaded) reload();
        if (renderedLocked == null || renderedLocked == status.canAdd) render();
    }

    private Boolean renderedLocked;

    /** Loads the work profile list. A newer request discards older answers. */
    void reload() {
        if (grid == null || a.mServiceWork == null) return;
        loaded = true;
        int current = ++request;
        loadApps(a.mServiceWork, apps -> {
            if (current != request) return;
            copies = new ArrayList<>();
            // Preinstalled apps already enabled in the profile (Play Store, Settings) stay out; only ones Pocket enabled show.
            for (ApplicationInfoWrapper app : apps) if (!app.isSystem() || (!app.isHidden() && isSystemCopy(app))) copies.add(app);
            a.copiesCount = copies.size();
            render();
        }, () -> { if (current == request) { loaded = false; note.setText("Не удалось загрузить копии. Открой Pocket снова."); } });
    }

    /** System apps share the personal APK: Pocket enables them in the profile and remembers which ones it enabled. */
    private static boolean isSystemCopy(ApplicationInfoWrapper app) {
        return LocalStorageManager.getInstance().stringListContains(PREF_SYSTEM_COPIES, app.getPackageName());
    }

    private static void markSystemCopy(ApplicationInfoWrapper app, boolean present) {
        LocalStorageManager storage = LocalStorageManager.getInstance();
        storage.removeFromStringList(PREF_SYSTEM_COPIES, app.getPackageName());
        if (present) storage.appendStringList(PREF_SYSTEM_COPIES, app.getPackageName());
    }

    interface AppsDone { void accept(List<ApplicationInfoWrapper> apps); }

    private void loadApps(IShelterService service, AppsDone done, Runnable failed) {
        Runnable timeout = failed;
        boolean[] finished = {false};
        Runnable once = () -> { if (!finished[0]) { finished[0] = true; timeout.run(); } };
        a.main.postDelayed(once, 15000);
        a.worker.execute(() -> {
            try {
                service.getApps(new IGetAppsCallback.Stub() {
                    @Override public void callback(List<ApplicationInfoWrapper> apps) {
                        AuthenticationUtility.requireTrustedBinderCaller();
                        a.post(() -> {
                            if (finished[0]) return;
                            finished[0] = true;
                            a.main.removeCallbacks(once);
                            done.accept(apps);
                        });
                    }
                    @Override public void onError(int reason) {
                        AuthenticationUtility.requireTrustedBinderCaller();
                        a.post(() -> { a.main.removeCallbacks(once); once.run(); });
                    }
                }, false);
            } catch (Exception e) {
                a.post(() -> { a.main.removeCallbacks(once); once.run(); });
            }
        });
    }

    private void render() {
        if (grid == null) return;
        grid.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < copies.size(); i++) {
            if (i % COLUMNS == 0) {
                line = new LinearLayout(a);
                grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            line.addView(appTile(copies.get(i)), new LinearLayout.LayoutParams(0, -2, 1));
        }
        for (int i = copies.size() % COLUMNS; line != null && i > 0 && i < COLUMNS; i++) line.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        boolean locked = status != null && !status.canAdd;
        renderedLocked = status == null ? null : locked;
        count.setText(!loaded ? "Только через прокси Pocket" : copies.isEmpty() ? "Копий пока нет" : copiesText(copies.size()) + ", только через прокси");
        if (a.mServiceWork == null) note.setText("Копии появятся, когда профиль свяжется.");
        else if (!loaded) note.setText("Загружаем копии…");
        else if (copies.isEmpty()) note.setText(locked ? "Подключи прокси, затем нажми «+», чтобы добавить первое приложение."
                : "Нажми «+», чтобы добавить первое приложение. Оно будет выходить в интернет только через прокси.");
        else note.setText("Удерживай значок для ярлыка и удаления");
    }

    static String copiesText(int n) {
        int mod10 = n % 10, mod100 = n % 100;
        String word = mod10 == 1 && mod100 != 11 ? "копия" : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "копии" : "копий";
        return n + " " + word;
    }

    private LinearLayout tileFrame(String label) {
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tile.setPadding(a.dp(2), a.dp(6), a.dp(2), a.dp(10));
        tile.setBackgroundResource(R.drawable.bg_row);
        tile.setContentDescription(label);
        return tile;
    }

    private TextView tileLabel(String text, int color) {
        TextView label = new TextView(a);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(color);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(android.view.Gravity.CENTER);
        label.setPadding(0, a.dp(6), 0, 0);
        return label;
    }

    private View appTile(ApplicationInfoWrapper app) {
        LinearLayout tile = tileFrame(app.getLabel());
        android.widget.ImageView icon = new android.widget.ImageView(a);
        String key = "w:" + app.getPackageName();
        Bitmap cached = icons.get(key);
        if (cached != null) icon.setImageBitmap(cached);
        else {
            icon.setImageDrawable(a.getPackageManager().getDefaultActivityIcon());
            loadIcon(a.mServiceWork, app, bitmap -> { icons.put(key, bitmap); icon.setImageBitmap(bitmap); });
        }
        // Same rounded square for every icon, whatever shape the app ships.
        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), a.dp(14));
            }
        });
        icon.setClipToOutline(true);
        tile.addView(icon, new LinearLayout.LayoutParams(a.dp(52), a.dp(52)));
        tile.addView(tileLabel(app.getLabel(), a.color(R.color.ink)), new LinearLayout.LayoutParams(-1, -2));
        tile.setOnClickListener(v -> launch(app));
        tile.setOnLongClickListener(v -> { showMenu(app); return true; });
        return tile;
    }

    private RowView appRow(LinearLayout group, ApplicationInfoWrapper app, IShelterService service) {
        if (group.getChildCount() > 0) separator(group);
        RowView row = new RowView(a);
        row.setTitle(app.getLabel());
        row.setAppIcon(a.getPackageManager().getDefaultActivityIcon());
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        String key = (service == a.mServiceWork ? "w:" : "p:") + app.getPackageName();
        Bitmap cached = icons.get(key);
        if (cached != null) row.setAppIcon(cached);
        else loadIcon(service, app, icon -> { icons.put(key, icon); row.setAppIcon(icon); });
        return row;
    }

    interface IconDone { void accept(Bitmap icon); }

    private void loadIcon(IShelterService service, ApplicationInfoWrapper app, IconDone done) {
        try {
            service.loadIcon(app, new ILoadIconCallback.Stub() {
                @Override public void callback(Bitmap icon) {
                    AuthenticationUtility.requireTrustedBinderCaller();
                    if (icon != null) a.post(() -> done.accept(icon));
                }
            });
        } catch (Exception ignored) { }
    }

    private long profileSerial() throws Exception {
        long serial = a.state == null ? -1 : a.state.optLong("profileSerial", -1);
        if (serial < 0) serial = new org.json.JSONObject(a.mServiceWork.getPocketState()).getLong("profileSerial");
        if (serial < 0) throw new IllegalStateException();
        return serial;
    }

    private void launch(ApplicationInfoWrapper app) {
        try {
            Intent intent = new Intent(DummyActivity.UNFREEZE_AND_LAUNCH);
            intent.setComponent(new ComponentName(a, DummyActivity.class));
            intent.putExtra("packageName", app.getPackageName());
            intent.putExtra("profileSerial", profileSerial());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            DummyActivity.registerSameProcessRequest(intent);
            a.startActivity(intent);
        } catch (Exception e) {
            a.toast("Рабочий профиль недоступен. Открой Pocket снова.");
        }
    }

    private void showMenu(ApplicationInfoWrapper app) {
        v().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(20));
        LayoutInflater.from(a).inflate(R.layout.view_sheet_header, body, true);
        ((TextView) body.findViewById(R.id.sheet_title)).setText(app.getLabel());
        LinearLayout group = new LinearLayout(a);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_group);
        group.setClipToOutline(true);
        body.addView(group, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog menu = new BottomSheetDialog(a);
        menuRow(group, menu, R.drawable.ic_apps, R.color.pastel_blue, "Открыть", () -> launch(app));
        menuRow(group, menu, R.drawable.ic_add, R.color.pastel_green, "Ярлык на экран", () -> shortcut(app));
        menuRow(group, menu, R.drawable.ic_route, R.color.pastel_blue, "Подключение: " + connectionName(app.getPackageName()), () -> chooseConnection(app));
        // A system copy runs the personal APK, so it updates together with it.
        if (!app.isSystem()) menuRow(group, menu, R.drawable.ic_download, R.color.pastel_lavender, "Обновить копию", () -> update(app));
        RowView remove = menuRow(group, menu, R.drawable.ic_warning, R.color.pastel_orange, "Удалить копию", () -> confirmRemove(app));
        remove.setTitleColor(a.color(R.color.danger));
        menu.setContentView(body);
        body.findViewById(R.id.sheet_close).setOnClickListener(v -> menu.dismiss());
        menu.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        menu.getBehavior().setSkipCollapsed(true);
        menu.show();
    }

    private View v() { return grid; }

    /** The app's own connection, or «как у всех» when it follows the selected one. */
    private String connectionName(String pkg) {
        String id = a.state == null || a.state.optJSONObject("appConnections") == null ? "" : a.state.optJSONObject("appConnections").optString(pkg);
        for (int i = 0; !id.isEmpty() && a.connectionList != null && i < a.connectionList.length(); i++) {
            org.json.JSONObject value = a.connectionList.optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) return value.optString("name");
        }
        return id.isEmpty() ? "как у всех" : a.connectionList == null ? "…" : "удалено, выбери другое";
    }

    private void chooseConnection(ApplicationInfoWrapper app) {
        int count = a.connectionList == null ? 0 : a.connectionList.length();
        if (count == 0) { a.toast("Сначала добавь подключение на вкладке «Прокси»."); return; }
        String[] names = new String[count + 1], ids = new String[count + 1];
        names[0] = "Как у всех (выбранное подключение)"; ids[0] = "";
        for (int i = 0; i < count; i++) {
            org.json.JSONObject value = a.connectionList.optJSONObject(i);
            ids[i + 1] = value.optString("id");
            names[i + 1] = value.optString("name") + " · " + value.optString("typeLabel");
        }
        new AlertDialog.Builder(a).setTitle("Подключение для «" + app.getLabel() + "»")
                .setItems(names, (d, which) -> a.remote(() -> { a.mServiceWork.setAppConnection(app.getPackageName(), ids[which]); return null; },
                        x -> { a.toast(which == 0 ? "«" + app.getLabel() + "» идёт через выбранное подключение." : "«" + app.getLabel() + "» идёт через «" + names[which] + "». Переподключаемся…"); a.refreshState(); },
                        "Не удалось изменить подключение приложения."))
                .setNegativeButton("Отмена", null).show();
    }

    private RowView menuRow(LinearLayout group, BottomSheetDialog menu, int icon, int tile, String title, Runnable action) {
        if (group.getChildCount() > 0) separator(group);
        RowView row = new RowView(a);
        row.setIcon(icon, a.color(tile), a.color(R.color.ink)).setTitle(title);
        row.setOnClickListener(x -> { menu.dismiss(); action.run(); });
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void shortcut(ApplicationInfoWrapper app) {
        loadIcon(a.mServiceWork, app, icon -> {
            try {
                Intent intent = Utility.registerWorkShortcut(a, profileSerial(), app.getPackageName(), new String[0]);
                Utility.createLauncherShortcut(a, intent, Icon.createWithBitmap(icon),
                        BuildConfig.APPLICATION_ID + ".launch." + app.getPackageName(), app.getLabel());
                a.toast("Ярлык «" + app.getLabel() + "» отправлен на рабочий стол. Если его нет, разреши Pocket ярлыки в настройках приложения.");
            } catch (Exception e) {
                a.toast("Не удалось создать ярлык. Открой Pocket снова.");
            }
        });
    }

    /** Re-copies the personal version over the copy; its data inside Pocket stays. */
    private void update(ApplicationInfoWrapper copy) {
        if (a.mServiceMain == null) return;
        a.toast("Ищем личную версию «" + copy.getLabel() + "»…", true);
        loadApps(a.mServiceMain, apps -> {
            for (ApplicationInfoWrapper app : apps) {
                if (app.getPackageName().equals(copy.getPackageName())) { install(app, "Копия обновлена"); return; }
            }
            a.toast("В личном профиле нет «" + copy.getLabel() + "». Обнови через «Установить APK-файл».");
        }, () -> a.toast("Не удалось загрузить личные приложения."));
    }

    private void confirmRemove(ApplicationInfoWrapper app) {
        new AlertDialog.Builder(a)
                .setTitle("Удалить копию «" + app.getLabel() + "»?")
                .setMessage(app.isSystem()
                        ? "Системная копия будет скрыта из Pocket; её данные в Pocket сохранятся до повторного добавления. Личное приложение не изменится."
                        : "Копия и её данные в Pocket будут удалены. Личное приложение и его данные сохранятся.")
                .setPositiveButton("Удалить копию", (d, w) -> {
                    a.toast("Удаляем копию…", true);
                    try { a.mServiceWork.uninstallApp(app, callback(app, false, "Копия удалена")); }
                    catch (Exception e) { a.toast("Удаление не выполнено. Открой Pocket снова."); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void install(ApplicationInfoWrapper app, String success) {
        a.toast("Копируем «" + app.getLabel() + "» в Pocket…", true);
        try { a.mServiceWork.installApp(app, callback(app, true, success)); }
        catch (Exception e) { a.toast("Копия не создана. Рабочий профиль недоступен."); }
    }

    private IAppInstallCallback.Stub callback(ApplicationInfoWrapper app, boolean install, String success) {
        return new IAppInstallCallback.Stub() {
            @Override public void callback(int result) {
                AuthenticationUtility.requireTrustedBinderCaller();
                a.post(() -> {
                    if (result == Activity.RESULT_OK) {
                        if (app.isSystem()) markSystemCopy(app, install);
                        a.toast(success + ": «" + app.getLabel() + "».");
                        reload();
                    } else if (result == ShelterService.RESULT_CANNOT_INSTALL_SYSTEM_APP) {
                        a.toast(install ? "Это системное приложение нельзя включить в Pocket на этом телефоне." : "Системную копию нельзя удалить.");
                    } else if (result == ShelterService.RESULT_PROTECTION_REQUIRED) {
                        a.toast("Сначала включи защиту: нажми карточку «Прокси».");
                    } else if (result == ShelterService.RESULT_NO_SPACE) {
                        a.toast("На телефоне мало места. Освободи память и повтори.");
                    } else {
                        a.toast(install ? "Копирование не выполнено или отменено." : "Удаление не выполнено или отменено.");
                    }
                });
            }
        };
    }

    // ---- Picker of personal apps ----

    private void showPicker() {
        if (a.mServiceMain == null || a.mServiceWork == null) { a.toast(a.linkProblem); return; }
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(16));
        LayoutInflater.from(a).inflate(R.layout.view_sheet_header, body, true);
        ((TextView) body.findViewById(R.id.sheet_title)).setText("Добавить приложение");
        // The default EditText style is what makes the field focusable and editable; only the look is ours.
        EditText search = new EditText(a);
        search.setBackgroundResource(R.drawable.bg_field);
        search.setTextSize(15);
        search.setTextColor(a.color(R.color.ink));
        search.setHintTextColor(a.color(R.color.hint));
        search.setHint("Поиск");
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = a.dp(12);
        body.addView(search, sp);
        LinearLayout apk = new LinearLayout(a);
        apk.setOrientation(LinearLayout.VERTICAL);
        apk.setBackgroundResource(R.drawable.bg_group);
        apk.setClipToOutline(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.bottomMargin = a.dp(12);
        body.addView(apk, ap);
        RowView apkRow = new RowView(a);
        apkRow.setIcon(R.drawable.ic_download, a.color(R.color.teal), 0xFFFFFFFF)
                .setTitle("Установить APK-файл").setCaption("Только в Pocket, личный профиль не меняется");
        apkRow.setOnClickListener(v -> { if (sheet != null) sheet.dismiss(); a.selectApk(); });
        apk.addView(apkRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout group = new LinearLayout(a);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_group);
        group.setClipToOutline(true);
        body.addView(group, new LinearLayout.LayoutParams(-1, -2));
        ProgressBar progress = new ProgressBar(a);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = a.dp(12);
        body.addView(progress, pp);

        NestedScrollView scroll = new NestedScrollView(a);
        scroll.addView(body);
        sheet = new BottomSheetDialog(a);
        sheet.setContentView(scroll);
        BottomSheetDialog current = sheet;
        body.findViewById(R.id.sheet_close).setOnClickListener(v -> current.dismiss());
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.show();

        loadApps(a.mServiceMain, apps -> {
            progress.setVisibility(View.GONE);
            List<String> present = new ArrayList<>();
            for (ApplicationInfoWrapper copy : copies) present.add(copy.getPackageName());
            List<ApplicationInfoWrapper> personal = new ArrayList<>();
            // getApps already keeps only launchable or user-installed packages, so system apps like Gmail belong here too.
            for (ApplicationInfoWrapper app : apps) if (!present.contains(app.getPackageName())) personal.add(app);
            Runnable fill = () -> {
                group.removeAllViews();
                String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                for (ApplicationInfoWrapper app : personal) {
                    if (!q.isEmpty() && !app.getLabel().toLowerCase(Locale.ROOT).contains(q)) continue;
                    RowView row = appRow(group, app, a.mServiceMain);
                    if (app.isSystem()) row.setCaption("Системное: тот же APK, данные отдельные");
                    row.setOnClickListener(v -> { current.dismiss(); install(app, "Готово, копия создана"); });
                }
                if (group.getChildCount() == 0) {
                    RowView empty = new RowView(a);
                    empty.setTitle(personal.isEmpty() ? "Все личные приложения уже скопированы" : "Ничего не найдено");
                    group.addView(empty);
                }
            };
            fill.run();
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { fill.run(); }
            });
        }, () -> { progress.setVisibility(View.GONE); a.toast("Не удалось загрузить личные приложения."); });
    }

    @Override void onHide() { if (sheet != null) sheet.dismiss(); }
}
