package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import me.rothens.gpsexif.util.MiniJson;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backend on top of <a href="https://exiftool.org">ExifTool</a>, for formats commons-imaging can't write: PNG,
 * TIFF, WebP, HEIC/HEIF - written in place - and RAW files, whose metadata is written to an XMP sidecar
 * ({@code IMG_1234.xmp}) so the RAW file itself is never modified.
 */
public class ExifToolBackend implements MetadataBackend {

    private static final Set<String> IN_PLACE = Set.of("jpg", "jpeg", "png", "tif", "tiff", "webp", "heic", "heif",
            "avif");
    private static final Set<String> RAW = Set.of("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2",
            "orf", "rw2", "raf", "pef", "srw", "x3f", "3fr", "erf", "kdc", "mef", "mos", "mrw", "iiq", "rwl");

    /** All extensions this backend handles. */
    public static final Set<String> EXTENSIONS;

    static {
        Set<String> all = new java.util.HashSet<>(IN_PLACE);
        all.addAll(RAW);
        EXTENSIONS = Collections.unmodifiableSet(all);
    }

    private static final DateTimeFormatter EXIF_DATE_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private static final List<String> READ_TAGS = List.of(
            "-Composite:GPSLatitude", "-Composite:GPSLongitude", "-Composite:GPSAltitude",
            "-XMP-exif:GPSLatitude", "-XMP-exif:GPSLongitude", "-XMP-exif:GPSAltitude", "-XMP-exif:GPSAltitudeRef",
            "-GPSImgDirection", "-Orientation", "-DateTimeOriginal", "-SubSecTimeOriginal", "-OffsetTimeOriginal",
            "-CreateDate", "-Make", "-Model", "-Artist", "-Creator", "-Copyright", "-Rights", "-ImageDescription",
            "-Description", "-ExposureTime", "-FNumber", "-ISO", "-FocalLength", "-LensModel", "-ImageWidth",
            "-ImageHeight", "-XMP-photoshop:City", "-XMP-photoshop:State", "-XMP-photoshop:Country",
            "-XMP-iptcCore:Location", "-XMP-iptcCore:CountryCode", "-IPTC:City", "-IPTC:Sub-location",
            "-IPTC:Province-State", "-IPTC:Country-PrimaryLocationName", "-IPTC:Country-PrimaryLocationCode");

    /** XMP place tags, in the order of {@link Place}'s components. */
    private static final List<String> XMP_PLACE = List.of("XMP-iptcCore:Location", "XMP-photoshop:City",
            "XMP-photoshop:State", "XMP-photoshop:Country", "XMP-iptcCore:CountryCode");
    private static final List<String> IPTC_PLACE = List.of("IPTC:Sub-location", "IPTC:City", "IPTC:Province-State",
            "IPTC:Country-PrimaryLocationName", "IPTC:Country-PrimaryLocationCode");

    private final ExifTool exifTool;

    public ExifToolBackend(ExifTool exifTool) {
        this.exifTool = exifTool;
    }

    public ExifTool getExifTool() {
        return exifTool;
    }

    static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isRaw(Path file) {
        return RAW.contains(extension(file));
    }

    @Override
    public boolean canRead(Path file) {
        return EXTENSIONS.contains(extension(file));
    }

    @Override
    public boolean canWrite(Path file) {
        return canRead(file);
    }

    /** RAW files are written to an XMP sidecar: an existing {@code name.xmp} or {@code name.ext.xmp}, else {@code name.xmp}. */
    @Override
    public Path writeTarget(Path photo) {
        if (!isRaw(photo)) {
            return photo;
        }
        String name = photo.getFileName().toString();
        Path adobe = photo.resolveSibling(name.substring(0, name.lastIndexOf('.')) + ".xmp");
        Path withExtension = photo.resolveSibling(name + ".xmp");
        if (!Files.exists(adobe) && Files.exists(withExtension)) {
            return withExtension;
        }
        return adobe;
    }

    @Override
    public PhotoMetadata read(Path file) throws IOException {
        Map<String, Object> tags = readTags(file);
        Path sidecar = writeTarget(file);
        if (!sidecar.equals(file) && Files.exists(sidecar)) {
            // The sidecar's values win over the ones in the RAW file
            readTags(sidecar).forEach((k, v) -> {
                if (null != v) {
                    tags.put(k, v);
                }
            });
        }
        return toMetadata(tags);
    }

