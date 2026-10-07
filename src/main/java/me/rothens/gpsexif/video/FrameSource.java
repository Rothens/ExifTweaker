package me.rothens.gpsexif.video;

import java.awt.image.BufferedImage;

/**
 * The frames of a video, rendered one by one in order on a background thread (implementations hop to the Swing
 * thread themselves where they paint components).
 */
public interface FrameSource {

    int getFrameCount();

    /**
     * Draws frame {@code index} into {@code target}, which has the video's size.
     *
     * @throws Exception when the frame can't be rendered; the export stops with that error
     */
    void render(int index, BufferedImage target) throws Exception;

    /** Problems worth mentioning when the export is done (e.g. map tiles that couldn't be loaded); may be empty. */
    default String getWarnings() {
        return "";
    }

    /** Releases resources; called once when the export ends, also when it fails or is cancelled. */
    default void close() {
    }
}
