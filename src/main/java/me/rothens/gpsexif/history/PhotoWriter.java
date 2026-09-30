package me.rothens.gpsexif.history;

import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

/**
 * Performs every write to a photo: keeps the optional {@code .bak} backup of the original, records an undo
 * snapshot and then lets the {@link ImageFile} write itself.
 */
public class PhotoWriter {

    public static final String BACKUP_SUFFIX = ".bak";

    private final EditHistory history;
    private final BooleanSupplier backupsEnabled;

    public PhotoWriter(EditHistory history, BooleanSupplier backupsEnabled) {
        this.history = history;
        this.backupsEnabled = backupsEnabled;
    }

    public void savePosition(ImageFile image, GeoPosition position) throws IOException {
        try (EditHistory.Transaction tx = history.begin("Set location of " + image.getFile().getName())) {
            prepare(tx, image.getPath());
            image.savePosition(position);
            tx.commit();
        }
    }

    private void prepare(EditHistory.Transaction tx, Path file) throws IOException {
        if (backupsEnabled.getAsBoolean()) {
            createBackupIfMissing(file);
        }
        tx.snapshot(file);
    }

    public static Path backupPath(Path file) {
        return file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
    }

    /** Copies the file to {@code <name>.bak} unless that exists already, so the backup is always the true original. */
    static void createBackupIfMissing(Path file) throws IOException {
        Path backup = backupPath(file);
        if (!Files.exists(backup)) {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }
}
