package me.rothens.gpsexif.ui;

import com.formdev.flatlaf.util.SystemInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects whether the operating system uses a dark theme, by asking the platform's own tools. Any failure
 * (tool missing, unknown desktop, timeout) counts as "light".
 */
final class SystemTheme {

    private static final long TIMEOUT_MS = 2000;

    private SystemTheme() {
    }

    static boolean isDark() {
        try {
            if (SystemInfo.isWindows) {
                return isWindowsDark(run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"));
            }
            if (SystemInfo.isMacOS) {
                return isMacDark(run("defaults", "read", "-g", "AppleInterfaceStyle"));
            }
            if (SystemInfo.isLinux) {
                return isLinuxDark(System.getenv("GTK_THEME"),
                        run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"),
                        run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"),
                        run("kreadconfig6", "--group", "General", "--key", "ColorScheme"),
                        run("kreadconfig5", "--group", "General", "--key", "ColorScheme"));
            }
        } catch (RuntimeException e) {
            // Fall through to light
        }
        return false;
    }

    /** {@code reg query} prints e.g. {@code AppsUseLightTheme    REG_DWORD    0x0}; 0 means dark. */
    static boolean isWindowsDark(String regOutput) {
        if (null == regOutput) {
            return false;
        }
        for (String line : regOutput.split("\\R")) {
            if (line.contains("AppsUseLightTheme")) {
                return line.trim().endsWith("0x0");
            }
        }
        return false;
    }

    /** {@code defaults read -g AppleInterfaceStyle} prints "Dark" in dark mode and fails in light mode. */
    static boolean isMacDark(String output) {
        return null != output && output.trim().equalsIgnoreCase("dark");
    }

    /** Any of: GTK_THEME, GNOME color-scheme, GTK theme name, KDE color scheme name. */
    static boolean isLinuxDark(String... hints) {
        for (String hint : hints) {
            if (null != hint && hint.toLowerCase(Locale.ROOT).contains("dark")) {
                return true;
            }
        }
        return false;
    }

    /** Runs a command and returns its stdout, or {@code null} if it can't be run, fails or times out. */
    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
