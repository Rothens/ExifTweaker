package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.MetadataBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.TextTag;
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

    // Changes when the photo is renamed
    private volatile File file;
    private final MetadataBackend backend;
    // Replaced as a whole, possibly from a background thread (batch writes) while the UI reads it
    private volatile PhotoMetadata metadata = PhotoMetadata.EMPTY;
    private volatile TripMark tripMark = TripMark.NORMAL;

    public ImageFile(File file, MetadataBackend backend) {
        this.file = file;
        this.backend = backend;
        reload();
    }

    /** Whether the photo is skipped or preferred in Play photos and Travel mode. */
    public TripMark getTripMark() {
        return tripMark;
    }

    public void setTripMark(TripMark tripMark) {
        this.tripMark = null == tripMark ? TripMark.NORMAL : tripMark;
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

    /** Where the photo was taken in words (city, country), or {@code null}. */
    public me.rothens.gpsexif.metadata.Place getPlace() {
        return metadata.place();
    }

    /** A text field's value, or {@code null} if it isn't set. */
    public String getText(TextTag field) {
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

    /** After the file was renamed or moved (by ExifTweaker): from now on it's {@code renamed}. */
    public void moveTo(File renamed) {
        this.file = renamed;
    }

    /** The file that edits actually change: the photo itself, or its XMP sidecar for RAW files. */
    public Path getWritePath() {
        return backend.writeTarget(getPath());
    }

    /** Whether edits can be written (e.g. not when a format needs ExifTool and it isn't installed). */
    public boolean isWritable() {
        return backend.canWrite(getPath());
    }

    public MetadataBackend getBackend() {
        return backend;
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
        if (!isWritable()) {
            throw new IOException(file.getName() + " can't be written (" + file.getName().replaceAll(".*\\.", "")
                    .toUpperCase(java.util.Locale.ROOT) + " files need ExifTool)");
        }
        Path written = getWritePath();
        Path tmp = FileUtil.createSiblingTempFile(written);
        try {
            rewrite.write(getPath(), tmp);
            FileUtil.replace(tmp, written);
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
