package me.rothens.gpsexif.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ImageOrientationTest {

    private static final int MARK = 0xFF0000;

    /** 3x2 image with only the top-left pixel set. */
    private static BufferedImage marked() {
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, MARK);
        return img;
    }

    @Test
    void normalAndUnknownOrientationsAreUntouched() {
        BufferedImage img = marked();
        assertSame(img, ImageOrientation.apply(img, 1));
        assertSame(img, ImageOrientation.apply(img, 0));
        assertSame(img, ImageOrientation.apply(img, 9));
        assertNull(ImageOrientation.apply(null, 6));
    }

    /** Where the stored top-left pixel ends up on screen, per EXIF orientation. */
    @ParameterizedTest
    @CsvSource({
            "2, 3, 2, 2, 0",
            "3, 3, 2, 2, 1",
            "4, 3, 2, 0, 1",
            "5, 2, 3, 0, 0",
            "6, 2, 3, 1, 0",
            "7, 2, 3, 1, 2",
            "8, 2, 3, 0, 2",
    })
    void movesTopLeftPixel(int orientation, int width, int height, int x, int y) {
        BufferedImage out = ImageOrientation.apply(marked(), orientation);
        assertEquals(width, out.getWidth());
        assertEquals(height, out.getHeight());
        assertEquals(MARK, out.getRGB(x, y) & 0xFFFFFF);
        int marks = 0;
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                if ((out.getRGB(xx, yy) & 0xFFFFFF) == MARK) {
                    marks++;
                }
            }
        }
        assertEquals(1, marks);
    }
}
