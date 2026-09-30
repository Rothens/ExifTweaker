package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure-Java backend based on Apache Commons Imaging. Supports JPEG only. */
public class CommonsImagingBackend implements MetadataBackend {

    private static final Set<String> EXIF_FIELDS = Set.of("Make", "Model", "Orientation", "XResolution", "YResolution",
            "ExposureTime", "FNumber", "DateTimeOriginal", "DateTimeDigitized", "ExifImageWidth", "ExifImageLength");

    @Override
    public boolean canRead(Path file) {
        return isJpeg(file);
    }

    @Override
    public boolean canWrite(Path file) {
        return isJpeg(file);
    }

    private static boolean isJpeg(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    @Override
    public PhotoMetadata read(Path file) throws IOException {
        TiffImageMetadata exif = readExif(file);
        if (null == exif) {
            return PhotoMetadata.EMPTY;
        }
        return new PhotoMetadata(readPosition(exif), readOrientation(exif), readFields(exif));
    }

    private static TiffImageMetadata readExif(Path file) throws IOException {
        ImageMetadata metadata = Imaging.getMetadata(file.toFile());
        if (metadata instanceof JpegImageMetadata jpegMetadata) {
            return jpegMetadata.getExif();
        }
        return null;
    }

    private static GeoPosition readPosition(TiffImageMetadata exif) {
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

    private static int readOrientation(TiffImageMetadata exif) {
        try {
            TiffField field = exif.findField(TiffTagConstants.TIFF_TAG_ORIENTATION);
            if (null != field) {
                int value = field.getIntValue();
                if (value >= 1 && value <= 8) {
                    return value;
                }
            }
        } catch (ImagingException | RuntimeException e) {
            // Malformed tag - fall back to "normal"
        }
        return 1;
    }

    private static List<ExifData> readFields(TiffImageMetadata exif) {
        List<ExifData> ret = new ArrayList<>();
        for (TiffField tf : exif.getAllFields()) {
            if (EXIF_FIELDS.contains(tf.getTagName()) && ret.stream().noneMatch(d -> d.getKey().equals(tf.getTagName()))) {
                ret.add(new ExifData(tf.getTagName(), tf.getValueDescription()));
            }
        }
        Collections.sort(ret);
        return ret;
    }

    @Override
    public void writePosition(Path source, Path target, GeoPosition position) throws IOException {
        TiffOutputSet outputSet = outputSetOf(source);
        outputSet.setGpsInDegrees(position.getLongitude(), position.getLatitude());
        write(source, target, outputSet);
    }

    @Override
    public void removePosition(Path source, Path target) throws IOException {
        TiffOutputSet original = outputSetOf(source);
        // TiffOutputSet can't remove a directory, so copy everything except the GPS directory into a new set.
        // The GPS pointer in the root directory is regenerated when writing, based on the directories present.
        TiffOutputSet withoutGps = new TiffOutputSet(original.byteOrder);
        for (TiffOutputDirectory directory : original) {
            if (directory.getType() != TiffDirectoryConstants.DIRECTORY_TYPE_GPS) {
                withoutGps.addDirectory(directory);
            }
        }
        if (!withoutGps.iterator().hasNext()) {
            // No EXIF at all, so there's nothing to remove
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        withoutGps.removeField(ExifTagConstants.EXIF_TAG_GPSINFO);
        write(source, target, withoutGps);
    }

    private static TiffOutputSet outputSetOf(Path source) throws IOException {
        TiffImageMetadata exif = readExif(source);
        TiffOutputSet outputSet = null != exif ? exif.getOutputSet() : null;
        return null != outputSet ? outputSet : new TiffOutputSet();
    }

    private static void write(Path source, Path target, TiffOutputSet outputSet) throws IOException {
        try {
            write(source, target, outputSet, true);
        } catch (ImagingException lossless) {
            // Not enough room in the existing EXIF segment - rewrite it entirely.
            write(source, target, outputSet, false);
        }
    }

    private static void write(Path source, Path target, TiffOutputSet outputSet, boolean lossless) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(target))) {
            if (lossless) {
                new ExifRewriter().updateExifMetadataLossless(source.toFile(), os, outputSet);
            } else {
                new ExifRewriter().updateExifMetadataLossy(source.toFile(), os, outputSet);
            }
        }
    }
}
