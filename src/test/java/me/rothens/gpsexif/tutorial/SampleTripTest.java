package me.rothens.gpsexif.tutorial;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SampleTripTest {

    @AfterEach
    void cleanUp() {
        SampleTrip.deleteAll();
    }

    @Test
    void copiesTheTripIntoAFreshFolderAndDeletesIt() throws Exception {
        Path dir = SampleTrip.copy();
        List<String> names = SampleTrip.list();
        assertTrue(names.contains("balaton.gpx"));
        for (String name : names) {
            assertTrue(Files.size(dir.resolve(name)) > 0, name);
        }
        // The tour asks to tag IMG_4515.jpg, so it must be one of the photos without a location
        long withLocation = 0;
        for (String name : names) {
            if (name.endsWith(".jpg")) {
                ImageFile photo = new ImageFile(dir.resolve(name).toFile(), new CommonsImagingBackend());
                assertNotNull(photo.getTaken(), name);
                if (null != photo.getGp()) {
                    withLocation++;
                }
                if (name.equals("IMG_4515.jpg")) {
                    assertNull(photo.getGp());
                }
            }
        }
        assertEquals(9, withLocation);

        Files.writeString(dir.resolve("IMG_4515.jpg.bak"), "a backup the tour left behind");
        SampleTrip.deleteAll();
        assertFalse(Files.exists(dir));
    }

    @Test
    void eachCopyIsSeparate() throws Exception {
        Path first = SampleTrip.copy();
        Path second = SampleTrip.copy();
        assertNotEquals(first, second);
    }
}
