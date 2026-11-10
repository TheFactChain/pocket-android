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

import java.util.ArrayList;
import java.util.List;

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
