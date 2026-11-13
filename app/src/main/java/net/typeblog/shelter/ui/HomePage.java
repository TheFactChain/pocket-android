package net.typeblog.shelter.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The only screen: what matters is always at hand. Proxy and updates are two small cards, the shop is the wide card
 * in the middle, and the apps card below holds the copies. Proxy, updates, settings and shop open as sheets.
 */
final class HomePage extends Page {
    final AppsPage apps;
    private final ShopPage shop;
    private final MorePage settings;
    private TextView proxyValue, proxyDetail, proxyAction, updatesValue, updatesDetail;
    private LinearLayout shopChips;
    private ImageView proxyIcon;
    private GradientDrawable proxyFill, updatesFill;
    private int proxyColor;
    private PocketStatus status;



    HomePage(MainActivity activity) {
        super(activity);
        apps = new AppsPage(activity);
        shop = new ShopPage(activity);
        settings = new MorePage(activity);

    }

    @Override View create(LayoutInflater inflater) {
        View root = inflater.inflate(R.layout.page_home, null);
        proxyValue = root.findViewById(R.id.proxy_value);
        proxyDetail = root.findViewById(R.id.proxy_detail);
        proxyAction = root.findViewById(R.id.proxy_action);
        proxyIcon = root.findViewById(R.id.proxy_icon);
        updatesValue = root.findViewById(R.id.updates_value);
        updatesDetail = root.findViewById(R.id.updates_detail);
        shopChips = root.findViewById(R.id.shop_chips);

        View proxyCard = root.findViewById(R.id.card_proxy);
        proxyColor = a.color(R.color.pastel_lavender);
        proxyFill = fill(proxyColor);
        proxyCard.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), proxyFill, null));
        tappable(proxyCard, a::openConnections);
        proxyAction.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (status != null) Fixes.run(a, status.action);
        });

        View updatesCard = root.findViewById(R.id.card_updates);
        updatesFill = fill(a.color(R.color.pastel_blue));
        updatesCard.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), updatesFill, null));
        tappable(updatesCard, () -> new UpdatesPage(a).showAsSheet());

        // Sunny diagonal gradient: the one card that is meant to catch the eye.
        GradientDrawable sun = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { a.color(R.color.pastel_yellow), a.color(R.color.pastel_orange), a.color(R.color.pastel_pink) });
        sun.setCornerRadius(a.dp(28));
        View shopCard = root.findViewById(R.id.card_shop);
        shopCard.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), sun, null));
        shopCard.setClipToOutline(true);
        tappable(shopCard, shop::showAsSheet);
        showCatalog(ShopCatalog.load(a));
        a.worker.execute(() -> {
            try {
                List<ShopCatalog.Item> fresh = ShopCatalog.fetch(a);
                a.post(() -> showCatalog(fresh));
            } catch (Exception ignored) { /* the built-in or cached catalog stays */ }
        });

        tappable(root.findViewById(R.id.home_settings), settings::showAsSheet);

        apps.bind(root.findViewById(R.id.card_apps));
        return root;
    }


    static void checkExitIp(MainActivity a) {
        a.remote(() -> { a.mServiceWork.checkExitIp(); return null; },
                x -> { a.toast("Проверяем внешний IP…"); a.refreshState(); }, "Не удалось запустить проверку IP.");
    }

    // ---- Formatting shared by the home card, the indicators sheet and the diagnostics ----

    static String time(long ms) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms)); }

    /** Text after «Внешний IP (выход): ». Never the server address, only what the service confirmed. */
    static String exitValue(JSONObject s) {
        if (s == null) return "не проверен";
        String ip = s.optString("exitIp");
        if (!ip.isEmpty()) return ip + (s.optLong("exitIpTime") > 0 ? ", проверен " + time(s.optLong("exitIpTime")) : "");
        String st = s.optString("exitStatus");
        return "CHECKING".equals(st) ? "проверяем…" : "UNAVAILABLE".equals(st) ? "сервис проверки недоступен" : "не проверен";
    }

    /** «IP чистый», «IP похож на VPN»… from the first rating; without one, whether the exit is known. */
    static String exitVerdict(JSONObject s) {
        JSONArray list = s == null ? null : s.optJSONArray("ratings");
        JSONObject r = list == null || list.length() == 0 ? null : list.optJSONObject(0);
        String risk = r == null ? "" : r.optString("risk");
        if (risk.equals("low")) return "IP чистый";
        if (risk.equals("medium")) return "IP под подозрением";
        if (risk.equals("high")) return "IP похож на VPN";
        if (s != null && "CHECKING".equals(s.optString("exitStatus"))) return "Проверяем IP…";
        return s != null && !s.optString("exitIp").isEmpty() ? "IP без оценки" : "IP не проверен";
    }

    static String pingText(JSONObject s) {
        long ping = s == null ? 0 : s.optLong("selectedPing");
        return ping > 0 ? ping + " мс" : ping < 0 ? "нет ответа" : "не проверен";
    }

    static String rate(long v) {
        if (v < 1024) return v + " Б/с";
        if (v < 1048576) return Math.round(v / 1024.0) + " КБ/с";
        return String.format(Locale.ROOT, "%.1f МБ/с", v / 1048576.0).replace('.', ',');
    }

    static String size(long v) { return v < 1048576 ? Math.round(v / 1024.0) + " КБ" : ConnectionsPage.bytes(v); }

    static String trafficText(JSONObject s) {
        if (s == null || !s.has("uplinkTotal") && !s.has("downlinkTotal")) return "—";
        return "получено " + size(s.optLong("downlinkTotal")) + ", отправлено " + size(s.optLong("uplinkTotal"));
    }

    /** Remaining traffic and days of the subscription the selected server belongs to; «—» when it has none. */
    static String subscriptionText(MainActivity a) {
        try {
            String id = a.state == null ? "" : a.state.optString("selectedId");
            String sub = "";
            for (int i = 0; !id.isEmpty() && i < a.connectionList.length(); i++) {
                JSONObject c = a.connectionList.optJSONObject(i);
                if (c != null && id.equals(c.optString("id"))) sub = c.optString("subscription");
            }
            if (sub.isEmpty()) return "—";
            for (int i = 0; i < a.subscriptionList.length(); i++) {
                JSONObject o = a.subscriptionList.optJSONObject(i);
                if (o == null || !sub.equals(o.optString("id"))) continue;
                StringBuilder sb = new StringBuilder(o.optString("title"));
                long total = o.optLong("total");
                if (total > 0) sb.append(sb.length() > 0 ? ": " : "").append("осталось ")
                        .append(ConnectionsPage.bytes(Math.max(0, total - o.optLong("upload") - o.optLong("download"))));
                long expire = o.optLong("expire");
                if (expire > 0) {
                    long days = (expire * 1000 - System.currentTimeMillis()) / 86400000L;
                    sb.append(sb.length() > 0 ? ", " : "").append(days >= 0 ? days + " дн." : "срок истёк");
                }
                return sb.length() == 0 ? "—" : sb.toString();
            }
        } catch (RuntimeException ignored) { /* shown as unknown */ }
        return "—";
    }


    /** Full list of indicators with the ratings (reference only). */
    static final class MetricsPage extends Page {
        private RowView server, exit, ping, traffic, sub, last;
        private LinearLayout ratings;

        MetricsPage(MainActivity activity) { super(activity); }

        @Override String title() { return "Показатели"; }

        @Override void build() {
            LinearLayout g = group();
            server = row(g, R.drawable.ic_globe, R.color.accent, "Сервер", null);
            exit = row(g, R.drawable.ic_shield, R.color.green, "Внешний IP (выход)", null);
            exit.setOnClickListener(v -> checkExitIp(a));
            ping = row(g, R.drawable.ic_info, R.color.accent, "Пинг", null);
            traffic = row(g, R.drawable.ic_download, R.color.accent, "Трафик за сеанс", null);
            sub = row(g, R.drawable.ic_key, R.color.violet, "Подписка", null);
            last = row(g, R.drawable.ic_check, R.color.green, "Последняя проверка", null);
            ratings = group();
            caption(content, "Оценку IP дают сторонние сервисы, это не гарантия. Диагностика — в настройках.");
        }

        @Override void onState() {
            view();
            JSONObject s = a.state;
            String name = s == null ? "" : s.optString("selectedName");
            server.setCaption(name.isEmpty() ? "не выбран" : name);
            boolean unknown = s == null || s.optString("exitIp").isEmpty();
            exit.setCaption(exitValue(s) + (unknown ? ". Нажми, чтобы проверить" : ""));
            ping.setCaption(pingText(s));
            traffic.setCaption(trafficText(s));
            sub.setCaption(subscriptionText(a));
            long t = s == null ? 0 : s.optLong("lastCheckTime");
            last.setCaption(t > 0 ? time(t) : "не проверено");
            ratings.removeAllViews();
            JSONArray list = s == null ? null : s.optJSONArray("ratings");
            if (list == null || list.length() == 0) { row(ratings, R.drawable.ic_shield, R.color.accent, "Оценка IP", "Появится после проверки IP"); return; }
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.optJSONObject(i);
                if (r == null) continue;
                RowView row = row(ratings, R.drawable.ic_shield, R.color.accent, "Оценка IP · " + r.optString("source", "сервис"), r.optString("summary", "—"));
                String risk = r.optString("risk");
                boolean low = risk.equals("low"), mid = risk.equals("medium"), high = risk.equals("high");
                row.setPill(ConnectionsPage.ratingPill(r),
                        a.color(low ? R.color.green_soft : mid ? R.color.orange_soft : high ? R.color.danger_soft : R.color.grey_soft),
                        a.color(low ? R.color.green_ink : mid ? R.color.orange_ink : high ? R.color.danger : R.color.muted));
            }
        }
    }

    /** Runs the step-by-step check and shows each step with a mark and a word; the report has no secrets. */
    static final class DiagnosticsPage extends Page {
        private LinearLayout steps;
        private String report = "";

        DiagnosticsPage(MainActivity activity) { super(activity); }

        @Override String title() { return "Диагностика"; }

        @Override void build() {
            steps = group();
            content.addView(textAction("Скопировать отчёт", v -> {
                if (report.isEmpty()) { a.toast("Отчёта пока нет."); return; }
                android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) { cm.setPrimaryClip(android.content.ClipData.newPlainText("Pocket", report)); a.toast("Отчёт скопирован."); }
            }), new LinearLayout.LayoutParams(-2, -2));
        }

        @Override void onShow() {
            steps.removeAllViews();
            report = "";
            row(steps, R.drawable.ic_info, R.color.accent, "Проверяем…", "Это займёт несколько секунд");
            a.remote(() -> new JSONObject(a.mServiceWork.diagnose()), this::show, "Не удалось выполнить диагностику.");
        }

        private void show(JSONObject result) {
            steps.removeAllViews();
            report = result.optString("report");
            JSONArray list = result.optJSONArray("steps");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject st = list.optJSONObject(i);
                if (st == null) continue;
                boolean known = st.has("ok") && !st.isNull("ok");
                boolean ok = known && st.optBoolean("ok");
                RowView row = row(steps, known ? (ok ? R.drawable.ic_check : R.drawable.ic_warning) : R.drawable.ic_info,
                        known ? (ok ? R.color.green : R.color.orange) : R.color.accent, st.optString("title"), st.optString("detail"));
                row.setPill(known ? (ok ? "✓ Успешно" : "✗ Сбой") : "Не проверено",
                        a.color(known ? (ok ? R.color.green_soft : R.color.danger_soft) : R.color.grey_soft),
                        a.color(known ? (ok ? R.color.green_ink : R.color.danger) : R.color.muted));
            }
            if (steps.getChildCount() == 0) row(steps, R.drawable.ic_info, R.color.accent, "Нет данных", "Служба не вернула шагов");
        }
    }

    /** One chip per kind of goods in the catalog (Proxy first): what is sold, not prices. */
    private void showCatalog(List<ShopCatalog.Item> list) {
        if (list == null) return;
        List<ShopCatalog.Item> kinds = new ArrayList<>();
        List<Integer> glyphs = new ArrayList<>();
        for (ShopCatalog.Item item : list) {
            int glyph = ShopPage.glyph(item);
            if (glyphs.contains(glyph)) continue;
            int at = glyph == R.drawable.ic_shield ? 0 : glyphs.size();
            glyphs.add(at, glyph);
            kinds.add(at, item);
        }
        shopChips.removeAllViews();
        for (int i = 0; i < Math.min(3, kinds.size()); i++) shopChips.addView(chip(glyphs.get(i), ShopPage.name(kinds.get(i))));
    }

    private View chip(int glyph, String name) {
        LinearLayout chip = new LinearLayout(a);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(a.dp(6), a.dp(5), a.dp(12), a.dp(5));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xB3FFFFFF);
        glass.setCornerRadius(a.dp(18));
        chip.setBackground(glass);
        ImageView icon = new ImageView(a);
        icon.setImageResource(glyph);
        icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        icon.setBackgroundResource(R.drawable.bg_circle);
        icon.setBackgroundTintList(ColorStateList.valueOf(a.color(R.color.ink)));
        icon.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(5));
        chip.addView(icon, new LinearLayout.LayoutParams(a.dp(26), a.dp(26)));
        TextView text = new TextView(a);
        text.setText(name);
        text.setTextColor(a.color(R.color.ink));
        text.setTextSize(13.5f);
        text.setTypeface(null, Typeface.BOLD);
        text.setMaxLines(1);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(a.dp(7));
        chip.addView(text, tp);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (shopChips.getChildCount() > 0) params.setMarginStart(a.dp(8));
        chip.setLayoutParams(params);
        return chip;
    }

    private GradientDrawable fill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(a.dp(28));
        return shape;
    }

    private void tappable(View view, Runnable action) {
        view.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(a, R.animator.tap));
        view.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action.run(); });
    }

    @Override void onState() {
        view();
        status = PocketStatus.of(a.state, a.linkProblem);
        proxyValue.setText(status.word);
        proxyDetail.setText(status.detail);
        proxyAction.setText(status.actionLabel);
        proxyAction.setVisibility(status.actionLabel == null || status.action == PocketStatus.Fix.NONE ? View.GONE : View.VISIBLE);
        proxyIcon.setImageResource(status.tone == PocketStatus.Tone.GOOD ? R.drawable.ic_check
                : status.tone == PocketStatus.Tone.BUSY ? R.drawable.ic_globe : R.drawable.ic_warning);
        int target = a.color(status.tone == PocketStatus.Tone.GOOD ? R.color.pastel_green
                : status.tone == PocketStatus.Tone.BUSY ? R.color.pastel_lavender : R.color.pastel_orange);
        if (target != proxyColor) {
            // The card fades to its new colour instead of jumping.
            ValueAnimator fade = ValueAnimator.ofObject(new ArgbEvaluator(), proxyColor, target).setDuration(420);
            fade.addUpdateListener(an -> proxyFill.setColor((int) an.getAnimatedValue()));
            fade.start();
            proxyColor = target;
        }
        boolean offered = a.updateOffer != null;
        updatesValue.setText(offered ? "Доступно" : BuildConfig.VERSION_NAME);
        updatesDetail.setText(offered ? "Версия " + a.updateOffer.versionName + ", нажми, чтобы обновить"
                : a.updateLatest ? "Последняя версия" : "Нажми, чтобы проверить");
        updatesFill.setColor(a.color(offered ? R.color.pastel_green : R.color.pastel_blue));
        apps.setStatus(status);

    }
}
