package me.rothens.gpsexif.video;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Writes frames of one size into a video file. */
public interface VideoEncoder {

    /** Adds the next frame; {@code frame} has the video's size and type {@link BufferedImage#TYPE_3BYTE_BGR}. */
    void addFrame(BufferedImage frame) throws IOException;

    /** Finishes the file. */
    void finish() throws IOException;

    /** Stops without finishing; the partly written file is deleted. */
    void abort();
}
