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
     * Writes a copy of {@code source} to {@code target} with its GPS position set to {@code position}.
     * {@code source} itself is never modified; replacing it is up to the caller.
     */
    default void writePosition(Path source, Path target, GeoPosition position) throws IOException {
        writePosition(source, target, position, null);
    }

    /**
     * Like {@link #writePosition(Path, Path, GeoPosition)}, also writing the altitude (metres above sea level,
     * negative below). A {@code null} altitude leaves an existing altitude untouched.
     */
    void writePosition(Path source, Path target, GeoPosition position, Double altitude) throws IOException;

    /**
     * Writes a copy of {@code source} to {@code target} without any GPS data. Other metadata is kept.
     * {@code source} itself is never modified; replacing it is up to the caller.
     */
    void removePosition(Path source, Path target) throws IOException;
}
