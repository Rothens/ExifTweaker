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
    private static final String TUTORIAL_SHOWN = "TUTORIAL_SHOWN";
    private static final String GPX_DIRECTORY = "GPX_DIRECTORY";
    private static final String RECENT_FOLDERS = "RECENT_FOLDERS";
    private static final String THUMBNAIL_VIEW = "THUMBNAIL_VIEW";
    private static final String PLACE_NAMES = "PLACE_NAMES";
    private static final String RENAME_PATTERN = "RENAME_PATTERN";
    public static final int MAX_RECENT_FOLDERS = 10;
    private static final String DISPLAY_ZONE = "DISPLAY_ZONE";
    private static final String GPX_MAX_GAP_MINUTES = "GPX_MAX_GAP_MINUTES";

    public static final int DEFAULT_GPX_MAX_GAP_MINUTES = 10;
    private static final String SHOW_PHOTO_MARKERS = "SHOW_PHOTO_MARKERS";
    private static final String EXIFTOOL_PATH = "EXIFTOOL_PATH";
    private static final String FFMPEG_PATH = "FFMPEG_PATH";
    private static final String VIDEO_SIZE = "VIDEO_SIZE";
    private static final String VIDEO_FPS = "VIDEO_FPS";
    private static final String VIDEO_DIRECTORY = "VIDEO_DIRECTORY";
    private static final String MAX_PHOTO_MARKERS = "MAX_PHOTO_MARKERS";
    public static final int DEFAULT_MAX_PHOTO_MARKERS = 200;
    public static final int MIN_PHOTO_MARKERS = 10;
    public static final int MAX_PHOTO_MARKERS_LIMIT = 2000;

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

    /** Whether the photos are shown as thumbnails rather than a list (the default). */
    public boolean isThumbnailView() {
        return prefs.getBoolean(THUMBNAIL_VIEW, false);
    }

    public void setThumbnailView(boolean thumbnails) {
        prefs.putBoolean(THUMBNAIL_VIEW, thumbnails);
    }

    /** Recently opened folders, the most recent first. */
    public java.util.List<String> getRecentFolders() {
        String value = prefs.get(RECENT_FOLDERS, "");
        return value.isEmpty() ? java.util.List.of() : java.util.List.of(value.split("\n"));
    }

    /** Puts {@code folder} first in the recent folders (moving it up if it's already there). */
    public void addRecentFolder(String folder) {
        java.util.List<String> folders = new java.util.ArrayList<>(getRecentFolders());
        folders.remove(folder);
        folders.add(0, folder);
        // Preferences values are limited in length: drop the oldest until it fits
        while (folders.size() > MAX_RECENT_FOLDERS
                || (folders.size() > 1 && String.join("\n", folders).length() > Preferences.MAX_VALUE_LENGTH)) {
            folders.remove(folders.size() - 1);
        }
        String value = String.join("\n", folders);
        if (value.length() <= Preferences.MAX_VALUE_LENGTH) {
            prefs.put(RECENT_FOLDERS, value);
        }
    }

    public void clearRecentFolders() {
        prefs.remove(RECENT_FOLDERS);
    }

    /** Folder of the last GPX file picked; empty if none yet. */
    public String getGpxDirectory() {
        return prefs.get(GPX_DIRECTORY, "");
    }

    public void setGpxDirectory(String directory) {
        prefs.put(GPX_DIRECTORY, directory);
    }

    /** Whether the tutorial was offered already (it's offered once, on the first start). */
    public boolean isTutorialShown() {
        return prefs.getBoolean(TUTORIAL_SHOWN, false);
    }

    public void setTutorialShown(boolean shown) {
        prefs.putBoolean(TUTORIAL_SHOWN, shown);
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

    /** Time zone the playback windows show times in; {@code null} for the camera's clock (the default). */
    public java.time.ZoneId getDisplayZone() {
        String id = prefs.get(DISPLAY_ZONE, "");
        try {
            return id.isEmpty() ? null : java.time.ZoneId.of(id);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    public void setDisplayZone(java.time.ZoneId zone) {
        prefs.put(DISPLAY_ZONE, null == zone ? "" : zone.getId());
    }

    public int getGpxMaxGapMinutes() {
        return Math.max(1, prefs.getInt(GPX_MAX_GAP_MINUTES, DEFAULT_GPX_MAX_GAP_MINUTES));
    }

    public void setGpxMaxGapMinutes(int minutes) {
        prefs.putInt(GPX_MAX_GAP_MINUTES, Math.max(1, minutes));
    }

    /** Configured ExifTool executable; empty means "look for it on the PATH". */
    public String getExifToolPath() {
        return prefs.get(EXIFTOOL_PATH, "");
    }

    public void setExifToolPath(String path) {
        prefs.put(EXIFTOOL_PATH, null == path ? "" : path.strip());
    }

    /** Configured FFmpeg executable; empty means "look for it on the PATH". */
    public String getFfmpegPath() {
        return prefs.get(FFMPEG_PATH, "");
    }

    public void setFfmpegPath(String path) {
        prefs.put(FFMPEG_PATH, null == path ? "" : path.strip());
    }

    /** Last chosen video size as "WIDTHxHEIGHT"; empty for the default. */
    public String getVideoSize() {
        return prefs.get(VIDEO_SIZE, "");
    }

    public void setVideoSize(String size) {
        prefs.put(VIDEO_SIZE, size);
    }

    public int getVideoFps() {
        return prefs.getInt(VIDEO_FPS, 30);
    }

    public void setVideoFps(int fps) {
        prefs.putInt(VIDEO_FPS, fps);
    }

    /** Folder of the last exported video; empty if none yet. */
    public String getVideoDirectory() {
        return prefs.get(VIDEO_DIRECTORY, "");
    }

    public void setVideoDirectory(String directory) {
        prefs.put(VIDEO_DIRECTORY, directory);
    }

    /** Whether the opened photos are shown on the map. Off by default. */
    public boolean isShowPhotoMarkers() {
        return prefs.getBoolean(SHOW_PHOTO_MARKERS, false);
    }

    public void setShowPhotoMarkers(boolean show) {
        prefs.putBoolean(SHOW_PHOTO_MARKERS, show);
    }

    /** At most this many photo markers (single photos or clusters) are drawn at once. */
    public int getMaxPhotoMarkers() {
        return clampMarkers(prefs.getInt(MAX_PHOTO_MARKERS, DEFAULT_MAX_PHOTO_MARKERS));
    }

    public void setMaxPhotoMarkers(int max) {
        prefs.putInt(MAX_PHOTO_MARKERS, clampMarkers(max));
    }

    private static int clampMarkers(int max) {
        return Math.max(MIN_PHOTO_MARKERS, Math.min(MAX_PHOTO_MARKERS_LIMIT, max));
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

    /** Whether the place name (city, country) is looked up and written along with a new location. On by default. */
    public boolean isPlaceNames() {
        return prefs.getBoolean(PLACE_NAMES, true);
    }

    public void setPlaceNames(boolean enabled) {
        prefs.putBoolean(PLACE_NAMES, enabled);
    }

    /** The last pattern used to rename photos. */
    public String getRenamePattern() {
        return prefs.get(RENAME_PATTERN, "{date} {place} {n:000}");
    }

    public void setRenamePattern(String pattern) {
        prefs.put(RENAME_PATTERN, pattern.length() > 500 ? pattern.substring(0, 500) : pattern);
    }
}
