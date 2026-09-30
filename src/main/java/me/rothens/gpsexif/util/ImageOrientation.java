package me.rothens.gpsexif.util;

import java.awt.image.BufferedImage;

/** Applies the EXIF {@code Orientation} tag (values 1-8) to an image so it's displayed upright. */
public final class ImageOrientation {

    private ImageOrientation() {
    }

    /**
     * Returns {@code image} transformed for display. Orientation 1 and unknown values return the image itself;
     * values 5-8 swap width and height.
     */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        if (null == image || orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, type);

        int[] src = image.getRGB(0, 0, w, h, null, 0, w);
        int outWidth = out.getWidth();
        int[] dst = new int[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = w - 1 - x; dy = y; }             // mirror horizontal
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }     // rotate 180
                    case 4 -> { dx = x; dy = h - 1 - y; }             // mirror vertical
                    case 5 -> { dx = y; dy = x; }                     // transpose
                    case 6 -> { dx = h - 1 - y; dy = x; }             // rotate 90 CW
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }     // transverse
                    default -> { dx = y; dy = w - 1 - x; }            // 8: rotate 90 CCW
                }
                dst[dy * outWidth + dx] = src[y * w + x];
            }
        }
        out.setRGB(0, 0, out.getWidth(), out.getHeight(), dst, 0, outWidth);
        return out;
    }
}
