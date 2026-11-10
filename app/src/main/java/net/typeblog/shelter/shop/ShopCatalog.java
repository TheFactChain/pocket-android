package net.typeblog.shelter.shop;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** Каталог магазина: встроенная копия, кэш, загрузка с сервера и ссылки на бота. Порт Shop.cs из Windows-лаунчера. */
public final class ShopCatalog {
    public static final String BOT_NAME = "FactChainStore_bot";
    public static final String CATALOG_URL = "https://shop.polove.ru/pocket-shop/v1/catalog.json";
    public static final String FROM_HOME = "home", FROM_CHECK = "check", FROM_NO_CONNECTION = "noconn";

    private static final String FILE = "shop-catalog.json";
    private static final int MAX_CHARS = 65536, MAX_ITEMS = 12;
    private static final Pattern ID = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern CONTROL = Pattern.compile("\\p{C}");
    private static final BigDecimal MAX_PRICE = new BigDecimal(1000000);

    private ShopCatalog() {}

    public static final class Item {
        public String id, title, description, term, icon, color;
        public BigDecimal price;

        public String priceText() { return money(price); }

        /** Строка описания: срок выдачи ("delivery") идёт после текста, если он есть. */
        public String line() {
            if (term.isEmpty()) return description;
            // A finished sentence gets the term as the next sentence: «…не нужны. До 30 минут».
            if (description.endsWith(".")) return description + " " + term.substring(0, 1).toUpperCase(Locale.ROOT) + term.substring(1);
            return description + ", " + term;
        }
    }

    /** 2100 -> "2 100 ₽", 99.5 -> "99,50 ₽"; пробелы неразрывные, цена не переносится. */
    public static String money(BigDecimal rubles) {
        boolean whole = rubles.stripTrailingZeros().scale() <= 0;
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator(' ');
        s.setDecimalSeparator(',');
        return new DecimalFormat(whole ? "#,##0" : "#,##0.00", s).format(rubles) + " ₽";
    }

    private static String clean(JSONObject o, String key, int limit) {
        String v = o.isNull(key) ? "" : o.opt(key).toString();
        v = CONTROL.matcher(v).replaceAll("").trim();
        return v.length() > limit ? v.substring(0, limit) : v;
    }

    /** Каталог приходит из сети: каждое поле проверяется, плохая запись пропускается, пустой результат даёт null. */
    public static List<Item> parse(String json) {
        try {
            if (json == null || json.length() > MAX_CHARS) return null;
            JSONArray list = new JSONObject(json).optJSONArray("products");
            if (list == null) return null;
            List<Item> result = new ArrayList<>();
            outer:
            for (int i = 0; i < Math.min(list.length(), MAX_ITEMS); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String id = clean(o, "id", 64), title = clean(o, "title", 40),
                        color = clean(o, "color", 7), icon = clean(o, "icon", 24);
                if (!ID.matcher(id).matches() || title.isEmpty()) continue;
                for (Item x : result) if (x.id.equals(id)) continue outer;
                // price_rub — только JSON-число; строка или булево пропускают запись
                Object raw = o.opt("price_rub");
                if (!(raw instanceof Number)) continue;
                BigDecimal price = new BigDecimal(raw.toString()).setScale(2, RoundingMode.HALF_EVEN);
                if (price.signum() <= 0 || price.compareTo(MAX_PRICE) >= 0) continue;
                Item it = new Item();
                it.id = id; it.title = title; it.price = price;
                it.description = clean(o, "description", 120);
                it.term = clean(o, "delivery", 40);
                it.icon = icon;
                it.color = COLOR.matcher(color).matches() ? color : icon.equals("claude") ? "#D97757" : "#3390EC";
                result.add(it);
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /** Кэш с сервера, иначе встроенная копия. Читает файлы: не вызывать из главного потока. */
    public static List<Item> load(Context context) {
        File cache = new File(context.getCacheDir(), FILE);
        try {
            if (cache.isFile()) {
                List<Item> cached = parse(readAll(new FileInputStream(cache)));
                if (cached != null) return cached;
            }
        } catch (Exception ignored) { } // нечитаемый кэш равен отсутствию кэша
        try {
            return parse(readAll(context.getAssets().open(FILE)));
        } catch (IOException e) {
            return null;
        }
    }

    /** Загрузка каталога с сервера и обновление кэша. Сеть: только вне главного потока. */
    public static List<Item> fetch(Context context) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(CATALOG_URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "PocketAndroid");
        String text;
        try {
            if (c.getResponseCode() != 200) throw new IOException("Каталог магазина недоступен.");
            text = readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
        List<Item> items = parse(text);
        if (items == null) throw new IOException("Каталог магазина не распознан.");
        try {
            File cache = new File(context.getCacheDir(), FILE), temp = new File(cache.getPath() + ".partial");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(text.getBytes("UTF-8")); }
            if (!temp.renameTo(cache)) { cache.delete(); temp.renameTo(cache); }
        } catch (Exception ignored) { } // кэш — удобство, каталог уже в руках
        return items;
    }

    // не более MAX_CHARS + 1 символа: лишний символ делает документ заведомо невалидным для parse
    private static String readAll(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, Charset.forName("UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while (sb.length() <= MAX_CHARS && (n = r.read(buf, 0, Math.min(buf.length, MAX_CHARS + 1 - sb.length()))) > 0) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    public static String supportUrl() { return "https://t.me/" + BOT_NAME + "?start=support"; }

    /** null для неверного id; id проверен по [a-z0-9-], источник фиксированный. */
    public static String botUrl(String id, String source) {
        if (id == null || !ID.matcher(id).matches()) return null;
        return "https://t.me/" + BOT_NAME + "?start=buy_" + id + "__" + source;
    }

    /** "Купить Proxy" без подключения: как в Windows, оффер "proxy" с источником noconn.
     *  Если в каталоге нет "proxy", Windows открывает магазин целиком; решает вызывающий. */
    public static String buyProxyUrl() { return botUrl("proxy", FROM_NO_CONNECTION); }
}
