package me.rothens.gpsexif.util;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailCacheTest {

    @TempDir
    Path dir;

    @Test
    void barsAroundAnEmbeddedThumbnailAreCutOff() {
        // A 160x120 (4:3) thumbnail of a 3:2 photo has black bars at the top and bottom
        BufferedImage thumbnail = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        BufferedImage cropped = ThumbnailCache.cropToAspect(thumbnail, new Dimension(6000, 4000));
        assertEquals(160, cropped.getWidth());
        assertEquals(107, cropped.getHeight());
        // Portrait photo: bars left and right
        cropped = ThumbnailCache.cropToAspect(thumbnail, new Dimension(3000, 4000));
        assertEquals(90, cropped.getWidth());
        assertEquals(120, cropped.getHeight());
        // Same shape: untouched
        assertSame(thumbnail, ThumbnailCache.cropToAspect(thumbnail, new Dimension(4000, 3000)));
    }

    @Test
    void scalesDownToTheLongestSideButNeverUp() {
        BufferedImage big = ThumbnailCache.scale(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB), 96);
        assertEquals(96, big.getWidth());
        assertEquals(72, big.getHeight());
        BufferedImage small = ThumbnailCache.scale(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), 96);
        assertEquals(40, small.getWidth());
    }

    @Test
    void loadsInTheBackgroundAndRemembersFailures() throws Exception {
        Path jpg = dir.resolve("a.jpg");
        BufferedImage picture = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ImageIO.write(picture, "jpg", jpg.toFile());
        Path broken = dir.resolve("broken.jpg");
        java.nio.file.Files.writeString(broken, "not a photo");
        ImageFile good = new ImageFile(jpg.toFile(), new CommonsImagingBackend());
        ImageFile bad = new ImageFile(broken.toFile(), new CommonsImagingBackend());

        CountDownLatch loaded = new CountDownLatch(2);
        ThumbnailCache cache = new ThumbnailCache(96, 10, loaded::countDown);
        assertNull(cache.get(good)); // not loaded yet
        assertNull(cache.get(bad));
        assertTrue(loaded.await(10, TimeUnit.SECONDS));
        BufferedImage thumbnail = cache.get(good);
        assertNotNull(thumbnail);
        assertEquals(96, thumbnail.getWidth());
        assertEquals(64, thumbnail.getHeight());
        assertEquals(Color.RED.getRGB() & 0xF0F0F0, thumbnail.getRGB(48, 32) & 0xF0F0F0);
        assertNull(cache.get(bad));
        assertTrue(cache.hasFailed(bad));

        cache.clear();
        assertFalse(cache.hasFailed(bad));
    }
}
