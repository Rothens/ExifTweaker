package me.rothens.gpsexif.history;

import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Performs every write to photos: keeps the optional {@code .bak} backup of the original, records undo snapshots
 * and then lets each {@link ImageFile} write itself. All photos of one call are undone together.
 */
public class PhotoWriter {

    public static final String BACKUP_SUFFIX = ".bak";

    /** A change applied to one photo. */
    public interface Edit {
        void apply(ImageFile image) throws IOException;
    }

    /** Receives progress of a batch and can stop it between two photos. */
    public interface Progress {
        void update(int done, int total);

        default boolean isCancelled() {
            return false;
        }
    }

    /**
     * Outcome of a batch.
     *
     * @param changed  photos that were written
     * @param failures photos that couldn't be written, with the reason
     * @param skipped  photos that weren't attempted because the batch was cancelled
     */
    public record Result(List<ImageFile> changed, Map<ImageFile, String> failures, int skipped) {
        public boolean isComplete() {
            return failures.isEmpty() && skipped == 0;
        }
    }

    private final EditHistory history;
    private final BooleanSupplier backupsEnabled;

    public PhotoWriter(EditHistory history, BooleanSupplier backupsEnabled) {
        this.history = history;
        this.backupsEnabled = backupsEnabled;
    }

    /** Writes a position into one photo; throws if it fails. */
    public void savePosition(ImageFile image, GeoPosition position) throws IOException {
        Result result = apply("Set location of " + image.getFile().getName(), List.of(image),
                i -> i.savePosition(position), true, (done, total) -> { });
        if (!result.failures().isEmpty()) {
            throw new IOException(result.failures().values().iterator().next());
        }
    }

    /** Whether a batch over these photos fits into the undo history's disk budget. */
    public boolean canUndo(List<ImageFile> images) {
        long total = 0;
        for (ImageFile image : images) {
            try {
                Path written = image.getWritePath();
                total += Files.exists(written) ? Files.size(written) : 0;
            } catch (IOException e) {
                // Missing file - it'll fail in the batch anyway
            }
        }
        return history.canUndoEditOfSize(total);
    }

    /**
     * Applies {@code edit} to every photo. A failing photo doesn't stop the batch. The photos written until the
     * batch ends (or is cancelled) are undone together, if {@code undoable}.
     */
    public Result apply(String description, List<ImageFile> images, Edit edit, boolean undoable, Progress progress) {
        List<ImageFile> changed = new ArrayList<>();
        Map<ImageFile, String> failures = new LinkedHashMap<>();
        int done = 0;
        try (EditHistory.Transaction tx = undoable ? history.begin(description) : history.beginWithoutUndo(description)) {
            for (ImageFile image : images) {
                if (progress.isCancelled()) {
                    break;
                }
                try {
                    Path written = image.getWritePath();
                    if (backupsEnabled.getAsBoolean() && Files.exists(written)) {
                        createBackupIfMissing(written);
                    }
                    tx.snapshot(written);
                    edit.apply(image);
                    changed.add(image);
                } catch (IOException | UncheckedIOException | IllegalStateException e) {
                    failures.put(image, null != e.getMessage() ? e.getMessage() : e.getClass().getSimpleName());
                }
                progress.update(++done, images.size());
            }
            tx.commit();
        }
        return new Result(List.copyOf(changed), failures, images.size() - done);
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
