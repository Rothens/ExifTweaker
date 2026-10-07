package me.rothens.gpsexif.video;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Pipes raw frames into an FFmpeg process that writes an H.264 MP4. */
public class FfmpegEncoder implements VideoEncoder {

    private final Process process;
    private final OutputStream frames;
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private final Thread logReader;
    private final Path output;
    private final int width;
    private final int height;

    public FfmpegEncoder(String executable, Path output, int width, int height, int fps) throws IOException {
        if (width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Width and height must be even");
        }
        this.output = output;
        this.width = width;
        this.height = height;
        List<String> command = new ArrayList<>(List.of(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "bgr24", "-video_size", width + "x" + height,
                "-framerate", Integer.toString(fps), "-i", "-"));
        if (Ffmpeg.hasX264(executable)) {
            command.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "20"));
        }
        command.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", output.toString()));
        process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        frames = process.getOutputStream();
        logReader = new Thread(() -> {
            try (InputStream err = process.getErrorStream()) {
                err.transferTo(log);
            } catch (IOException ignored) {
                // the process ended
            }
        }, "ffmpeg-log");
        logReader.setDaemon(true);
        logReader.start();
    }

    @Override
    public void addFrame(BufferedImage frame) throws IOException {
        if (frame.getType() != BufferedImage.TYPE_3BYTE_BGR || frame.getWidth() != width
                || frame.getHeight() != height) {
            throw new IllegalArgumentException("Expected a " + width + "x" + height + " TYPE_3BYTE_BGR frame");
        }
        try {
            frames.write(((DataBufferByte) frame.getRaster().getDataBuffer()).getData());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @Override
    public void finish() throws IOException {
        try {
            frames.close();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("FFmpeg didn't finish the video");
            }
            logReader.join(2000);
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg failed: " + logText());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort();
            throw new IOException("Interrupted", e);
        }
    }

    @Override
    public void abort() {
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // nothing more to do
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** FFmpeg closed the pipe: it stopped with an error, which is more useful than "broken pipe". */
    private IOException failure(IOException e) {
        try {
            process.waitFor(5, TimeUnit.SECONDS);
            logReader.join(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        String text = logText();
        return text.isEmpty() ? e : new IOException("FFmpeg failed: " + text, e);
    }

    private String logText() {
        synchronized (log) {
            return log.toString(StandardCharsets.UTF_8).strip();
        }
    }
}
