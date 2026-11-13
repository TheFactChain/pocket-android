package net.typeblog.shelter.ui;

import android.content.Intent;
import android.widget.LinearLayout;

import com.google.android.material.switchmaterial.SwitchMaterial;

import net.typeblog.shelter.BuildConfig;
import net.typeblog.shelter.R;
import net.typeblog.shelter.shop.ShopCatalog;

/** «Настройки» sheet: routing, support and about, each one row (updates have their own card on the home screen). */
final class MorePage extends Page {
    private SwitchMaterial directRu;
    private boolean applying;

    MorePage(MainActivity activity) { super(activity); }

    @Override String title() { return "Настройки"; }

    @Override void build() {
        LinearLayout routing = group();
        RowView ru = row(routing, R.drawable.ic_route, R.color.green, "Российские сайты напрямую", "Зарубежные всегда через прокси");
        directRu = new SwitchMaterial(a);
        directRu.setContentDescription("Российские сайты напрямую");
        directRu.setChecked(false);
        ru.setEnd(directRu);
        ru.setOnClickListener(v -> directRu.toggle());
        directRu.setOnCheckedChangeListener((b, on) -> {
            if (applying) return;
            if (a.state == null) { applying = true; directRu.setChecked(!on); applying = false; a.toast(a.linkProblem); return; }
            if (a.state.optBoolean("directRu", false) == on) return;
            a.toast(on ? "Российские сайты пойдут напрямую…" : "Весь интернет копий пойдёт через прокси…", true);
            a.remote(() -> { a.mServiceWork.setDirectRu(on); return null; },
                    x -> { a.toast("Готово, прокси перезапущен."); a.refreshState(); },
                    "Не удалось изменить маршрут. Открой Pocket снова.");
        });

        LinearLayout help = group();
        row(help, R.drawable.ic_chat, R.color.accent, "Тех. поддержка", "Ответим в Telegram")
                .setOnClickListener(v -> a.openUrl(ShopCatalog.supportUrl()));
        row(help, R.drawable.ic_info, R.color.violet, "О Pocket", "Версия, лицензии")
                .setOnClickListener(v -> a.startActivity(new Intent(a, SettingsActivity.class)));
    }

    @Override void onState() {
        view();
        if (a.state != null) {
            applying = true;
            directRu.setChecked(a.state.optBoolean("directRu", false));
            applying = false;
        }
    }
}
