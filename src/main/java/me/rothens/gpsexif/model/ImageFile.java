package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.MetadataBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.TextField;
import me.rothens.gpsexif.metadata.PhotoMetadata;
import me.rothens.gpsexif.util.FileUtil;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImageFile {

    private final File file;
    private final MetadataBackend backend;
    // Replaced as a whole, possibly from a background thread (batch writes) while the UI reads it
    private volatile PhotoMetadata metadata = PhotoMetadata.EMPTY;

    public ImageFile(File file, MetadataBackend backend) {
        this.file = file;
        this.backend = backend;
        reload();
    }

    /** Re-reads the metadata from disk, e.g. after the file was written or restored. */
    public void reload() {
        try {
            metadata = backend.read(file.toPath());
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read metadata of " + file.getName() + ": " + e.getMessage());
            metadata = PhotoMetadata.EMPTY;
        }
    }

    public boolean hasExifGPS() {
        return null != metadata.position();
    }

    public GeoPosition getGp() {
        return metadata.position();
    }

    /** When the photo was taken according to the camera's clock, or {@code null}. */
    public java.time.LocalDateTime getTaken() {
        return metadata.taken();
    }

    /** The camera's UTC offset when the photo was taken, if the camera recorded it, or {@code null}. */
    public java.time.ZoneOffset getTakenOffset() {
        return metadata.takenOffset();
    }

    public Double getAltitude() {
        return metadata.altitude();
    }

    /** Direction the camera pointed, degrees clockwise from north, or {@code null}. */
    public Double getDirection() {
        return metadata.direction();
    }

    /** A text field's value, or {@code null} if it isn't set. */
    public String getText(TextField field) {
        return metadata.text().get(field);
    }

    public int getOrientation() {
        return metadata.orientation();
    }

    public List<ExifData> getExifData() {
        return metadata.fields();
    }

    public File getFile() {
        return file;
    }

    public Path getPath() {
        return file.toPath();
    }

    /**
     * Writes {@code position} into the file. The new image is written to a temporary file next to the original
     * first and only moved over it once writing fully succeeded, so a failure never leaves a truncated original.
     */
    public void savePosition(GeoPosition position) throws IOException {
        savePosition(position, null);
    }

    /** Writes a position and, unless {@code null}, an altitude in metres. */
    public void savePosition(GeoPosition position, Double altitude) throws IOException {
        rewrite((source, target) -> backend.writePosition(source, target, position, altitude));
    }

    /** Removes all GPS data from the file, as safely as {@link #savePosition}. */
    public void removePosition() throws IOException {
        rewrite(backend::removePosition);
    }

    /** Writes any combination of metadata changes, as safely as {@link #savePosition}. */
    public void apply(MetadataChanges changes) throws IOException {
        rewrite((source, target) -> backend.write(source, target, changes));
    }

    private interface Rewrite {
        void write(Path source, Path target) throws IOException;
    }

    private void rewrite(Rewrite rewrite) throws IOException {
        Path original = getPath();
        Path tmp = FileUtil.createSiblingTempFile(original);
        try {
            rewrite.write(original, tmp);
            FileUtil.replace(tmp, original);
        } finally {
            Files.deleteIfExists(tmp);
        }
        reload();
    }

    @Override
    public String toString() {
        return file.getName();
    }
}
