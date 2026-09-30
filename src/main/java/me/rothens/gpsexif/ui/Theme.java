package me.rothens.gpsexif.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.SystemInfo;

/** The UI theme, based on FlatLaf. Enum names are persisted in the preferences, so don't rename them. */
public enum Theme {
    SYSTEM("Same as system"),
    LIGHT("Light"),
    DARK("Dark");

    private final String displayName;

    Theme(String displayName) {
        this.displayName = displayName;
    }

    /** Whether this theme resolves to a dark look; {@link #SYSTEM} asks the operating system. */
    public boolean isDark() {
        return switch (this) {
            case LIGHT -> false;
            case DARK -> true;
            case SYSTEM -> SystemTheme.isDark();
        };
    }

    /**
     * Installs the look and feel. Call before creating any UI, or call {@link FlatLaf#updateUI()} afterwards to
     * switch an already visible UI.
     */
    public void install() {
        boolean dark = isDark();
        if (SystemInfo.isMacOS) {
            if (dark) {
                FlatMacDarkLaf.setup();
            } else {
                FlatMacLightLaf.setup();
            }
        } else if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    /** Parses a persisted theme name, falling back to {@link #SYSTEM}. */
    public static Theme fromName(String name) {
        for (Theme theme : values()) {
            if (theme.name().equals(name)) {
                return theme;
            }
        }
        return SYSTEM;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
