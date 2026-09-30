package me.rothens.gpsexif.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemThemeTest {

    @Test
    void windows() {
        String header = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n";
        assertTrue(SystemTheme.isWindowsDark(header + "    AppsUseLightTheme    REG_DWORD    0x0\r\n"));
        assertFalse(SystemTheme.isWindowsDark(header + "    AppsUseLightTheme    REG_DWORD    0x1\r\n"));
        assertFalse(SystemTheme.isWindowsDark(null));
    }

    @Test
    void mac() {
        assertTrue(SystemTheme.isMacDark("Dark\n"));
        assertFalse(SystemTheme.isMacDark(null));
    }

    @Test
    void linux() {
        assertTrue(SystemTheme.isLinuxDark(null, "'prefer-dark'\n", null, null, null));
        assertTrue(SystemTheme.isLinuxDark(null, "'default'\n", "'Adwaita-dark'\n", null, null));
        assertTrue(SystemTheme.isLinuxDark(null, null, null, "BreezeDark\n", null));
        assertTrue(SystemTheme.isLinuxDark("Adwaita:dark", null, null, null, null));
        assertFalse(SystemTheme.isLinuxDark(null, "'default'\n", "'Adwaita'\n", null, null));
    }

    @Test
    void themeNames() {
        assertEquals(Theme.DARK, Theme.fromName("DARK"));
        assertEquals(Theme.SYSTEM, Theme.fromName("bogus"));
        assertEquals(Theme.SYSTEM, Theme.fromName(null));
        assertFalse(Theme.LIGHT.isDark());
        assertTrue(Theme.DARK.isDark());
    }
}
