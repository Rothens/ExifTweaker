package me.rothens.gpsexif.metadata;

import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads and writes photo metadata. The UI and model only talk to this interface, so another implementation
 * (e.g. ExifTool for HEIC/RAW) can be added without touching them.
 */
public interface MetadataBackend {

    /** Whether this backend can read metadata from the given file. */
    boolean canRead(Path file);

    /** Whether this backend can write metadata to the given file. */
    boolean canWrite(Path file);

    /** Reads the metadata of {@code file}. Files without metadata return {@link PhotoMetadata#EMPTY}. */
    PhotoMetadata read(Path file) throws IOException;

    /**
     * The file that writes to {@code photo} actually change: the photo itself, or e.g. an XMP sidecar next to a
     * RAW file. It may not exist yet.
     */
    default Path writeTarget(Path photo) {
        return photo;
    }

    /**
     * Writes the new content of {@link #writeTarget(Path) photo's write target} to {@code target}, with
     * {@code changes} applied and everything else kept. Nothing existing is modified; replacing the write target
     * with {@code target} is up to the caller. {@code target} may already exist (as an empty temporary file).
     */
    void write(Path photo, Path target, MetadataChanges changes) throws IOException;

    /** An embedded preview image (e.g. of a RAW file) as JPEG bytes, or {@code null}. */
    default byte[] preview(Path photo) throws IOException {
        return null;
    }

    default void writePosition(Path source, Path target, GeoPosition position) throws IOException {
        write(source, target, new MetadataChanges().position(position));
    }

    /** Writes a position and, unless {@code null}, an altitude in metres (a {@code null} altitude is left as is). */
    default void writePosition(Path source, Path target, GeoPosition position, Double altitude) throws IOException {
        MetadataChanges changes = new MetadataChanges().position(position);
        if (null != altitude) {
            changes.altitude(altitude);
        }
        write(source, target, changes);
    }

    /** Writes a copy without any GPS data. */
    default void removePosition(Path source, Path target) throws IOException {
        write(source, target, new MetadataChanges().removePosition());
    }
}
