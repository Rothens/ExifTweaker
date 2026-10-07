package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.viewer.TileFactory;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * The photo playback as video frames: each photo for a fixed time, optionally cross-fading into the next one.
 * Each photo's frame (photo, timestamp, map) is rendered once and reused.
 */
final class PlaybackFrames extends OffscreenFrames {

    private final PlaybackSequence sequence;
    private final int fps;
    private final double secondsPerPhoto;
    private final double fade;
    private final PlaybackView view;
    private int renderedIndex = -1;
    private BufferedImage rendered;
    private int nextIndex = -1;
    private BufferedImage next;

    /**
     * Call on the Swing thread.
     *
     * @param fade cross-fade into the next photo at the end of each photo, in seconds (0 for none)
     */
    PlaybackFrames(PlaybackSequence sequence, ClockZone clock, TileFactory tileFactory, int width, int height, int fps,
                   double secondsPerPhoto, double fade) {
        super(width, height);
        this.sequence = sequence;
        this.fps = fps;
        this.secondsPerPhoto = secondsPerPhoto;
        this.fade = Math.min(fade, secondsPerPhoto / 2);
        this.view = new PlaybackView(tileFactory, sequence.getRoute(), clock);
        view.setShowFileInfo(false);
        hideLoadingTiles(view.getMap());
        size(view);
        view.layoutLayers();
        view.fitRoute();
    }

    @Override
    public int getFrameCount() {
        return Math.max(1, (int) Math.round(sequence.size() * secondsPerPhoto * fps));
    }

    @Override
    public void render(int index, BufferedImage target) throws Exception {
        double t = (double) index / fps;
        int i = Math.min(sequence.size() - 1, (int) Math.floor(t / secondsPerPhoto));
        double intoPhoto = t - i * secondsPerPhoto;
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(frameOf(i), 0, 0, null);
            if (fade > 0 && i + 1 < sequence.size() && intoPhoto > secondsPerPhoto - fade) {
                float alpha = (float) Math.min(1, (intoPhoto - (secondsPerPhoto - fade)) / fade);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.drawImage(frameOf(i + 1), 0, 0, null);
            }
        } finally {
            g.dispose();
        }
    }

    /** The whole frame of photo {@code i}; the current and the next one are kept. */
    private BufferedImage frameOf(int i) throws Exception {
        if (i == renderedIndex) {
            return rendered;
        }
        if (i == nextIndex) {
            renderedIndex = nextIndex;
            rendered = next;
            nextIndex = -1;
            next = null;
            return rendered;
        }
        if (i == renderedIndex + 1 && renderedIndex >= 0) {
            // the next photo, needed early for the cross-fade
            nextIndex = i;
            next = renderPhoto(i);
            return next;
        }
        renderedIndex = i;
        rendered = renderPhoto(i);
        return rendered;
    }

    private BufferedImage renderPhoto(int i) throws Exception {
        ImageFile photo = sequence.get(i);
        loadPhoto(photo);
        onEdt(() -> {
            view.setPhoto(photo, photo(photo), i, sequence.size());
            view.showPosition(sequence.mapPosition(i), null != photo.getGp());
            return null;
        });
        awaitTiles(view.getMap());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        paint(view, image);
        return image;
    }
}
