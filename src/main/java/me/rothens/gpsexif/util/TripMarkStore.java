package me.rothens.gpsexif.util;

import me.rothens.gpsexif.model.TripMark;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Remembers which photos are skipped or preferred in trips, by their absolute path. Kept in ExifTweaker's own
 * folder, not in the photos: it's a choice about the film, not about the photo, and needs no Save or Undo.
 */
public class TripMarkStore {

    private final Path file;
    private final Properties marks = new Properties();

    /** Loads the marks from {@code file} (missing or unreadable: no marks). */
    public TripMarkStore(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                marks.load(in);
            } catch (IOException | IllegalArgumentException e) {
                marks.clear();
            }
        }
    }

    /** The default store: {@code ~/.exiftweaker/trip-marks.properties}. */
    public static TripMarkStore openDefault() {
        return new TripMarkStore(Path.of(System.getProperty("user.home"), ".exiftweaker", "trip-marks.properties"));
    }

    public synchronized TripMark get(Path photo) {
        String value = marks.getProperty(key(photo));
        if (null == value) {
            return TripMark.NORMAL;
        }
        try {
            return TripMark.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TripMark.NORMAL;
        }
    }

    /** Sets the mark of a photo and saves the store. */
    public synchronized void set(Path photo, TripMark mark) throws IOException {
        if (TripMark.NORMAL == mark) {
            marks.remove(key(photo));
        } else {
            marks.setProperty(key(photo), mark.name());
        }
        save();
    }

    private static String key(Path photo) {
        return photo.toAbsolutePath().normalize().toString();
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "trip-marks", ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            marks.store(out, "ExifTweaker: photos skipped or preferred in trips");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
