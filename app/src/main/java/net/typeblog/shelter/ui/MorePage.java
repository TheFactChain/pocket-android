package net.typeblog.shelter.ui;

import android.content.Intent;
import android.widget.LinearLayout;

import com.google.android.material.switchmaterial.SwitchMaterial;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

/** «Настройки» tab: routing, support, updates and about, each one row. */
final class MorePage extends Page {
    private SwitchMaterial directRu;
    private RowView updates;
    private boolean applying;

    MorePage(MainActivity activity) { super(activity); }

    @Override String title() { return "Настройки"; }

    @Override void build() {
        LinearLayout routing = group();
        RowView ru = row(routing, R.drawable.ic_route, R.color.green, "Российские сайты напрямую", "Зарубежные всегда через прокси");
        directRu = new SwitchMaterial(a);
        directRu.setContentDescription("Российские сайты напрямую");
        directRu.setChecked(true);
        ru.setEnd(directRu);
        ru.setOnClickListener(v -> directRu.toggle());
        directRu.setOnCheckedChangeListener((b, on) -> {
            if (applying) return;
            if (a.state == null) { applying = true; directRu.setChecked(!on); applying = false; a.toast(a.linkProblem); return; }
            if (a.state.optBoolean("directRu", true) == on) return;
            a.toast(on ? "Российские сайты пойдут напрямую…" : "Весь интернет копий пойдёт через прокси…", true);
            a.remote(() -> { a.mServiceWork.setDirectRu(on); return null; },
                    x -> { a.toast("Готово, прокси перезапущен."); a.refreshState(); },
                    "Не удалось изменить маршрут. Открой Pocket снова.");
        });

        LinearLayout help = group();
        row(help, R.drawable.ic_chat, R.color.accent, "Тех. поддержка", "Ответим в Telegram")
                .setOnClickListener(v -> a.openUrl(ShopCatalog.supportUrl()));
        updates = row(help, R.drawable.ic_download, R.color.teal, "Обновления", "Pocket " + BuildConfig.VERSION_NAME);
        updates.setOnClickListener(v -> { dismissSheet(); new UpdatesPage(a).showAsSheet(); });
        row(help, R.drawable.ic_info, R.color.violet, "О Pocket", "Версия, лицензии")
                .setOnClickListener(v -> a.startActivity(new Intent(a, SettingsActivity.class)));
    }

    @Override void onState() {
        view();
        if (a.state != null) {
            applying = true;
            directRu.setChecked(a.state.optBoolean("directRu", true));
            applying = false;
        }
        if (a.updateOffer != null) updates.setPill("Доступно", a.color(R.color.green_soft), a.color(R.color.green_ink));
        else updates.setEnd(null);
    }
}
