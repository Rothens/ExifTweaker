package me.rothens.gpsexif.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckTest {

    @Test
    void comparesVersionsByTheirNumbers() {
        assertTrue(UpdateCheck.isNewer("1.4.0", "1.3.2"));
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.9"));
        assertTrue(UpdateCheck.isNewer("v2.0", "1.99.99"));
        assertTrue(UpdateCheck.isNewer("1.4.0", "1.4.0-SNAPSHOT"), "the release beats its development build");
        assertFalse(UpdateCheck.isNewer("1.4.0", "1.4.0"));
        assertFalse(UpdateCheck.isNewer("1.4.0-beta", "1.4.0"));
        assertFalse(UpdateCheck.isNewer("1.3.9", "1.4.0"));
        assertFalse(UpdateCheck.isNewer("1.4", "1.4.0"));
        assertFalse(UpdateCheck.isNewer("SneakyPeeky", "1.0.0"), "unreadable versions are never newer");
        assertFalse(UpdateCheck.isNewer("1.5.0", null));
    }

    @Test
    void readsGitHubsAnswer() throws IOException {
        UpdateCheck check = new UpdateCheck(uri -> {
            assertEquals(UpdateCheck.LATEST, uri.toString());
            return "{\"tag_name\":\"v1.4.0\",\"name\":\"Places\",\"draft\":false,"
                    + "\"html_url\":\"https://github.com/Rothens/ExifTweaker/releases/tag/v1.4.0\"}";
        });
        UpdateCheck.Release release = check.latest();
        assertEquals("1.4.0", release.version());
        assertEquals("https://github.com/Rothens/ExifTweaker/releases/tag/v1.4.0", release.url());

        // Only GitHub links are opened
        assertEquals(UpdateCheck.RELEASES_PAGE, UpdateCheck.parse("{\"tag_name\":\"1.5.0\",\"html_url\":\"http://evil\"}").url());
        assertThrows(IOException.class, () -> UpdateCheck.parse("{\"message\":\"Not Found\"}"));
        assertThrows(IOException.class, () -> UpdateCheck.parse("<html>"));
    }
}
