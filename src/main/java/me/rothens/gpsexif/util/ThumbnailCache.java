package me.rothens.gpsexif.util;

import me.rothens.gpsexif.model.ImageFile;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * Small thumbnails for the photo grid, loaded in the background only for the photos that are actually shown.
 * The thumbnail embedded in the EXIF data is used when there is one (fast: nothing else is decoded); otherwise the
 * photo is decoded at a reduced size. The most recently requested photos load first, so scrolling stays snappy.
 */
public final class ThumbnailCache {

    private static final int THREADS = 2;

    private final int size;
    private final Runnable onLoaded;
    private final Map<ImageFile, BufferedImage> cache;
    private final Set<ImageFile> failed = Collections.synchronizedSet(new HashSet<>());
    private final Deque<ImageFile> queue = new LinkedList<>();
    private final Set<ImageFile> queued = new HashSet<>();
    private int generation;

    /**
     * @param size     longest side of a thumbnail, in pixels
     * @param capacity how many thumbnails are kept in memory
     * @param onLoaded called on the Swing thread after a thumbnail was loaded (e.g. to repaint)
     */
    public ThumbnailCache(int size, int capacity, Runnable onLoaded) {
        this.size = size;
        this.onLoaded = onLoaded;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ImageFile, BufferedImage> eldest) {
                return size() > capacity;
            }
        });
        for (int i = 0; i < THREADS; i++) {
            Thread worker = new Thread(this::work, "thumbnails-" + i);
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    /** The thumbnail if it's loaded; otherwise {@code null}, and it's loaded in the background. */
    public BufferedImage get(ImageFile photo) {
        BufferedImage thumbnail = cache.get(photo);
        if (null == thumbnail && !failed.contains(photo)) {
            synchronized (queue) {
                if (queued.add(photo)) {
                    queue.addFirst(photo);
                } else {
                    queue.remove(photo);
                    queue.addFirst(photo); // asked again: it's on screen, load it soon
                }
                queue.notifyAll();
            }
        }
        return thumbnail;
    }

    /** Whether loading the photo failed (e.g. a RAW file without ExifTool). */
    public boolean hasFailed(ImageFile photo) {
        return failed.contains(photo);
    }

    /** Forgets everything, e.g. when another folder is opened or a photo changed. */
    public void clear() {
        synchronized (queue) {
            generation++;
            queue.clear();
            queued.clear();
        }
        cache.clear();
        failed.clear();
    }

    /** Forgets one photo's thumbnail, e.g. after it was rotated. */
    public void invalidate(ImageFile photo) {
        cache.remove(photo);
        failed.remove(photo);
    }

    private void work() {
        while (true) {
            ImageFile photo;
            int gen;
            synchronized (queue) {
                while (queue.isEmpty()) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                photo = queue.pollFirst();
                gen = generation;
            }
            BufferedImage thumbnail = null;
            try {
                thumbnail = load(photo, size);
            } catch (Exception | OutOfMemoryError e) {
                // shown as "no preview"
            }
            synchronized (queue) {
                queued.remove(photo);
                if (gen != generation) {
                    continue; // another folder was opened meanwhile
                }
                if (null == thumbnail) {
                    failed.add(photo);
                } else {
                    cache.put(photo, thumbnail);
                }
            }
            SwingUtilities.invokeLater(onLoaded);
        }
    }

    /** Loads a thumbnail of at most {@code size} pixels on its longest side, upright. */
    public static BufferedImage load(ImageFile photo, int size) throws Exception {
        BufferedImage picture = exifThumbnail(photo);
        if (null == picture) {
            picture = PhotoLoader.load(photo, size);
            return null == picture ? null : scale(picture, size);
        }
        return scale(ImageOrientation.apply(picture, photo.getOrientation()), size);
    }

    /** The JPEG's embedded thumbnail, cropped to the photo's shape (cameras often add black bars), or null. */
    private static BufferedImage exifThumbnail(ImageFile photo) {
        String name = photo.getFile().getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
            return null;
        }
        try {
            ImageMetadata metadata = Imaging.getMetadata(photo.getFile());
            if (!(metadata instanceof JpegImageMetadata jpeg)) {
                return null;
            }
            BufferedImage thumbnail = jpeg.getExifThumbnail();
            if (null == thumbnail || thumbnail.getWidth() < 32) {
                return null;
            }
            Dimension photoSize = photoSize(photo);
            return null == photoSize ? thumbnail : cropToAspect(thumbnail, photoSize);
        } catch (Exception e) {
            return null;
        }
    }

    /** The photo's size in pixels without decoding it, or null. */
    private static Dimension photoSize(ImageFile photo) {
        try (ImageInputStream in = ImageIO.createImageInputStream(photo.getFile())) {
            Iterator<ImageReader> readers = null == in ? null : ImageIO.getImageReaders(in);
            if (null == readers || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Cuts off the bars a camera adds when the thumbnail's shape differs from the photo's (e.g. 4:3 vs 3:2). */
    static BufferedImage cropToAspect(BufferedImage thumbnail, Dimension photo) {
        double target = (double) photo.width / photo.height;
        double actual = (double) thumbnail.getWidth() / thumbnail.getHeight();
        if (Math.abs(target - actual) < 0.03) {
            return thumbnail;
        }
        if (target > actual) {
            int h = (int) Math.round(thumbnail.getWidth() / target);
            return thumbnail.getSubimage(0, (thumbnail.getHeight() - h) / 2, thumbnail.getWidth(), h);
        }
        int w = (int) Math.round(thumbnail.getHeight() * target);
        return thumbnail.getSubimage((thumbnail.getWidth() - w) / 2, 0, w, thumbnail.getHeight());
    }

    /** Scales down (never up) to fit {@code size}, smoothly. */
    static BufferedImage scale(BufferedImage image, int size) {
        double factor = Math.min(1.0, (double) size / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage current = image;
        // Halve in steps first, so large photos don't get grainy
        while (current.getWidth() / 2 >= w && current.getHeight() / 2 >= h) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return current.getWidth() == w && current.getHeight() == h && current.getType() == BufferedImage.TYPE_INT_RGB
                ? current : resize(current, w, h);
    }

    private static BufferedImage resize(BufferedImage image, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
