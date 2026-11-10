package net.typeblog.shelter.ui;

import android.widget.LinearLayout;

import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

import java.util.List;

/** «Магазин»: the catalog of Shop.cs; a row opens the bot with the item. Closes by a tap outside or a swipe. */
final class ShopPage extends Page {
    private LinearLayout items;

    ShopPage(MainActivity activity) { super(activity); }

    @Override String title() { return "Магазин"; }

    @Override boolean closeButton() { return false; }

    @Override void build() {
        items = group();
        render(ShopCatalog.load(a));
        a.worker.execute(() -> {
            try {
                List<ShopCatalog.Item> fresh = ShopCatalog.fetch(a);
                a.post(() -> render(fresh));
            } catch (Exception ignored) { /* the built-in or cached catalog stays */ }
        });
    }

    private void render(List<ShopCatalog.Item> list) {
        items.removeAllViews();
        if (list == null) return;
        for (ShopCatalog.Item item : list) {
            RowView row = row(items, 0, R.color.accent, item.title, item.line());
            row.setIcon(glyph(item), a.color(tile(item)), a.color(R.color.ink));
            row.setPill(item.priceText(), a.color(R.color.blue_soft), a.color(R.color.blue_ink));
            row.setContentDescription(item.title + ", " + item.priceText());
            row.setOnClickListener(v -> a.openUrl(ShopCatalog.botUrl(item.id, ShopCatalog.FROM_HOME)));
        }
    }

    /** The catalog names Windows (Fluent) symbols; each maps to the nearest Pocket glyph. */
    static int glyph(ShopCatalog.Item item) {
        if (item.icon.equals("claude")) return R.drawable.ic_claude;
        if (item.icon.startsWith("Bot")) return R.drawable.ic_bot;
        if (item.icon.startsWith("Shield") || item.id.equals("proxy")) return R.drawable.ic_shield;
        if (item.icon.startsWith("Key") || item.id.startsWith("windows")) return R.drawable.ic_key;
        return R.drawable.ic_bag;
    }

    /** The short word for the home card: «Proxy, ChatGPT и Windows». */
    static String name(ShopCatalog.Item item) {
        int glyph = glyph(item);
        return glyph == R.drawable.ic_shield ? "Proxy" : glyph == R.drawable.ic_bot ? "ChatGPT"
                : glyph == R.drawable.ic_claude ? "Claude" : glyph == R.drawable.ic_key ? "Windows" : item.title;
    }

    private static int tile(ShopCatalog.Item item) {
        int glyph = glyph(item);
        if (glyph == R.drawable.ic_shield) return R.color.pastel_green;
        if (glyph == R.drawable.ic_claude) return R.color.pastel_orange;
        if (glyph == R.drawable.ic_key) return R.color.pastel_yellow;
        if (glyph == R.drawable.ic_bot) return R.color.pastel_lavender;
        return R.color.pastel_blue;
    }
}
