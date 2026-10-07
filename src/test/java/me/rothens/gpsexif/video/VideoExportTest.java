package me.rothens.gpsexif.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VideoExportTest {

    @TempDir
    Path dir;

    /** Frames with a moving bar, so the encoder has something to do. */
    private static class Bars implements FrameSource {
        final int count;
        final AtomicBoolean closed = new AtomicBoolean();

        Bars(int count) {
            this.count = count;
        }

        @Override
        public int getFrameCount() {
            return count;
        }

        @Override
        public void render(int index, BufferedImage target) {
            Graphics2D g = target.createGraphics();
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, target.getWidth(), target.getHeight());
            g.setColor(Color.ORANGE);
            g.fillRect(index * 4, 0, 20, target.getHeight());
            g.dispose();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** An MP4 file starts with an "ftyp" box. */
    private static void assertMp4(Path file) throws IOException {
        byte[] head = new byte[8];
        try (var in = Files.newInputStream(file)) {
            assertEquals(8, in.read(head));
        }
        assertEquals("ftyp", new String(head, 4, 4, StandardCharsets.US_ASCII));
        assertTrue(Files.size(file) > 1000);
    }

    @Test
    void builtInEncoderWritesAnMp4() throws Exception {
        Path out = dir.resolve("built-in.mp4");
        Bars bars = new Bars(30);
        AtomicInteger progress = new AtomicInteger();
        assertTrue(VideoExport.run(bars, VideoExport.createEncoder(null, out, 320, 240, 30), 320, 240,
                progress::set, () -> false));
        assertEquals(30, progress.get());
        assertTrue(bars.closed.get());
        assertMp4(out);
    }

    @Test
    void ffmpegWritesAnMp4() throws Exception {
        String ffmpeg = Ffmpeg.locate("");
        assumeTrue(null != ffmpeg, "FFmpeg isn't installed");
        assertNotNull(Ffmpeg.version(ffmpeg));
        Path out = dir.resolve("ffmpeg.mp4");
        assertTrue(VideoExport.run(new Bars(30), VideoExport.createEncoder(ffmpeg, out, 320, 240, 30), 320, 240,
                done -> { }, () -> false));
        assertMp4(out);
    }

    @Test
    void cancellingDeletesThePartlyWrittenFile() throws Exception {
        for (String ffmpeg : new String[]{null, Ffmpeg.locate("")}) {
            Path out = dir.resolve("cancelled" + (null == ffmpeg ? "" : "-ffmpeg") + ".mp4");
            Bars bars = new Bars(30);
            AtomicInteger progress = new AtomicInteger();
            assertFalse(VideoExport.run(bars, VideoExport.createEncoder(ffmpeg, out, 320, 240, 30), 320, 240,
                    progress::set, () -> progress.get() >= 5));
            assertEquals(5, progress.get());
            assertTrue(bars.closed.get());
            assertFalse(Files.exists(out));
            if (null == Ffmpeg.locate("")) {
                break;
            }
        }
    }

    @Test
    void aFailingFrameStopsTheExportAndCleansUp() throws Exception {
        Path out = dir.resolve("failed.mp4");
        Bars bars = new Bars(30) {
            @Override
            public void render(int index, BufferedImage target) {
                if (index == 3) {
                    throw new IllegalStateException("broken frame");
                }
                super.render(index, target);
            }
        };
        assertThrows(IllegalStateException.class, () -> VideoExport.run(bars,
                VideoExport.createEncoder(null, out, 320, 240, 30), 320, 240, done -> { }, () -> false));
        assertTrue(bars.closed.get());
        assertFalse(Files.exists(out));
    }

    @Test
    void ffmpegIsNotFoundAtABadPath() {
        assertNull(Ffmpeg.version(dir.resolve("no-such-ffmpeg").toString()));
    }
}
