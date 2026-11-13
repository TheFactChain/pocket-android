package net.typeblog.shelter.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class FlagTest {
    @Test public void firstCountryFlagIsTakenFromTheServerName() {
        assertEquals("🇺🇸", ConnectionsPage.flag("USA 🇺🇸 (Новые блокировки)"));
        assertEquals("🇦🇹", ConnectionsPage.flag("🇦🇹 Austria 🇩🇪"));
        assertEquals("", ConnectionsPage.flag("Мой сервер"));
        assertEquals("A lone regional letter is not a flag", "", ConnectionsPage.flag("x 🇺"));
        assertEquals("", ConnectionsPage.flag(""));
    }
}