    /** Tag name (without group) to value, plus the group-qualified names for tags read from several groups. */
    private Map<String, Object> readTags(Path file) throws IOException {
        List<String> args = new ArrayList<>(List.of("-j", "-G1", "-n"));
        args.addAll(READ_TAGS);
        args.add(file.toString());
        Object json;
        try {
            json = MiniJson.parse(exifTool.execute(args));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unexpected ExifTool output for " + file.getFileName() + ": " + e.getMessage(), e);
        }
        Map<String, Object> tags = new HashMap<>();
        if (json instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = e.getKey().toString();
                tags.put(key, e.getValue());
                int colon = key.indexOf(':');
                String name = colon < 0 ? key : key.substring(colon + 1);
                tags.putIfAbsent(name, e.getValue());
            }
        }
        return tags;
    }

    static PhotoMetadata toMetadata(Map<String, Object> tags) {
        GeoPosition position = null;
        Double lat = number(tags.get("Composite:GPSLatitude"));
        Double lon = number(tags.get("Composite:GPSLongitude"));
        if (null == lat || null == lon) {
            lat = number(tags.get("XMP-exif:GPSLatitude"));
            lon = number(tags.get("XMP-exif:GPSLongitude"));
        }
        if (null != lat && null != lon && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
            position = new GeoPosition(lat, lon);
        }
        Double altitude = number(tags.get("Composite:GPSAltitude"));
        if (null == altitude && null != number(tags.get("XMP-exif:GPSAltitude"))) {
            altitude = number(tags.get("XMP-exif:GPSAltitude"));
            Double ref = number(tags.get("XMP-exif:GPSAltitudeRef"));
            if (null != ref && ref == 1) {
                altitude = -altitude;
            }
        }
        Double direction = number(tags.get("GPSImgDirection"));
        Double orientation = number(tags.get("Orientation"));

        String dateText = string(tags.get("DateTimeOriginal"));
        if (null == dateText) {
            dateText = string(tags.get("CreateDate"));
        }
        LocalDateTime taken = parseDateTime(dateText);
        ZoneOffset offset = parseOffset(string(tags.get("OffsetTimeOriginal")));
        if (null == offset && null != dateText && dateText.length() > 19) {
            // XMP dates may carry their own offset: 2026:09:30 10:00:00.25+02:00
            offset = parseOffset(dateText.replaceFirst("^.{19}(\\.\\d+)?", ""));
        }
        if (null != taken && dateText.length() == 19) {
            String subSec = string(tags.get("SubSecTimeOriginal"));
            if (null != subSec && subSec.matches("\\d{1,9}")) {
                taken = taken.withNano(Integer.parseInt((subSec + "000000000").substring(0, 9)));
            }
        }

        Map<TextTag, String> text = new EnumMap<>(TextTag.class);
        putText(text, TextTag.MAKE, tags.get("Make"));
        putText(text, TextTag.MODEL, tags.get("Model"));
        putText(text, TextTag.ARTIST, null != tags.get("Artist") ? tags.get("Artist") : tags.get("Creator"));
        putText(text, TextTag.COPYRIGHT, null != tags.get("Copyright") ? tags.get("Copyright") : tags.get("Rights"));
        putText(text, TextTag.DESCRIPTION, null != tags.get("ImageDescription") ? tags.get("ImageDescription")
                : tags.get("Description"));

        List<ExifData> fields = new ArrayList<>();
        Double exposure = number(tags.get("ExposureTime"));
        if (null != exposure && exposure > 0) {
            fields.add(new ExifData("ExposureTime", exposure < 1
                    ? "1/" + Math.round(1 / exposure) + " s" : trim(exposure) + " s"));
        }
        addInfo(fields, "FNumber", tags.get("FNumber"), "f/", "");
        addInfo(fields, "ISO", tags.get("ISO"), "", "");
        addInfo(fields, "FocalLength", tags.get("FocalLength"), "", " mm");
        addInfo(fields, "LensModel", tags.get("LensModel"), "", "");
        addInfo(fields, "ImageWidth", tags.get("ImageWidth"), "", "");
        addInfo(fields, "ImageHeight", tags.get("ImageHeight"), "", "");
        addInfo(fields, "Orientation", tags.get("Orientation"), "", "");
        Collections.sort(fields);

        int orient = null == orientation ? 1 : orientation.intValue();
        return new PhotoMetadata(position, orient >= 1 && orient <= 8 ? orient : 1, fields, taken, offset, altitude,
                null == direction ? null : MetadataChanges.normalizeDegrees(direction), text, readPlace(tags));
    }

    /** The XMP place, else the IPTC one, or {@code null}. */
    private static Place readPlace(Map<String, Object> tags) {
        for (List<String> names : List.of(XMP_PLACE, IPTC_PLACE)) {
            Place place = new Place(string(tags.get(names.get(0))), string(tags.get(names.get(1))),
                    string(tags.get(names.get(2))), string(tags.get(names.get(3))), string(tags.get(names.get(4))));
            if (!place.isEmpty()) {
                if (null != place.countryCode() && place.countryCode().length() == 3) {
                    place = new Place(place.sublocation(), place.city(), place.state(), place.country(),
                            Place.alpha2(place.countryCode()));
                }
                return place;
            }
        }
        return null;
    }


    /** Sets (or with {@link Place#NONE} clears) the XMP place; {@code clearIptc} also drops a stale IPTC one. */
    private static void placeArgs(List<String> a, Place place, boolean clearIptc) {
        if (null == place) {
            return;
        }
        String[] values = {place.sublocation(), place.city(), place.state(), place.country(), place.countryCode()};
        for (int i = 0; i < values.length; i++) {
            a.add("-" + XMP_PLACE.get(i) + "=" + (null == values[i] ? "" : values[i]));
            if (clearIptc) {
                a.add("-" + IPTC_PLACE.get(i) + "=");
            }
        }
    }

    @Override
    public void write(Path photo, Path target, MetadataChanges changes) throws IOException {
        Path writeTarget = writeTarget(photo);
        boolean sidecar = !writeTarget.equals(photo);
        List<String> tags = sidecar ? sidecarArgs(photo, changes) : inPlaceArgs(changes);
        Path source = sidecar && !Files.exists(writeTarget) ? photo : writeTarget;
        if (sidecar && source.equals(photo) && null != changes.getPlace()) {
            // A new sidecar is filled with the RAW file's own values (e.g. an old IPTC city), which would override
            // ours: write the place in a second step
            List<String> place = new ArrayList<>(List.of("-n", "-overwrite_original"));
            placeArgs(place, changes.getPlace(), false);
            tags.removeIf(a -> XMP_PLACE.stream().anyMatch(t -> a.startsWith("-" + t + "=")));
            if (tags.isEmpty()) {
                tags.add("-XMP-xmp:MetadataDate=now");
            }
            write(source, target, tags);
            place.add(target.toString());
            exifTool.execute(place);
            return;
        }
        if (tags.isEmpty()) {
            if (source.equals(photo) && sidecar) {
                throw new IOException("Nothing to write for " + photo.getFileName());
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        write(source, target, tags);
    }

    private void write(Path source, Path target, List<String> tags) throws IOException {
        // ExifTool refuses to overwrite with -o; the caller's (empty) temporary file is replaced
        Files.deleteIfExists(target);
        List<String> args = new ArrayList<>(List.of("-n"));
        args.addAll(tags);
        args.addAll(List.of("-o", target.toString(), source.toString()));
        exifTool.execute(args);
        if (!Files.exists(target)) {
            throw new IOException("ExifTool didn't write " + source.getFileName());
        }
    }

    /** Tags for formats that hold EXIF themselves (PNG, TIFF, WebP, HEIC, JPEG). */
    private static List<String> inPlaceArgs(MetadataChanges c) {
        List<String> a = new ArrayList<>();
        if (c.isRemovePosition()) {
            a.add("-GPS:all=");
            a.add("-XMP-exif:GPS*=");
        }
        if (null != c.getPosition()) {
            double lat = c.getPosition().getLatitude();
            double lon = c.getPosition().getLongitude();
            a.add("-GPS:GPSLatitude=" + Math.abs(lat));
            a.add("-GPS:GPSLatitudeRef=" + (lat < 0 ? "S" : "N"));
            a.add("-GPS:GPSLongitude=" + Math.abs(lon));
            a.add("-GPS:GPSLongitudeRef=" + (lon < 0 ? "W" : "E"));
        }
        if (null != c.getAltitude()) {
            a.add("-GPS:GPSAltitude=" + Math.abs(c.getAltitude()));
            a.add("-GPS:GPSAltitudeRef=" + (c.getAltitude() < 0 ? 1 : 0));
        } else if (c.isRemoveAltitude()) {
            a.add("-GPS:GPSAltitude=");
            a.add("-GPS:GPSAltitudeRef=");
        }
        if (null != c.getDirection()) {
            a.add("-GPS:GPSImgDirection=" + c.getDirection());
            a.add("-GPS:GPSImgDirectionRef=T");
        } else if (c.isRemoveDirection()) {
            a.add("-GPS:GPSImgDirection=");
            a.add("-GPS:GPSImgDirectionRef=");
        }
        Map<TextTag, String> names = Map.of(TextTag.MAKE, "IFD0:Make", TextTag.MODEL, "IFD0:Model",
                TextTag.ARTIST, "IFD0:Artist", TextTag.COPYRIGHT, "IFD0:Copyright",
                TextTag.DESCRIPTION, "IFD0:ImageDescription");
        c.getText().forEach((field, value) -> a.add("-" + names.get(field) + "=" + value));
        if (null != c.getTimeShift() && !c.getTimeShift().isZero()) {
            a.add("-AllDates" + (c.getTimeShift().isNegative() ? "-=" : "+=") + shiftValue(c.getTimeShift().abs()));
        }
        if (null != c.getTaken()) {
            a.add("-ExifIFD:DateTimeOriginal=" + EXIF_DATE_TIME.format(c.getTaken()));
            a.add("-ExifIFD:SubSecTimeOriginal=");
        }
        placeArgs(a, c.getPlace(), true);
        return a;
    }

    /** Tags for an XMP sidecar next to a RAW file. */
    private List<String> sidecarArgs(Path photo, MetadataChanges c) throws IOException {
        List<String> a = new ArrayList<>();
        if (c.isRemovePosition()) {
            a.add("-XMP-exif:GPS*=");
        }
        if (null != c.getPosition()) {
            a.add("-XMP-exif:GPSLatitude=" + c.getPosition().getLatitude());
            a.add("-XMP-exif:GPSLongitude=" + c.getPosition().getLongitude());
        }
        if (null != c.getAltitude()) {
            a.add("-XMP-exif:GPSAltitude=" + Math.abs(c.getAltitude()));
            a.add("-XMP-exif:GPSAltitudeRef=" + (c.getAltitude() < 0 ? 1 : 0));
        } else if (c.isRemoveAltitude()) {
            a.add("-XMP-exif:GPSAltitude=");
            a.add("-XMP-exif:GPSAltitudeRef=");
        }
        if (null != c.getDirection()) {
            a.add("-XMP-exif:GPSImgDirection=" + c.getDirection());
            a.add("-XMP-exif:GPSImgDirectionRef=T");
        } else if (c.isRemoveDirection()) {
            a.add("-XMP-exif:GPSImgDirection=");
            a.add("-XMP-exif:GPSImgDirectionRef=");
        }
        for (Map.Entry<TextTag, String> e : c.getText().entrySet()) {
            String tag = switch (e.getKey()) {
                case MAKE -> "XMP-tiff:Make";
                case MODEL -> "XMP-tiff:Model";
                case ARTIST -> "XMP-dc:Creator";
                case COPYRIGHT -> "XMP-dc:Rights";
                case DESCRIPTION -> "XMP-dc:Description";
            };
            if (e.getKey() == TextTag.ARTIST) {
                a.add("-" + tag + "="); // Creator is a list: clear it, or "=" would append
            }
            if (!e.getValue().isEmpty() || e.getKey() != TextTag.ARTIST) {
                a.add("-" + tag + "=" + e.getValue());
            }
        }
        LocalDateTime taken = c.getTaken();
        if (null == taken && null != c.getTimeShift() && !c.getTimeShift().isZero()) {
            // The date may only be in the RAW file, so compute the new value rather than shifting the sidecar's
            LocalDateTime current = read(photo).taken();
            if (null != current) {
                taken = current.withNano(0).plus(c.getTimeShift());
            }
        }
        if (null != taken) {
            a.add("-XMP-exif:DateTimeOriginal=" + EXIF_DATE_TIME.format(taken));
        }
        placeArgs(a, c.getPlace(), false);
        return a;
    }

    /** ExifTool's date shift format: "Y:M:D h:m:s". */
    static String shiftValue(Duration d) {
        long s = d.getSeconds();
        return String.format(Locale.ROOT, "0:0:%d %d:%d:%d", s / 86400, (s / 3600) % 24, (s / 60) % 60, s % 60);
    }

    /** Embedded preview of RAW (and some other) files, largest usable first. */
    @Override
    public byte[] preview(Path photo) throws IOException {
        Object json = MiniJson.parse(exifTool.execute(List.of("-j", "-b", "-PreviewImage", "-JpgFromRaw",
                "-ThumbnailImage", photo.toString())));
        if (json instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
            for (String tag : new String[]{"PreviewImage", "JpgFromRaw", "ThumbnailImage"}) {
                if (map.get(tag) instanceof String s && s.startsWith("base64:")) {
                    return Base64.getDecoder().decode(s.substring("base64:".length()));
                }
            }
        }
        return null;
    }

    private static void putText(Map<TextTag, String> text, TextTag field, Object value) {
        String s = value instanceof List<?> list ? String.join("; ", list.stream().map(String::valueOf).toList())
                : string(value);
        if (null != s && !s.isBlank()) {
            text.put(field, s.strip());
        }
    }

    private static void addInfo(List<ExifData> fields, String name, Object value, String prefix, String suffix) {
        if (null != value) {
            String s = value instanceof Double d ? trim(d) : value.toString();
            fields.add(new ExifData(name, prefix + s + suffix));
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static Double number(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String string(Object value) {
        return null == value ? null : value instanceof Double d ? trim(d) : value.toString();
    }

    private static LocalDateTime parseDateTime(String text) {
        if (null == text || text.length() < 19) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.substring(0, 19), EXIF_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ZoneOffset parseOffset(String text) {
        if (null == text || text.isBlank()) {
            return null;
        }
        try {
            return ZoneOffset.of(text.strip().equals("Z") ? "Z" : text.strip());
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
