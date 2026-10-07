package me.rothens.gpsexif.util;

import me.rothens.gpsexif.model.ImageFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/** Loads photos for display: decoded by Java where possible, else the embedded preview; upright. */
public final class PhotoLoader {

    private PhotoLoader() {
    }

    /**
     * Loads {@code image} at roughly {@code maxSize} pixels on its longest side (at least), turned upright.
     * Returns {@code null} if neither Java nor the backend's embedded preview can provide a picture.
     */
    public static BufferedImage load(ImageFile image, int maxSize) throws IOException {
        BufferedImage picture = null;
        try {
            picture = readSubsampled(image.getFile(), maxSize);
        } catch (IOException | RuntimeException e) {
            // Not decodable by Java (HEIC, RAW) - try the embedded preview below
        }
        if (null == picture) {
            byte[] preview = image.getBackend().preview(image.getPath());
            if (null != preview) {
                picture = ImageIO.read(new ByteArrayInputStream(preview));
            }
        }
        return ImageOrientation.apply(picture, image.getOrientation());
    }

    /** Decodes only every n-th pixel so large photos don't have to be fully loaded into memory. */
    public static BufferedImage readSubsampled(File file, int maxSize) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            if (null == in) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, longest / maxSize);
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }
}
