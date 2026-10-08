package me.rothens.gpsexif.share;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.ExifTool;
import me.rothens.gpsexif.metadata.ExifToolBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.XmpPlace;
import me.rothens.gpsexif.metadata.Place;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.PhotoLoader;
import me.rothens.gpsexif.util.ThumbnailCache;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saves copies of photos for sharing, leaving the originals untouched: with all metadata, without the location
 * (GPS and place name), or without any metadata at all (only the orientation is kept, so photos don't turn
 * sideways). Optionally smaller: then the copy is a new JPEG, turned upright. RAW files are always shared as JPEG.
 */
public final class ShareExport {

    /** How much of the metadata the copies keep. */
    public enum Privacy {
        ALL("All metadata"),
        NO_LOCATION("Without location"),
        NONE("Without any metadata");

        private final String label;

        Privacy(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @param maxSize longest side in pixels, or 0 for the original size
     * @param quality JPEG quality 0..1, used when a photo is encoded anew
     */
    public record Options(Privacy privacy, int maxSize, float quality) {
    }

    private final CommonsImagingBackend jpegBackend = new CommonsImagingBackend();
    private final ExifToolBackend exifTool;

    /** @param exifTool for formats other than JPEG; {@code null} if it isn't installed */
    public ShareExport(ExifToolBackend exifTool) {
        this.exifTool = exifTool;
    }

    /**
     * Writes a copy of {@code photo} into {@code folder}, under its own name or with " (2)" if that's taken.
     *
     * @return the copy
     */
    public Path export(ImageFile photo, Path folder, Options options) throws IOException {
        Path source = photo.getPath();
        boolean jpeg = isJpeg(source);
        boolean encode = options.maxSize() > 0 || ExifToolBackend.isRaw(source);
        String name = source.getFileName().toString();
        if (encode && !jpeg) {
            name = base(name) + ".jpg";
        }
        Files.createDirectories(folder);
        Path target = freeName(folder, name);
        Path temp = Files.createTempFile(folder, ".exiftweaker-share-", ".tmp");
        try {
            if (encode) {
                encode(photo, temp, options);
            } else if (options.privacy() == Privacy.ALL) {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            } else if (jpeg) {
                if (options.privacy() == Privacy.NO_LOCATION) {
                    jpegBackend.write(source, temp, new MetadataChanges().removePosition());
                } else {
                    Files.write(temp, withoutMetadata(Files.readAllBytes(source), photo.getOrientation()));
                }
            } else {
                exifTool(source).execute(withoutMetadataArgs(source, temp, options.privacy()));
            }
            Files.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private ExifTool exifTool(Path source) throws IOException {
        if (null == exifTool) {
            throw new IOException(extension(source).toUpperCase(Locale.ROOT) + " files need ExifTool for this");
        }
        return exifTool.getExifTool();
    }

    /** ExifTool arguments that remove the location: GPS (EXIF and XMP) and the place name (XMP and IPTC). */
    private static final List<String> LOCATION_TAGS = List.of("-GPS:all=", "-XMP-exif:GPS*=",
            "-XMP-photoshop:City=", "-XMP-photoshop:State=", "-XMP-photoshop:Country=", "-XMP-iptcCore:Location=",
            "-XMP-iptcCore:CountryCode=", "-IPTC:City=", "-IPTC:Sub-location=", "-IPTC:Province-State=",
            "-IPTC:Country-PrimaryLocationName=", "-IPTC:Country-PrimaryLocationCode=",
            "-XMP-exiftweaker:District=");

    /** ExifTool arguments that write a copy without the location / any metadata (keeping the orientation). */
    private static List<String> withoutMetadataArgs(Path source, Path target, Privacy privacy) throws IOException {
        Files.deleteIfExists(target); // ExifTool won't overwrite
        List<String> args = new ArrayList<>(List.of("-n"));
        if (privacy == Privacy.NO_LOCATION) {
            args.addAll(LOCATION_TAGS);
        } else {
            args.addAll(List.of("-all=", "-tagsFromFile", "@", "-Orientation", "-ICC_Profile"));
        }
        args.addAll(List.of("-o", target.toString(), source.toString()));
        return args;
    }

    /** A new JPEG: upright, at most {@code maxSize}, with the metadata the options keep. */
    private void encode(ImageFile photo, Path target, Options options) throws IOException {
        int size = options.maxSize() > 0 ? options.maxSize() : 100_000;
        BufferedImage picture = PhotoLoader.load(photo, size);
        if (null == picture) {
            throw new IOException("Couldn't read the picture" + (null == exifTool ? " (this file type needs ExifTool)"
                    : ""));
        }
        if (Math.max(picture.getWidth(), picture.getHeight()) > size || picture.getType() != BufferedImage.TYPE_INT_RGB) {
            picture = ThumbnailCache.scale(rgb(picture), size);
        }
        byte[] jpeg = encodeJpeg(picture, options.quality());
        Path source = photo.getPath();
        if (options.privacy() != Privacy.NONE) {
            if (isJpeg(source)) {
                jpeg = withMetadataOf(source, jpeg, options.privacy() == Privacy.NO_LOCATION,
                        picture.getWidth(), picture.getHeight());
            } else if (null != exifTool) {
                Files.write(target, jpeg);
                List<String> args = new ArrayList<>(List.of("-n", "-overwrite_original", "-tagsFromFile",
                        source.toString(), "-all:all", "-ThumbnailImage=", "-PreviewImage=", "-IFD0:Orientation=1",
                        "-ExifIFD:ExifImageWidth=" + picture.getWidth(),
                        "-ExifIFD:ExifImageHeight=" + picture.getHeight()));
                if (options.privacy() == Privacy.NO_LOCATION) {
                    args.addAll(LOCATION_TAGS);
                }
                args.add(target.toString());
                exifTool.getExifTool().execute(args);
                return;
            }
        }
        Files.write(target, jpeg);
    }

    /** Copies EXIF and XMP of {@code source} into the newly encoded {@code jpeg} (upright, no thumbnail). */
    static byte[] withMetadataOf(Path source, byte[] jpeg, boolean removeLocation, int width, int height)
            throws IOException {
        ImageMetadata metadata = Imaging.getMetadata(source.toFile());
        TiffImageMetadata exif = metadata instanceof JpegImageMetadata m ? m.getExif() : null;
        byte[] result = jpeg;
        if (null != exif) {
            TiffOutputSet original = exif.getOutputSet();
            TiffOutputSet set = new TiffOutputSet(original.byteOrder);
            for (TiffOutputDirectory directory : original) {
                int type = directory.getType();
                // IFD1 and later hold the (now sideways and too large) thumbnail
                if (type >= TiffDirectoryConstants.DIRECTORY_TYPE_SUB
                        || (removeLocation && type == TiffDirectoryConstants.DIRECTORY_TYPE_GPS)) {
                    continue;
                }
                set.addDirectory(directory);
            }
            if (removeLocation) {
                set.removeField(ExifTagConstants.EXIF_TAG_GPSINFO);
            }
            TiffOutputDirectory root = set.getOrCreateRootDirectory();
            root.removeField(TiffTagConstants.TIFF_TAG_ORIENTATION);
            root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) 1);
            TiffOutputDirectory exifDir = set.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_EXIF);
            if (null != exifDir) {
                exifDir.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH);
                exifDir.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH);
                exifDir.add(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH, (short) width); // unsigned 16 bit
                exifDir.add(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH, (short) height);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(result.length + 65536);
            new ExifRewriter().updateExifMetadataLossy(result, out, set);
            result = out.toByteArray();
        }
        String xmp = Imaging.getXmpXml(source.toFile());
        if (null != xmp) {
            String kept = removeLocation ? XmpPlace.apply(xmp, Place.NONE, true) : xmp;
            ByteArrayOutputStream out = new ByteArrayOutputStream(result.length + kept.length() + 1024);
            new JpegXmpRewriter().updateXmpXml(result, out, kept);
            result = out.toByteArray();
        }
        return result;
    }

