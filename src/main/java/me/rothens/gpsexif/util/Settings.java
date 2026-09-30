package me.rothens.gpsexif.util;

import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.ui.Theme;

import java.util.prefs.Preferences;

/**
 * Typed access to the user's persisted preferences.
 *
 * Created by Rothens on 2017. 06. 20..
 */
public class Settings {
    private static final String LAST_DIRECTORY = "LAST_DIRECTORY";
    private static final String BACKUPS_ENABLED = "BACKUPS_ENABLED";
    private static final String MAP_LAYER = "MAP_LAYER";
    private static final String THEME = "THEME";
    private static final String TILE_CACHE_MAX_MB = "TILE_CACHE_MAX_MB";
    private static final String CAMERA_ZONE = "CAMERA_ZONE";
    private static final String GPX_MAX_GAP_MINUTES = "GPX_MAX_GAP_MINUTES";

    public static final int DEFAULT_GPX_MAX_GAP_MINUTES = 10;

    public static final int DEFAULT_TILE_CACHE_MAX_MB = 500;
    public static final int MIN_TILE_CACHE_MAX_MB = 50;
    /** Pre-0.3 setting: index into [OpenStreetMap, VirtualEarth]. */
    private static final String LEGACY_MAP_TYPE = "MAP_TYPE";

    private final Preferences prefs;

    public Settings(Preferences prefs) {
        this.prefs = prefs;
    }

    public String getLastDirectory() {
        return prefs.get(LAST_DIRECTORY, "");
    }

    public void setLastDirectory(String directory) {
        prefs.put(LAST_DIRECTORY, directory);
    }

    /** Size limit of the map tile disk cache, in MB. */
    public int getTileCacheMaxMb() {
        return Math.max(MIN_TILE_CACHE_MAX_MB, prefs.getInt(TILE_CACHE_MAX_MB, DEFAULT_TILE_CACHE_MAX_MB));
    }

    public void setTileCacheMaxMb(int megabytes) {
        prefs.putInt(TILE_CACHE_MAX_MB, Math.max(MIN_TILE_CACHE_MAX_MB, megabytes));
    }

    /** Time zone the camera's clock is set to (for GPX matching); the system zone by default. */
    public java.time.ZoneId getCameraZone() {
        try {
            return java.time.ZoneId.of(prefs.get(CAMERA_ZONE, java.time.ZoneId.systemDefault().getId()));
        } catch (java.time.DateTimeException e) {
            return java.time.ZoneId.systemDefault();
        }
    }

    public void setCameraZone(java.time.ZoneId zone) {
        prefs.put(CAMERA_ZONE, zone.getId());
    }

    public int getGpxMaxGapMinutes() {
        return Math.max(1, prefs.getInt(GPX_MAX_GAP_MINUTES, DEFAULT_GPX_MAX_GAP_MINUTES));
    }

    public void setGpxMaxGapMinutes(int minutes) {
        prefs.putInt(GPX_MAX_GAP_MINUTES, Math.max(1, minutes));
    }

    public Theme getTheme() {
        return Theme.fromName(prefs.get(THEME, null));
    }

    public void setTheme(Theme theme) {
        prefs.put(THEME, theme.name());
    }

    public MapLayer getMapLayer() {
        String name = prefs.get(MAP_LAYER, null);
        if (null != name) {
            return MapLayer.fromName(name);
        }
        // The retired VirtualEarth satellite layer maps to its replacement
        return prefs.getInt(LEGACY_MAP_TYPE, 0) == 1 ? MapLayer.ESRI_WORLD_IMAGERY : MapLayer.OPENSTREETMAP;
    }

    public void setMapLayer(MapLayer layer) {
        prefs.put(MAP_LAYER, layer.name());
    }

    /** Whether a {@code .bak} copy of each photo is kept before it's first modified. On by default. */
    public boolean isBackupsEnabled() {
        return prefs.getBoolean(BACKUPS_ENABLED, true);
    }

    public void setBackupsEnabled(boolean enabled) {
        prefs.putBoolean(BACKUPS_ENABLED, enabled);
    }
}
