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
    private TextView headline, proxyValue, proxyDetail, proxyAction, shopPrice;
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
        shopPrice = root.findViewById(R.id.shop_price);
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
        shopPrice.setText(proxyPrice());

        tappable(root.findViewById(R.id.home_settings), settings::showAsSheet);
        apps.bind(root.findViewById(R.id.card_apps));
        return root;
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

    private String proxyPrice() {
        for (ShopCatalog.Item item : ShopCatalog.load(a)) if (item.id.equals("proxy")) return item.priceText();
        return "Proxy";
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
