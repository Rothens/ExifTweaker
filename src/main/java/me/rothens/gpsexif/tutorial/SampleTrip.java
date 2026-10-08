package me.rothens.gpsexif.tutorial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The sample photos for the tutorial: a weekend at Lake Balaton (generated pictures, real places), three of them
 * without a location, and the GPX track of the first day. They're copied to a temporary folder, so the tutorial
 * never touches the user's own photos, and deleted when the app exits.
 */
public final class SampleTrip {

    private static final String RESOURCES = "samples/";
    private static final List<Path> created = new ArrayList<>();

    private SampleTrip() {
    }

    /** Copies the sample photos into a new temporary folder and returns it. */
    public static synchronized Path copy() throws IOException {
        Path dir = Files.createTempDirectory("ExifTweaker sample photos ");
        created.add(dir);
        for (String name : list()) {
            try (InputStream in = SampleTrip.class.getResourceAsStream(RESOURCES + name)) {
                if (null == in) {
                    throw new IOException("Missing sample file " + name);
                }
                Files.copy(in, dir.resolve(name));
            }
        }
        return dir;
    }

    /** The bundled file names. */
    static List<String> list() throws IOException {
        try (InputStream in = SampleTrip.class.getResourceAsStream(RESOURCES + "index.txt")) {
            if (null == in) {
                throw new IOException("Missing sample photo list");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(s -> !s.isEmpty()).toList();
        }
    }

    /** Deletes the folders {@link #copy()} made (with the backups the tutorial may have left in them). */
    public static synchronized void deleteAll() {
        for (Path dir : created) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException ignored) {
                // a leftover in the temp folder; the OS cleans those up
            }
        }
        created.clear();
    }
}