    private static BufferedImage rgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE); // transparent PNG areas
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    static byte[] encodeJpeg(BufferedImage picture, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            writer.write(null, new IIOImage(picture, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * The JPEG without any metadata segments (EXIF, XMP, IPTC, comments, maker data), byte for byte the same
     * picture. The color profile stays, and an orientation other than "normal" is written back so the photo isn't
     * shown sideways.
     */
    static byte[] withoutMetadata(byte[] jpeg, int orientation) throws IOException {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("Not a JPEG file");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(jpeg, 0, 2);
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                throw new IOException("Broken JPEG file");
            }
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            if (marker == 0xDA) { // start of scan: the picture itself, copied as it is
                out.write(jpeg, pos, jpeg.length - pos);
                break;
            }
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > jpeg.length) {
                throw new IOException("Broken JPEG file");
            }
            if (keep(jpeg, pos, marker)) {
                out.write(jpeg, pos, 2 + length);
            }
            pos += 2 + length;
        }
        byte[] stripped = out.toByteArray();
        if (orientation == 1) {
            return stripped;
        }
        TiffOutputSet set = new TiffOutputSet();
        set.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) orientation);
        ByteArrayOutputStream withOrientation = new ByteArrayOutputStream(stripped.length + 256);
        new ExifRewriter().updateExifMetadataLossless(stripped, withOrientation, set);
        return withOrientation.toByteArray();
    }

    /** Keeps everything but metadata: JFIF (APP0), the color profile (APP2 ICC_PROFILE), Adobe's color info (APP14). */
    private static boolean keep(byte[] jpeg, int pos, int marker) {
        if (marker == 0xFE) {
            return false; // comment
        }
        if (marker < 0xE0 || marker > 0xEF) {
            return true; // tables, frame header, restart interval, ...
        }
        if (marker == 0xE0 || marker == 0xEE) {
            return true;
        }
        if (marker == 0xE2) {
            String id = new String(jpeg, pos + 4, Math.min(11, jpeg.length - pos - 4),
                    java.nio.charset.StandardCharsets.US_ASCII);
            return id.startsWith("ICC_PROFILE");
        }
        return false;
    }

    /** {@code name} in {@code folder}, or "name (2).ext", ... if it's taken. */
    static Path freeName(Path folder, String name) {
        Path candidate = folder.resolve(name);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = folder.resolve(base(name) + " (" + i + ")" + name.substring(base(name).length()));
        }
        return candidate;
    }

    private static boolean isJpeg(Path file) {
        String ext = extension(file);
        return ext.equals("jpg") || ext.equals("jpeg");
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String base(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
