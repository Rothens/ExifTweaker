package me.rothens.gpsexif.metadata;

import static me.rothens.gpsexif.i18n.I18n.tr;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Picks the backend per file: JPEG uses the built-in {@link CommonsImagingBackend}; other formats use ExifTool
 * when it's available. Without ExifTool those files are still listed, but read as empty and can't be written.
 * ExifTool can be switched on or off at runtime (e.g. after the user set its location).
 */
public class RoutingBackend implements MetadataBackend {

    private final CommonsImagingBackend builtIn = new CommonsImagingBackend();
    private volatile ExifToolBackend exifTool;

    public void setExifTool(ExifToolBackend exifTool) {
        this.exifTool = exifTool;
    }

    public ExifToolBackend getExifTool() {
        return exifTool;
    }

    public boolean hasExifTool() {
        return null != exifTool;
    }

    /** Files that are listed but need ExifTool, which isn't available. */
    public boolean needsExifTool(Path file) {
        return !builtIn.canRead(file) && ExifToolBackend.EXTENSIONS.contains(ExifToolBackend.extension(file))
                && null == exifTool;
    }

    private MetadataBackend backendFor(Path file) {
        if (builtIn.canRead(file)) {
            return builtIn;
        }
        ExifToolBackend et = exifTool;
        return null != et && et.canRead(file) ? et : null;
    }

    @Override
    public boolean canRead(Path file) {
        return builtIn.canRead(file) || ExifToolBackend.EXTENSIONS.contains(ExifToolBackend.extension(file));
    }

    @Override
    public boolean canWrite(Path file) {
        MetadataBackend backend = backendFor(file);
        return null != backend && backend.canWrite(file);
    }

    @Override
    public Path writeTarget(Path photo) {
        MetadataBackend backend = backendFor(photo);
        return null != backend ? backend.writeTarget(photo) : photo;
    }

    @Override
    public PhotoMetadata read(Path file) throws IOException {
        MetadataBackend backend = backendFor(file);
        return null != backend ? backend.read(file) : PhotoMetadata.EMPTY;
    }

    @Override
    public void write(Path photo, Path target, MetadataChanges changes) throws IOException {
        MetadataBackend backend = backendFor(photo);
        if (null == backend) {
            throw new IOException(tr("{0} needs ExifTool to be written", photo.getFileName()));
        }
        backend.write(photo, target, changes);
    }

    @Override
    public byte[] preview(Path photo) throws IOException {
        ExifToolBackend et = exifTool;
        return null != et ? et.preview(photo) : null;
    }
}
