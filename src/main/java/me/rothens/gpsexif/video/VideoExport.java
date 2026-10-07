package me.rothens.gpsexif.video;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Renders every frame of a {@link FrameSource} and feeds it to a {@link VideoEncoder}. */
public final class VideoExport {

    private VideoExport() {
    }

    /**
     * Runs the export on the calling (background) thread.
     *
     * @param progress  called with the number of frames written so far
     * @param cancelled checked between frames; the partly written file is deleted when it returns true
     * @return {@code false} if cancelled
     */
    public static boolean run(FrameSource source, VideoEncoder encoder, int width, int height,
                              IntConsumer progress, BooleanSupplier cancelled) throws Exception {
        boolean done = false;
        try {
            BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            int count = source.getFrameCount();
            for (int i = 0; i < count; i++) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                source.render(i, frame);
                encoder.addFrame(frame);
                progress.accept(i + 1);
            }
            if (cancelled.getAsBoolean()) {
                return false;
            }
            encoder.finish();
            done = true;
            return true;
        } finally {
            if (!done) {
                encoder.abort();
            }
            source.close();
        }
    }

    /** Creates the FFmpeg encoder if {@code ffmpeg} is set, else the built-in one. */
    public static VideoEncoder createEncoder(String ffmpeg, java.nio.file.Path output, int width, int height, int fps)
            throws IOException {
        return null != ffmpeg ? new FfmpegEncoder(ffmpeg, output, width, height, fps)
                : new JCodecEncoder(output, fps);
    }
}
