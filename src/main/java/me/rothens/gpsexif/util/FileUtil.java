package me.rothens.gpsexif.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileUtil {

    private FileUtil() {
    }

    /** Creates an empty temporary file in the same directory as {@code file}, so it can be moved over it atomically. */
    public static Path createSiblingTempFile(Path file) throws IOException {
        return Files.createTempFile(file.toAbsolutePath().getParent(), ".exiftweaker-", ".tmp");
    }

    /** Moves {@code source} over {@code target}, atomically where the file system supports it. */
    public static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
