package net.typeblog.shelter.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

/**
 * The only screen: what matters is always at hand. A headline says the state, the proxy card shows it in one word,
 * the shop card sells the proxy, and the apps card holds the copies. Proxy, settings and shop open as sheets.
 */
final class HomePage extends Page {
    final AppsPage apps;
    private final ShopPage shop;
    private final MorePage settings;
    private TextView headline, proxyValue, proxyDetail, proxyAction, shopValue;
    private android.widget.LinearLayout shopIcons;
    private View settingsDot;
    private android.widget.ImageView proxyIcon;
    private GradientDrawable proxyFill;
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
        headline = root.findViewById(R.id.home_headline);
        proxyValue = root.findViewById(R.id.proxy_value);
        proxyDetail = root.findViewById(R.id.proxy_detail);
        proxyAction = root.findViewById(R.id.proxy_action);
        settingsDot = root.findViewById(R.id.home_settings_dot);
        proxyIcon = root.findViewById(R.id.proxy_icon);

        View proxyCard = root.findViewById(R.id.card_proxy);
        proxyColor = a.color(R.color.pastel_lavender);
        proxyFill = fill(proxyColor);
        proxyCard.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), proxyFill, null));
        tappable(proxyCard, a::openConnections);
        proxyAction.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (status != null) Fixes.run(a, status.action);
        });

        View shopCard = root.findViewById(R.id.card_shop);
        shopCard.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), fill(a.color(R.color.pastel_yellow)), null));
        tappable(shopCard, shop::showAsSheet);
        shopValue = root.findViewById(R.id.shop_value);
        shopIcons = root.findViewById(R.id.shop_icons);
        showCatalog(ShopCatalog.load(a));
        a.worker.execute(() -> {
            try {
                java.util.List<ShopCatalog.Item> fresh = ShopCatalog.fetch(a);
                a.post(() -> showCatalog(fresh));
            } catch (Exception ignored) { /* the built-in or cached catalog stays */ }
        });

        tappable(root.findViewById(R.id.home_settings), settings::showAsSheet);
        apps.bind(root.findViewById(R.id.card_apps));
        return root;
    }

    /** The shop card says what is sold, not prices: names and glyphs of the catalog, Proxy first. */
    private void showCatalog(java.util.List<ShopCatalog.Item> list) {
        if (list == null) return;
        java.util.List<Integer> glyphs = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ShopCatalog.Item item : list) {
            int glyph = ShopPage.glyph(item);
            if (glyphs.contains(glyph)) continue;
            int at = glyph == R.drawable.ic_shield ? 0 : glyphs.size();
            glyphs.add(at, glyph);
            names.add(at, ShopPage.name(item));
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) text.append(i == names.size() - 1 ? " и " : ", ");
            text.append(names.get(i));
        }
        shopValue.setText(text);
        for (int i = 0; i < shopIcons.getChildCount(); i++) {
            android.widget.ImageView icon = (android.widget.ImageView) shopIcons.getChildAt(i);
            icon.setVisibility(i < glyphs.size() ? View.VISIBLE : View.GONE);
            if (i < glyphs.size()) icon.setImageResource(glyphs.get(i));
        }
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
        headline.setText(status.headline);
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
        settingsDot.setVisibility(a.updateOffer != null ? View.VISIBLE : View.GONE);
        apps.setStatus(status);
    }
}
