package me.rothens.gpsexif.video;

import org.jcodec.api.awt.AWTSequenceEncoder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The built-in, pure-Java H.264 encoder: works without FFmpeg, but is slower and makes larger files. */
public class JCodecEncoder implements VideoEncoder {

    private final AWTSequenceEncoder encoder;
    private final Path output;

    public JCodecEncoder(Path output, int fps) throws IOException {
        this.output = output;
        this.encoder = AWTSequenceEncoder.createSequenceEncoder(output.toFile(), fps);
    }

    @Override
    public void addFrame(BufferedImage frame) throws IOException {
        encoder.encodeImage(frame);
    }

    @Override
    public void finish() throws IOException {
        encoder.finish();
    }

    @Override
    public void abort() {
        try {
            encoder.finish();
        } catch (IOException | RuntimeException ignored) {
            // the file is deleted anyway
        }
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}
