package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.MetadataBackend;
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
    private PhotoMetadata metadata = PhotoMetadata.EMPTY;

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
        Path original = getPath();
        Path tmp = FileUtil.createSiblingTempFile(original);
        try {
            backend.writePosition(original, tmp, position);
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
