package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.OSMTileFactoryInfo;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Renders playback and travel frames off screen, as the video export does. */
class VideoFramesTest {

    @TempDir
    Path dir;

    /** No tile server: the tiles fail at once, and the frames are rendered without them. */
    private final DefaultTileFactory tiles = new DefaultTileFactory(new OSMTileFactoryInfo("none", "http://127.0.0.1:9"));
    private final ClockZone clock = new ClockZone(ZoneOffset.UTC, null);

    @AfterEach
    void tearDown() {
        tiles.dispose();
    }

    private ImageFile photo(String name, Color color, LocalDateTime taken, GeoPosition position) throws Exception {
        BufferedImage img = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 160, 90);
        g.dispose();
        Path f = dir.resolve(name);
        ImageIO.write(img, "png", f.toFile());
        Path jpg = dir.resolve(name.replace(".png", ".jpg"));
        ImageIO.write(ImageIO.read(f.toFile()), "jpg", jpg.toFile());
        ImageFile image = new ImageFile(jpg.toFile(), new CommonsImagingBackend());
        MetadataChanges changes = new MetadataChanges().taken(taken);
        if (null != position) {
            changes.position(position);
        }
        image.apply(changes);
        return image;
    }

    private static void assertColor(Color expected, BufferedImage frame) {
        Color actual = new Color(frame.getRGB(frame.getWidth() / 2, frame.getHeight() / 2));
        String message = "expected " + expected + " but was " + actual;
        assertEquals(expected.getRed(), actual.getRed(), 20, message);
        assertEquals(expected.getGreen(), actual.getGreen(), 20, message);
        assertEquals(expected.getBlue(), actual.getBlue(), 20, message);
    }

    private static <T> T edt(java.util.concurrent.Callable<T> task) throws Exception {
        return OffscreenFrames.onEdt(task);
    }

    @Test
    void playbackShowsEachPhotoAndCrossFades() throws Exception {
        PlaybackSequence sequence = new PlaybackSequence(List.of(
                photo("a.png", Color.RED, LocalDateTime.of(2026, 10, 1, 9, 0), null),
                photo("b.png", Color.BLUE, LocalDateTime.of(2026, 10, 1, 10, 0), null)));
        PlaybackFrames frames = edt(() -> new PlaybackFrames(sequence, clock, tiles, 320, 180, 10, 1.0, 0.4));
        assertEquals(20, frames.getFrameCount());
        BufferedImage frame = new BufferedImage(320, 180, BufferedImage.TYPE_3BYTE_BGR);
        frames.render(0, frame);
        assertColor(Color.RED, frame);
        frames.render(8, frame); // halfway through the fade
        Color mid = new Color(frame.getRGB(160, 90));
        assertTrue(mid.getRed() > 60 && mid.getBlue() > 60, "a blend of both: " + mid);
        frames.render(12, frame);
        assertColor(Color.BLUE, frame);
        frames.render(19, frame); // the last photo doesn't fade out
        assertColor(Color.BLUE, frame);
        assertEquals("", frames.getWarnings());
    }

    @Test
    void travelFramesFollowTheTimeline() throws Exception {
        GeoPosition home = new GeoPosition(47.5, 19.05);
        GeoPosition lake = new GeoPosition(46.9, 17.9);
        List<ImageFile> photos = List.of(
                photo("a.png", Color.RED, LocalDateTime.of(2026, 10, 1, 9, 0), home),
                photo("b.png", Color.BLUE, LocalDateTime.of(2026, 10, 1, 12, 0), lake));
        TravelTimeline timeline = new TravelTimeline(photos, clock::instant, List.of(),
                new TravelTimeline.Settings(Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofMillis(500),
                        true, Duration.ofHours(1), 2000, Duration.ofSeconds(2)));
        TravelFrames frames = edt(() -> new TravelFrames(timeline, List.of(timeline.getStraightRoute()), clock,
                tiles, 320, 180, 10, 0.25, false, -1));
        assertEquals(100, frames.getFrameCount());
        BufferedImage frame = new BufferedImage(320, 180, BufferedImage.TYPE_3BYTE_BGR);
        frames.render(10, frame); // 1 s: the first photo
        assertColor(Color.RED, frame);
        frames.render(99, frame); // the end: the last photo
        assertColor(Color.BLUE, frame);
        assertTrue(frames.getWarnings().contains("map tile"), frames.getWarnings());
    }

    @Test
    void overlaysAreScaledToTheVideoSize() throws Exception {
        PlaybackSequence sequence = new PlaybackSequence(List.of(
                photo("a.png", Color.BLACK, LocalDateTime.of(2026, 10, 1, 9, 0), null)));
        int[] boxWidth = new int[2];
        int i = 0;
        for (int width : new int[]{1280, 3840}) {
            int height = width * 9 / 16;
            PlaybackFrames frames = edt(() -> new PlaybackFrames(sequence, clock, tiles, width, height, 1, 1, 0));
            BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            frames.render(0, frame);
            // The timestamp box is lighter than the black photo; measure its width along a row through it
            int y = height * 40 / 720;
            int right = 0;
            for (int x = 0; x < width / 2; x++) {
                if (new Color(frame.getRGB(x, y)).getRed() > 8) {
                    right = x;
                }
            }
            boxWidth[i++] = right;
        }
        assertEquals(3.0, (double) boxWidth[1] / boxWidth[0], 0.25);
    }
}
