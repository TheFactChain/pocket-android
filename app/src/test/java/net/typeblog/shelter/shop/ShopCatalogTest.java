package net.typeblog.shelter.shop;

import static org.junit.Assert.*;

import org.junit.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class ShopCatalogTest {
    private static String p(String id, String price, String extra) {
        return "{\"id\":\"" + id + "\",\"title\":\"T\",\"price_rub\":" + price + extra + "}";
    }

    private static String cat(String... items) { return "{\"products\":[" + String.join(",", items) + "]}"; }

    @Test public void builtInAssetParses() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/shop-catalog.json")), StandardCharsets.UTF_8);
        List<ShopCatalog.Item> items = ShopCatalog.parse(json);
        assertNotNull(items);
        assertTrue(items.size() > 0 && items.size() <= 12);
    }

    @Test public void rejectsBadInput() {
        assertNull(ShopCatalog.parse(null));
        assertNull(ShopCatalog.parse("не json"));
        assertNull(ShopCatalog.parse("{}"));
        assertNull(ShopCatalog.parse(cat(p("a", "\"100\"", ""))));
        assertNull(ShopCatalog.parse(cat(p("a", "true", ""))));
        assertNull(ShopCatalog.parse(cat(p("A_b", "100", ""))));
        assertNull(ShopCatalog.parse(cat(p("a", "0", ""))));
        assertNull(ShopCatalog.parse(cat(p("a", "1000000", ""))));
    }

    @Test public void duplicateIdSkipped() {
        assertEquals(1, ShopCatalog.parse(cat(p("a", "1", ""), p("a", "2", ""))).size());
    }

    @Test public void truncatesToTwelve() {
        String[] items = new String[15];
        for (int i = 0; i < 15; i++) items[i] = p("p" + i, "10", "");
        assertEquals(12, ShopCatalog.parse(cat(items)).size());
    }

    @Test public void colors() {
        List<ShopCatalog.Item> r = ShopCatalog.parse(cat(
                p("a", "10", ",\"color\":\"#00ff00\""), p("b", "10", ",\"color\":\"red\""),
                p("c", "10", ",\"icon\":\"claude\""), p("d", "10", ",\"icon\":\"claude\",\"color\":\"#12345G\"")));
        assertEquals("#00ff00", r.get(0).color);
        assertEquals("#3390EC", r.get(1).color);
        assertEquals("#D97757", r.get(2).color);
        assertEquals("#D97757", r.get(3).color);
    }

    @Test public void cleansAndLine() {
        ShopCatalog.Item i = ShopCatalog.parse(cat(
                "{\"id\":\"a\",\"title\":\" Ti\\u0000tle \",\"price_rub\":99.504,\"description\":\"D\",\"delivery\":\"1 день\"}")).get(0);
        assertEquals("Title", i.title);
        assertEquals(new BigDecimal("99.50"), i.price);
        assertEquals("D, 1 день", i.line());
        ShopCatalog.Item sentence = ShopCatalog.parse(cat(
                "{\"id\":\"b\",\"title\":\"T\",\"price_rub\":1,\"description\":\"Карта не нужна.\",\"delivery\":\"до 30 минут\"}")).get(0);
        assertEquals("Карта не нужна. До 30 минут", sentence.line());
    }

    @Test public void money() {
        assertEquals("2 100 ₽", ShopCatalog.money(new BigDecimal("2100.00")));
        assertEquals("99,50 ₽", ShopCatalog.money(new BigDecimal("99.5")));
    }

    @Test public void urls() {
        assertEquals("https://t.me/FactChainStore_bot?start=support", ShopCatalog.supportUrl());
        assertEquals("https://t.me/FactChainStore_bot?start=buy_proxy__noconn", ShopCatalog.botUrl("proxy", ShopCatalog.FROM_NO_CONNECTION));
        assertNull(ShopCatalog.botUrl("Bad id", "home"));
        assertNull(ShopCatalog.botUrl(null, "home"));
        assertEquals(ShopCatalog.botUrl("proxy", "noconn"), ShopCatalog.buyProxyUrl());
    }
}
