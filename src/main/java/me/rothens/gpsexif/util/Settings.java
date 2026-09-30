package me.rothens.gpsexif.util;

import java.util.prefs.Preferences;

/**
 * Typed access to the user's persisted preferences.
 *
 * Created by Rothens on 2017. 06. 20..
 */
public class Settings {
    private static final String LAST_DIRECTORY = "LAST_DIRECTORY";
    private static final String BACKUPS_ENABLED = "BACKUPS_ENABLED";
    private static final String MAP_TYPE = "MAP_TYPE";

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

    public int getMapType() {
        return prefs.getInt(MAP_TYPE, 0);
    }

    public void setMapType(int index) {
        prefs.putInt(MAP_TYPE, index);
    }

    /** Whether a {@code .bak} copy of each photo is kept before it's first modified. On by default. */
    public boolean isBackupsEnabled() {
        return prefs.getBoolean(BACKUPS_ENABLED, true);
    }

    public void setBackupsEnabled(boolean enabled) {
        prefs.putBoolean(BACKUPS_ENABLED, enabled);
    }
}
