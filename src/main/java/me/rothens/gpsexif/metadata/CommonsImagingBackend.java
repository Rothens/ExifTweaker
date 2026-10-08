package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.bytesource.ByteSource;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageParser;
import org.apache.commons.imaging.formats.jpeg.JpegPhotoshopMetadata;
import org.apache.commons.imaging.formats.jpeg.iptc.IptcBlock;
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord;
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes;
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter;
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data;
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter;
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
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoAscii;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure-Java backend based on Apache Commons Imaging. Supports JPEG only. */
public class CommonsImagingBackend implements MetadataBackend {

    /** Read-only technical fields shown for information; editable ones are read into {@link PhotoMetadata}. */
    private static final Set<String> EXIF_FIELDS = Set.of("Orientation", "XResolution", "YResolution",
            "ExposureTime", "FNumber", "ISO", "FocalLength", "LensModel", "ExifImageWidth", "ExifImageLength");

    private static final Map<TextTag, TagInfoAscii> TEXT_TAGS = Map.of(
            TextTag.MAKE, TiffTagConstants.TIFF_TAG_MAKE,
            TextTag.MODEL, TiffTagConstants.TIFF_TAG_MODEL,
            TextTag.ARTIST, TiffTagConstants.TIFF_TAG_ARTIST,
            TextTag.COPYRIGHT, TiffTagConstants.TIFF_TAG_COPYRIGHT,
            TextTag.DESCRIPTION, TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);

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
        ImageMetadata metadata = Imaging.getMetadata(file.toFile());
        JpegImageMetadata jpeg = metadata instanceof JpegImageMetadata j ? j : null;
        TiffImageMetadata exif = null == jpeg ? null : jpeg.getExif();
        Place place = readPlace(file, null == jpeg ? null : jpeg.getPhotoshop());
        if (null == exif) {
            return PhotoMetadata.EMPTY.withPlace(place);
        }
        return new PhotoMetadata(readPosition(exif), readOrientation(exif), readFields(exif), readTaken(exif),
                readTakenOffset(exif), readAltitude(exif), readDirection(exif), readText(exif), place);
    }

    /** IPTC IIM place records, in the order of {@link Place}'s components. */
    private static final List<IptcTypes> IPTC_PLACE = List.of(IptcTypes.SUBLOCATION, IptcTypes.CITY,
            IptcTypes.PROVINCE_STATE, IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME, IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE);

    /** The place from XMP, else from IPTC, or {@code null}. */
    private static Place readPlace(Path file, JpegPhotoshopMetadata photoshop) {
        try {
            Place place = XmpPlace.read(Imaging.getXmpXml(file.toFile()));
            if (null != place) {
                return place;
            }
        } catch (IOException | RuntimeException e) {
            // Broken XMP: try IPTC
        }
        if (null == photoshop || null == photoshop.photoshopApp13Data) {
            return null;
        }
        String[] values = new String[IPTC_PLACE.size()];
        for (IptcRecord record : photoshop.photoshopApp13Data.getRecords()) {
            int i = IPTC_PLACE.indexOf(record.iptcType instanceof IptcTypes t ? t : null);
            if (i >= 0 && null == values[i]) {
                values[i] = record.getValue();
            }
        }
        String code = values[4];
        if (null != code && code.strip().length() == 3) {
            code = Place.alpha2(code.strip());
        }
        Place place = new Place(values[0], values[1], values[2], values[3], code);
        return place.isEmpty() ? null : place;
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

    private static Double readDirection(TiffImageMetadata exif) {
        try {
            TiffField direction = exif.findField(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION);
            return null == direction ? null : MetadataChanges.normalizeDegrees(direction.getDoubleValue());
        } catch (ImagingException | RuntimeException e) {
            return null;
        }
    }

    private static Map<TextTag, String> readText(TiffImageMetadata exif) {
        Map<TextTag, String> text = new EnumMap<>(TextTag.class);
        for (Map.Entry<TextTag, TagInfoAscii> entry : TEXT_TAGS.entrySet()) {
            String value = asciiValue(exif, entry.getValue());
            if (null != value && !value.isBlank()) {
                text.put(entry.getKey(), value.strip());
            }
        }
        return text;
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
    public void write(Path source, Path target, MetadataChanges changes) throws IOException {
        TiffImageMetadata exif = readExif(source);
        TiffOutputSet outputSet = null != exif ? exif.getOutputSet() : null;
        if (null == outputSet) {
            outputSet = new TiffOutputSet();
        }
        if (changes.isRemovePosition()) {
            outputSet = withoutGps(outputSet);
        }
        if (null != changes.getPosition()) {
            outputSet.setGpsInDegrees(changes.getPosition().getLongitude(), changes.getPosition().getLatitude());
        }
        if (null != changes.getAltitude() || changes.isRemoveAltitude()) {
            TiffOutputDirectory gps = changes.isRemoveAltitude() ? outputSet.getGpsDirectory()
                    : outputSet.getOrCreateGpsDirectory();
            if (null != gps) {
                gps.removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE);
                gps.removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF);
                if (null != changes.getAltitude()) {
                    double altitude = changes.getAltitude();
                    gps.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE, RationalNumber.valueOf(Math.abs(altitude)));
                    gps.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF, (byte) (altitude < 0
                            ? GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_BELOW_SEA_LEVEL
                            : GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_ABOVE_SEA_LEVEL));
                }
            }
        }
        if (null != changes.getDirection() || changes.isRemoveDirection()) {
            TiffOutputDirectory gps = changes.isRemoveDirection() ? outputSet.getGpsDirectory()
                    : outputSet.getOrCreateGpsDirectory();
            if (null != gps) {
                gps.removeField(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION);
                gps.removeField(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF);
                if (null != changes.getDirection()) {
                    gps.add(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION, RationalNumber.valueOf(changes.getDirection()));
                    gps.add(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF,
                            GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF_VALUE_TRUE_NORTH);
                }
            }
        }
        for (Map.Entry<TextTag, String> entry : changes.getText().entrySet()) {
            TagInfoAscii tag = TEXT_TAGS.get(entry.getKey());
            TiffOutputDirectory root = outputSet.getOrCreateRootDirectory();
            root.removeField(tag);
            if (!entry.getValue().isEmpty()) {
                root.add(tag, entry.getValue());
            }
        }
        if (null != changes.getTimeShift() && null != exif) {
            shiftTime(exif, outputSet, changes.getTimeShift());
        }
        if (null != changes.getTaken()) {
            setDateTime(outputSet.getOrCreateExifDirectory(), ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL,
                    changes.getTaken());
            // Sub-seconds of the old time don't belong to the new one
            outputSet.getOrCreateExifDirectory().removeField(ExifTagConstants.EXIF_TAG_SUB_SEC_TIME_ORIGINAL);
        }
        if (null == changes.getPlace()) {
            if (!outputSet.iterator().hasNext()) {
                // No EXIF at all and nothing to add
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            write(source, target, outputSet);
            return;
        }
        // The place goes into the XMP (and IPTC) segments, after the EXIF is done
        byte[] bytes;
        if (outputSet.iterator().hasNext()) {
            Path exifDone = Files.createTempFile(target.toAbsolutePath().getParent(), ".exiftweaker-", ".tmp");
            try {
                write(source, exifDone, outputSet);
                bytes = Files.readAllBytes(exifDone);
            } finally {
                Files.deleteIfExists(exifDone);
            }
        } else {
            bytes = Files.readAllBytes(source);
        }
        bytes = withPlace(bytes, changes.getPlace());
        Files.write(target, bytes);
    }

    /** The JPEG with its XMP place replaced, and its IPTC place too if it has IPTC data. */
    static byte[] withPlace(byte[] jpeg, Place place) throws IOException {
        String xmp = Imaging.getXmpXml(jpeg);
        String updated = XmpPlace.apply(xmp, place);
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 4096);
        new JpegXmpRewriter().updateXmpXml(jpeg, out, updated);
        byte[] result = out.toByteArray();
        JpegImageParser parser = new JpegImageParser();
        if (!parser.hasIptcSegment(ByteSource.array(result))) {
            // XMP is what today's tools read; no need to add a legacy IPTC block that wasn't there
            return result;
        }
        JpegPhotoshopMetadata photoshop = parser.getPhotoshopMetadata(ByteSource.array(result), null);
        if (null == photoshop || null == photoshop.photoshopApp13Data) {
            return result;
        }
        PhotoshopApp13Data data = photoshop.photoshopApp13Data;
        List<IptcRecord> records = new ArrayList<>();
        for (IptcRecord record : data.getRecords()) {
            if (!(record.iptcType instanceof IptcTypes t && IPTC_PLACE.contains(t))) {
                records.add(record);
            }
        }
        String[] values = {place.sublocation(), place.city(), place.state(), place.country(), place.countryCode3()};
        for (int i = 0; i < values.length; i++) {
            if (null != values[i]) {
                records.add(new IptcRecord(IPTC_PLACE.get(i), values[i]));
            }
        }
        // The IPTC digest (0x0425) no longer matches; without it, readers simply trust the data
        List<IptcBlock> blocks = data.getNonIptcBlocks().stream().filter(b -> b.getBlockType() != 0x0425).toList();
        out = new ByteArrayOutputStream(result.length + 1024);
        new JpegIptcRewriter().writeIptc(result, out, new PhotoshopApp13Data(records, blocks, true));
        return out.toByteArray();
    }

    /** DateTimeOriginal, DateTimeDigitized (EXIF directory) and DateTime (root) all move by {@code shift}. */
    private static void shiftTime(TiffImageMetadata exif, TiffOutputSet outputSet, Duration shift)
            throws ImagingException {
        TagInfoAscii[] tags = {ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL,
                ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, TiffTagConstants.TIFF_TAG_DATE_TIME};
        for (TagInfoAscii tag : tags) {
            LocalDateTime value = parseDateTime(asciiValue(exif, tag));
            if (null != value) {
                TiffOutputDirectory directory = tag == TiffTagConstants.TIFF_TAG_DATE_TIME
                        ? outputSet.getOrCreateRootDirectory() : outputSet.getOrCreateExifDirectory();
                setDateTime(directory, tag, value.plus(shift));
            }
        }
    }

    private static void setDateTime(TiffOutputDirectory directory, TagInfoAscii tag, LocalDateTime value)
            throws ImagingException {
        directory.removeField(tag);
        directory.add(tag, EXIF_DATE_TIME.format(value));
    }

    /**
     * TiffOutputSet can't remove a directory, so copy everything except the GPS directory into a new set. The GPS
     * pointer in the root directory is regenerated when writing, based on the directories present.
     */
    private static TiffOutputSet withoutGps(TiffOutputSet original) throws ImagingException {
        TiffOutputSet withoutGps = new TiffOutputSet(original.byteOrder);
        for (TiffOutputDirectory directory : original) {
            if (directory.getType() != TiffDirectoryConstants.DIRECTORY_TYPE_GPS) {
                withoutGps.addDirectory(directory);
            }
        }
        withoutGps.removeField(ExifTagConstants.EXIF_TAG_GPSINFO);
        return withoutGps;
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
