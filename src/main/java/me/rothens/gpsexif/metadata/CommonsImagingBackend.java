package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.common.RationalNumber;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
        return new PhotoMetadata(readPosition(exif), readOrientation(exif), readFields(exif), readTaken(exif),
                readTakenOffset(exif), readAltitude(exif));
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

    private static final DateTimeFormatter EXIF_DATE_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    /** OffsetTimeOriginal (EXIF 2.31), e.g. "+02:00". commons-imaging has no constant for it. */
    private static final int TAG_OFFSET_TIME_ORIGINAL = 0x9011;

    /** DateTimeOriginal (with sub-seconds), falling back to DateTimeDigitized and DateTime. */
    static LocalDateTime readTaken(TiffImageMetadata exif) {
        LocalDateTime taken = parseDateTime(asciiValue(exif, ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL));
        if (null != taken) {
            String subSec = asciiValue(exif, ExifTagConstants.EXIF_TAG_SUB_SEC_TIME_ORIGINAL);
            if (null != subSec && subSec.trim().matches("\\d{1,9}")) {
                String digits = (subSec.trim() + "000000000").substring(0, 9);
                taken = taken.withNano(Integer.parseInt(digits));
            }
            return taken;
        }
        taken = parseDateTime(asciiValue(exif, ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED));
        return null != taken ? taken : parseDateTime(asciiValue(exif, TiffTagConstants.TIFF_TAG_DATE_TIME));
    }

    private static ZoneOffset readTakenOffset(TiffImageMetadata exif) {
        for (TiffField field : exif.getAllFields()) {
            if (field.getTag() == TAG_OFFSET_TIME_ORIGINAL) {
                try {
                    return ZoneOffset.of(field.getStringValue().trim());
                } catch (ImagingException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Double readAltitude(TiffImageMetadata exif) {
        try {
            TiffField altitude = exif.findField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE);
            if (null == altitude) {
                return null;
            }
            double value = altitude.getDoubleValue();
            TiffField ref = exif.findField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF);
            boolean below = null != ref
                    && ref.getIntValue() == GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_BELOW_SEA_LEVEL;
            return below ? -value : value;
        } catch (ImagingException | RuntimeException e) {
            return null;
        }
    }

    private static String asciiValue(TiffImageMetadata exif, TagInfo tag) {
        try {
            TiffField field = exif.findField(tag, true);
            return null != field ? field.getStringValue() : null;
        } catch (ImagingException | RuntimeException e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String text) {
        if (null == text) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), EXIF_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null; // e.g. "0000:00:00 00:00:00" written by cameras without a set clock
        }
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
    public void writePosition(Path source, Path target, GeoPosition position, Double altitude) throws IOException {
        TiffOutputSet outputSet = outputSetOf(source);
        outputSet.setGpsInDegrees(position.getLongitude(), position.getLatitude());
        if (null != altitude) {
            TiffOutputDirectory gps = outputSet.getOrCreateGpsDirectory();
            gps.removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE);
            gps.removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF);
            gps.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE, RationalNumber.valueOf(Math.abs(altitude)));
            gps.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF, (byte) (altitude < 0
                    ? GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_BELOW_SEA_LEVEL
                    : GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_ABOVE_SEA_LEVEL));
        }
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
