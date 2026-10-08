package me.rothens.gpsexif.util;

import me.rothens.gpsexif.model.TripMark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TripMarkStoreTest {

    @TempDir
    Path dir;

    @Test
    void marksAreSavedAndLoadedBack() throws Exception {
        Path file = dir.resolve("config").resolve("trip-marks.properties");
        Path photo = dir.resolve("Holiday").resolve("IMG_1.jpg");
        TripMarkStore store = new TripMarkStore(file);
        assertEquals(TripMark.NORMAL, store.get(photo));
        store.set(photo, TripMark.SKIP);
        store.set(dir.resolve("Holiday/../Holiday/IMG_2.jpg"), TripMark.PREFER);
        TripMarkStore reloaded = new TripMarkStore(file);
        assertEquals(TripMark.SKIP, reloaded.get(photo));
        assertEquals(TripMark.PREFER, reloaded.get(dir.resolve("Holiday/IMG_2.jpg")));
        reloaded.set(photo, TripMark.NORMAL);
        assertEquals(TripMark.NORMAL, new TripMarkStore(file).get(photo));
    }

    @Test
    void aBrokenFileMeansNoMarks() throws Exception {
        Path file = dir.resolve("trip-marks.properties");
        Files.writeString(file, "x=\\u12");
        assertEquals(TripMark.NORMAL, new TripMarkStore(file).get(dir.resolve("x")));
    }
}
