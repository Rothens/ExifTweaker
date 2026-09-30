package me.rothens.gpsexif.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a real ExifTool; skipped when it isn't installed. */
class ExifToolTest {

    static String executable;

    @BeforeAll
    static void findExifTool() {
        executable = ExifTool.locate(null);
    }

    @TempDir
    Path dir;

    private ExifTool exifTool;

    @AfterEach
    void tearDown() {
        if (null != exifTool) {
            exifTool.close();
        }
    }

    @Test
    void encodesSpecialCharactersAsCString() {
        assertEquals("-Artist=Máté", ExifTool.encode("-Artist=Máté"));
        assertEquals("#[CSTR]-Description=a\\nb\\tc \\\\ \\\"q\\\"", ExifTool.encode("-Description=a\nb\tc \\ \"q\""));
    }

    @Test
    void unknownExecutableIsNotFound() {
        assertNull(ExifTool.version("/nonexistent/exiftool"));
        assertEquals(executable, ExifTool.locate("/nonexistent/exiftool"), "falls back to the PATH");
    }

    @Test
    void runsSeveralCommandsInOneProcessAndReportsErrors() throws IOException {
        assumeTrue(null != executable, "ExifTool not installed");
        exifTool = new ExifTool(executable);
        String first = exifTool.execute(List.of("-ver"));
        assertTrue(first.strip().matches("\\d+\\.\\d+.*"), first);
        assertEquals(first, exifTool.execute(List.of("-ver")));

        Path missing = dir.resolve("missing.jpg");
        IOException e = assertThrows(IOException.class, () -> exifTool.execute(List.of("-j", missing.toString())));
        assertTrue(e.getMessage().contains("Error"), e.getMessage());

        // Still usable after an error
        Files.writeString(dir.resolve("x.txt"), "hello");
        assertTrue(exifTool.execute(List.of("-j", "-FileSize#", dir.resolve("x.txt").toString())).contains("5"));
    }
}
