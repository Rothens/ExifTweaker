package me.rothens.gpsexif.util;

import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.ui.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    private final Preferences prefs = Preferences.userRoot().node("exiftweaker-test-" + UUID.randomUUID());
    private final Settings settings = new Settings(prefs);

    @AfterEach
    void tearDown() throws BackingStoreException {
        prefs.removeNode();
    }

    @Test
    void defaults() {
        assertEquals("", settings.getLastDirectory());
        assertTrue(settings.isBackupsEnabled());
        assertEquals(MapLayer.OPENSTREETMAP, settings.getMapLayer());
        assertEquals(Theme.SYSTEM, settings.getTheme());
    }

    @Test
    void storesTheme() {
        settings.setTheme(Theme.DARK);
        assertEquals(Theme.DARK, new Settings(prefs).getTheme());
    }

    @Test
    void legacyVirtualEarthSettingMapsToEsri() {
        prefs.putInt("MAP_TYPE", 1);
        assertEquals(MapLayer.ESRI_WORLD_IMAGERY, settings.getMapLayer());
        prefs.putInt("MAP_TYPE", 0);
        assertEquals(MapLayer.OPENSTREETMAP, settings.getMapLayer());
    }

    @Test
    void newSettingWinsOverLegacy() {
        prefs.putInt("MAP_TYPE", 1);
        settings.setMapLayer(MapLayer.OPENSTREETMAP);
        assertEquals(MapLayer.OPENSTREETMAP, settings.getMapLayer());
    }
}
