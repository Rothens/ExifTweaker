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
     * Writes a copy of {@code source} to {@code target} with {@code changes} applied; everything else is kept.
     * {@code source} itself is never modified; replacing it is up to the caller.
     */
    void write(Path source, Path target, MetadataChanges changes) throws IOException;

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
