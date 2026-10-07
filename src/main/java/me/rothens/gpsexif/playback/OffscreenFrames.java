package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.map.MapTiles;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.PhotoLoader;
import me.rothens.gpsexif.video.FrameSource;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.viewer.Tile;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Shared plumbing for rendering a playback view into video frames: the view is laid out at a "logical" size and
 * scaled up to the video size, so the clock and the map inset look the same in 720p and in 4K; photos are drawn
 * at the full video resolution.
 */
abstract class OffscreenFrames implements FrameSource {

    /** The logical size's shorter side: overlays are designed for a window of about this height. */
    private static final double LOGICAL_SHORT_SIDE = 900;
    private static final long TILE_TIMEOUT_MS = 20_000;

    protected final int width;
    protected final int height;
    protected final int logicalWidth;
    protected final int logicalHeight;
    private final int photoSize;
    private final Map<ImageFile, BufferedImage> photos = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ImageFile, BufferedImage> eldest) {
                    return size() > 6;
                }
            });
    private final MapTiles.Waiter tiles = new MapTiles.Waiter(TILE_TIMEOUT_MS);
    private int unreadablePhotos;

    OffscreenFrames(int width, int height) {
        this.width = width;
        this.height = height;
        double scale = Math.min(width, height) / LOGICAL_SHORT_SIDE;
        this.logicalWidth = (int) Math.round(width / scale);
        this.logicalHeight = (int) Math.round(height / scale);
        this.photoSize = Math.max(width, height);
    }

    /** The loaded photo, for the view to draw; {@code null} until {@link #loadPhoto} loaded it. */
    protected BufferedImage photo(ImageFile photo) {
        return null == photo ? null : photos.get(photo);
    }

    /** Loads a photo at the video's resolution (on the export thread) unless it's cached. */
    protected void loadPhoto(ImageFile photo) {
        if (null == photo || photos.containsKey(photo)) {
            return;
        }
        BufferedImage image = null;
        try {
            image = PhotoLoader.load(photo, photoSize);
        } catch (Exception e) {
            // drawn as an empty frame
        }
        if (null == image) {
            unreadablePhotos++;
        }
        photos.put(photo, image);
    }

    /** Waits for the tiles the given maps show, as they are now; call off the Swing thread. */
    protected void awaitTiles(JXMapViewer... maps) throws Exception {
        List<Tile> needed = onEdt(() -> {
            List<Tile> list = new ArrayList<>();
            for (JXMapViewer map : maps) {
                list.addAll(MapTiles.request(map));
            }
            return list;
        });
        tiles.await(needed, () -> Thread.currentThread().isInterrupted());
    }

    /** Paints {@code view} (laid out at the logical size) scaled into {@code target}; call off the Swing thread. */
    protected void paint(JComponent view, BufferedImage target) throws Exception {
        onEdt(() -> {
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.scale((double) width / logicalWidth, (double) height / logicalHeight);
                view.paint(g);
            } finally {
                g.dispose();
            }
            return null;
        });
    }

    /** Draws tiles that aren't loaded (yet) as nothing rather than as a "loading" icon, which a video would keep. */
    protected static void hideLoadingTiles(JXMapViewer... maps) {
        for (JXMapViewer map : maps) {
            map.setLoadingImage(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        }
    }

    /** Lays a view out at the logical size; call on the Swing thread. */
    protected void size(JComponent view) {
        view.setSize(logicalWidth, logicalHeight);
        view.doLayout();
    }

    @Override
    public String getWarnings() {
        List<String> warnings = new ArrayList<>();
        if (tiles.getMissing() > 0) {
            warnings.add(tiles.getMissing() + " map tile" + (tiles.getMissing() == 1 ? "" : "s")
                    + " couldn't be downloaded and " + (tiles.getMissing() == 1 ? "is" : "are")
                    + " missing from the map.");
        }
        if (unreadablePhotos > 0) {
            warnings.add(unreadablePhotos + " photo" + (unreadablePhotos == 1 ? "" : "s")
                    + " couldn't be read and " + (unreadablePhotos == 1 ? "is" : "are") + " shown as empty frames.");
        }
        return String.join("\n", warnings);
    }

    static <T> T onEdt(Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.call();
        }
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = task.call();
                } catch (Exception e) {
                    error[0] = e;
                }
            });
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : new RuntimeException(e.getCause());
        }
        if (null != error[0]) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }
}
