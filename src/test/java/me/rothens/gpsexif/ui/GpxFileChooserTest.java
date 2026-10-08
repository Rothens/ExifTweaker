package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class GpxFileChooserTest {

    private final Preferences prefs = Preferences.userRoot().node("exiftweaker-test-" + UUID.randomUUID());
    private final Settings settings = new Settings(prefs);

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() throws BackingStoreException {
        prefs.removeNode();
    }

    private List<ImageFile> photosIn(Path folder) throws Exception {
        Files.createDirectories(folder);
        Path photo = Files.createFile(folder.resolve("a.jpg"));
        return List.of(new ImageFile(photo.toFile(), new CommonsImagingBackend()));
    }

    @Test
    void startsWhereTheTrackMostLikelyIs() throws Exception {
        Path photos = dir.resolve("photos");
        Path tracks = Files.createDirectories(dir.resolve("tracks"));
        List<ImageFile> list = photosIn(photos);

        // Nothing known yet: the photos' folder
        assertEquals(photos.toFile(), GpxFileChooser.startFolder(list, settings));

        // A GPX file was picked elsewhere before: start there
        settings.setGpxDirectory(tracks.toString());
        assertEquals(tracks.toFile(), GpxFileChooser.startFolder(list, settings));

        // ... unless the photos' folder has a track of its own (e.g. the tutorial's sample trip)
        Files.writeString(photos.resolve("trip.GPX"), "<gpx/>");
        assertEquals(photos.toFile(), GpxFileChooser.startFolder(list, settings));
    }

    @Test
    void aRememberedFolderThatIsGoneIsIgnored() throws Exception {
        Path photos = dir.resolve("photos");
        settings.setGpxDirectory(dir.resolve("deleted sample photos").toString());
        assertEquals(photos.toFile(), GpxFileChooser.startFolder(photosIn(photos), settings));
        assertNull(GpxFileChooser.startFolder(List.of(), new Settings(prefs)));
        assertEquals(new File(dir.resolve("deleted sample photos").toString()).getPath(), settings.getGpxDirectory());
    }
}
