package net.typeblog.shelter.ui;

import android.graphics.Color;
import android.widget.LinearLayout;

import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

import java.util.List;

/** «Магазин»: the catalog of Shop.cs; a row opens the bot with the item. */
final class ShopPage extends Page {
    private LinearLayout items;

    ShopPage(MainActivity activity) { super(activity); }

    @Override String title() { return "Магазин"; }

    @Override void build() {
        items = group();
        render(ShopCatalog.load(a));
        caption(content, "Покупка и оплата проходят в Telegram-боте. Pocket не получает данные оплаты.");
        a.worker.execute(() -> {
            try {
                List<ShopCatalog.Item> fresh = ShopCatalog.fetch(a);
                a.post(() -> render(fresh));
            } catch (Exception ignored) { /* the built-in or cached catalog stays */ }
        });
    }

    private void render(List<ShopCatalog.Item> list) {
        items.removeAllViews();
        for (ShopCatalog.Item item : list) {
            RowView row = row(items, R.drawable.ic_bag, R.color.accent, item.title, item.line());
            try { row.setTile(Color.parseColor(item.color)); } catch (RuntimeException ignored) { }
            row.setPill(item.priceText(), a.color(R.color.blue_soft), a.color(R.color.blue_ink));
            row.setOnClickListener(v -> a.openUrl(ShopCatalog.botUrl(item.id, ShopCatalog.FROM_HOME)));
        }
    }
}
