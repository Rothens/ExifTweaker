package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        TiffImageMetadata exif = readExif(source);
        TiffOutputSet outputSet = null != exif ? exif.getOutputSet() : null;
        if (null == outputSet) {
            outputSet = new TiffOutputSet();
        }
        outputSet.setGpsInDegrees(position.getLongitude(), position.getLatitude());
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
