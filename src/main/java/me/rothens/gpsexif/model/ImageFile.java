package me.rothens.gpsexif.model;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImageFile {

    private static final Set<String> EXIF_FIELDS = Set.of("Make", "Model", "Orientation", "XResolution", "YResolution",
            "ExposureTime", "FNumber", "DateTimeOriginal", "DateTimeDigitized", "ExifImageWidth", "ExifImageLength");

    private final File file;
    private GeoPosition gp;
    private List<ExifData> exifData;

    public ImageFile(File file) {
        this.file = file;
        reload();
    }

    public boolean hasExifGPS() {
        return null != gp;
    }

    private void reload() {
        TiffImageMetadata exif = readExif(file);
        gp = getLocation(exif);
        exifData = fillExif(exif);
    }

    private static TiffImageMetadata readExif(File image) {
        try {
            ImageMetadata metadata = Imaging.getMetadata(image);
            if (metadata instanceof JpegImageMetadata jpegMetadata) {
                return jpegMetadata.getExif();
            }
        } catch (IOException e) {
            System.err.println("Couldn't read metadata of " + image.getName() + ": " + e.getMessage());
        }
        return null;
    }

    private static GeoPosition getLocation(TiffImageMetadata exif) {
        if (null == exif) {
            return null;
        }
        try {
            TiffImageMetadata.GpsInfo gpsInfo = exif.getGpsInfo();
            if (null != gpsInfo) {
                return new GeoPosition(gpsInfo.getLatitudeAsDegreesNorth(), gpsInfo.getLongitudeAsDegreesEast());
            }
        } catch (ImagingException | RuntimeException e) {
            // Malformed GPS block - treat as "no position"
        }
        return null;
    }

    private static List<ExifData> fillExif(TiffImageMetadata exif) {
        List<ExifData> ret = new ArrayList<>();
        if (null == exif) {
            return ret;
        }
        for (TiffField tf : exif.getAllFields()) {
            if (EXIF_FIELDS.contains(tf.getTagName()) && ret.stream().noneMatch(d -> d.getKey().equals(tf.getTagName()))) {
                ret.add(new ExifData(tf.getTagName(), tf.getValueDescription()));
            }
        }
        Collections.sort(ret);
        return ret;
    }

    public List<ExifData> getExifData() {
        return exifData;
    }

    public File getFile() {
        return file;
    }

    public GeoPosition getGp() {
        return gp;
    }

    public void setGp(GeoPosition gp) {
        this.gp = gp;
    }

    /**
     * Writes the current GPS position into the file's EXIF block. The new image is written to a temporary
     * file next to the original first and only moved over it once writing fully succeeded, so a failure
     * never leaves a truncated original behind.
     */
    public void save() throws IOException {
        if (null == gp) {
            throw new IllegalStateException("No position set for " + file.getName());
        }
        TiffImageMetadata exif = readExif(file);
        TiffOutputSet outputSet = null != exif ? exif.getOutputSet() : null;
        if (null == outputSet) {
            outputSet = new TiffOutputSet();
        }
        outputSet.setGpsInDegrees(gp.getLongitude(), gp.getLatitude());

        Path original = file.toPath();
        Path tmp = Files.createTempFile(original.toAbsolutePath().getParent(), ".exiftweaker-", ".tmp");
        try {
            try {
                write(tmp, outputSet, true);
            } catch (ImagingException lossless) {
                // Not enough room in the existing EXIF segment - rewrite it entirely.
                write(tmp, outputSet, false);
            }
            try {
                Files.move(tmp, original, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, original, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        reload();
    }

    private void write(Path target, TiffOutputSet outputSet, boolean lossless) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(target))) {
            if (lossless) {
                new ExifRewriter().updateExifMetadataLossless(file, os, outputSet);
            } else {
                new ExifRewriter().updateExifMetadataLossy(file, os, outputSet);
            }
        }
    }

    @Override
    public String toString() {
        return file.getName();
    }
}
