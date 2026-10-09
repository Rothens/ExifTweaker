package me.rothens.gpsexif.playback;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackPoint;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.map.TrackPainter;
import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * One playback frame: the photo, the timestamp overlay and the map inset following the route. Used on screen by
 * {@link PlaybackWindow} and off screen, at the video's size, by the video export.
 */
final class PlaybackView extends JLayeredPane {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(tr("EEEE, d MMMM yyyy"),
            me.rothens.gpsexif.i18n.I18n.locale());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final JXMapViewer map = new JXMapViewer();
    private final TrackPainter routePainter = new TrackPainter();
    private final List<GeoPosition> route;
    private final List<Track> routeTrack;
    private final PhotoPanel photoPanel = new PhotoPanel();
    private ClockZone clock;
    private boolean showFileInfo = true;

    /**
     * @param route locations of the located photos, in the order they were taken
     */
    PlaybackView(TileFactory tileFactory, List<GeoPosition> route, ClockZone clock) {
        this.route = route;
        this.clock = clock;
        map.setTileFactory(tileFactory);
        map.setOverlayPainter(new CompoundPainter<>(routePainter, new AttributionPainter()));
        map.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 160), 2));
        List<TrackPoint> points = route.stream().map(p -> new TrackPoint(null, p, null)).toList();
        routeTrack = points.isEmpty() ? List.of() : List.of(new Track("route", List.of(points)));
        map.setVisible(!routeTrack.isEmpty());

        setOpaque(true);
        setBackground(new Color(18, 18, 20));
        add(photoPanel, JLayeredPane.DEFAULT_LAYER);
        add(map, JLayeredPane.PALETTE_LAYER);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutLayers();
            }
        });
    }

    JXMapViewer getMap() {
        return map;
    }

    void setClock(ClockZone clock) {
        this.clock = clock;
        repaint();
    }

    /** Whether the overlay shows "3 / 120  IMG_0042.jpg" under the date (not in videos). */
    void setShowFileInfo(boolean show) {
        this.showFileInfo = show;
    }

    /**
     * Cross-fades from one photo to the next in {@code seconds} (0, the default, for a hard cut). Only for the
     * screen: the video export fades its frames itself.
     */
    void setFade(double seconds) {
        photoPanel.fadeMs = Math.max(0, (long) (seconds * 1000));
    }

    void setPhoto(ImageFile photo, BufferedImage image, int index, int total) {
        photoPanel.set(photo, image, index, total);
    }

    /**
     * Centers the map on a photo's location.
     *
     * @param own whether it's the photo's own location (marked), rather than an earlier photo's
     */
    void showPosition(GeoPosition position, boolean own) {
        if (null != position) {
            map.setAddressLocation(position);
        }
        routePainter.set(routeTrack, List.of(), own ? position : null);
        map.repaint();
    }

    /** Zooms the map so the whole route fits. */
    void fitRoute() {
        if (route.size() > 1) {
            map.zoomToBestFit(new HashSet<>(route), 0.8);
        } else {
            map.setZoom(4);
        }
    }

    /**
     * How much to enlarge the time overlay in a picture of this size: 1 up to 600 pixels on the short side, growing
     * with the picture (up to 3x) so it stays readable on a large screen and in videos, portrait ones too.
     */
    static double overlayScale(Component picture) {
        return Math.max(1, Math.min(3, Math.min(picture.getWidth(), picture.getHeight()) / 600.0));
    }

    void layoutLayers() {
        photoPanel.setBounds(0, 0, getWidth(), getHeight());
        int w = Math.max(240, getWidth() / 4);
        int h = Math.max(180, w * 3 / 4);
        map.setBounds(getWidth() - w - 16, getHeight() - h - 16, w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    private final class PhotoPanel extends JComponent {
        /** How long the previous photo may stay while the next one loads, before fading anyway. */
        private static final long MAX_WAIT_MS = 1500;

        private ImageFile photo;
        private BufferedImage image;
        private int index;
        private int total;
        private long fadeMs;
        /** What was on screen when the photo changed, fading out over the new one; {@code null} when not fading. */
        private BufferedImage previous;
        private long changedAt;
        /** When the fade began: once the new photo was loaded; 0 while it's loading. */
        private long fadeStart;
        private final Timer animation = new Timer(15, e -> tick());

        void set(ImageFile photo, BufferedImage image, int index, int total) {
            boolean changed = photo != this.photo;
            if (changed && fadeMs > 0 && null != this.photo && getWidth() > 0 && getHeight() > 0) {
                previous = snapshot(); // includes a fade in progress, so a quick skip doesn't jump
                changedAt = System.currentTimeMillis();
                fadeStart = 0;
                animation.start();
            } else if (changed) {
                previous = null;
            }
            this.photo = photo;
            this.image = image;
            this.index = index;
            this.total = total;
            if (null != previous && 0 == fadeStart && (null != image || !changed)) {
                fadeStart = System.currentTimeMillis(); // loaded (or failed to): fade in
            }
            repaint();
        }

        private void tick() {
            long now = System.currentTimeMillis();
            if (null != previous && 0 == fadeStart && now - changedAt > MAX_WAIT_MS) {
                fadeStart = now;
            }
            if (null == previous || (0 != fadeStart && now - fadeStart >= fadeMs)) {
                previous = null;
                animation.stop();
            }
            repaint();
        }

        /** How much of the previous photo still shows: 1 while the new one loads, down to 0. */
        private float previousAlpha() {
            if (null == previous) {
                return 0;
            }
            if (0 == fadeStart) {
                return 1;
            }
            double t = (double) (System.currentTimeMillis() - fadeStart) / Math.max(1, fadeMs);
            return (float) Math.max(0, Math.min(1, 1 - t));
        }

        private BufferedImage snapshot() {
            BufferedImage shot = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = shot.createGraphics();
            try {
                g.setColor(PlaybackView.this.getBackground());
                g.fillRect(0, 0, shot.getWidth(), shot.getHeight());
                g.setFont(getFont());
                paintComponent(g);
            } finally {
                g.dispose();
            }
            return shot;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (null == photo) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                paintPhoto(g2);
                float alpha = previousAlpha();
                if (alpha > 0) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    g2.drawImage(previous, 0, 0, null);
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintPhoto(Graphics2D g2) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (null != image) {
                double scale = Math.min((double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale);
                int h = (int) (image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } else {
                g2.setColor(new Color(150, 150, 150));
                String text = tr("Loading {0}...", photo.getFile().getName());
                g2.drawString(text, (getWidth() - g2.getFontMetrics().stringWidth(text)) / 2, getHeight() / 2);
            }
            Graphics2D overlay = (Graphics2D) g2.create();
            try {
                double scale = overlayScale(this);
                overlay.scale(scale, scale);
                paintTimestamp(overlay);
            } finally {
                overlay.dispose();
            }
        }

        private void paintTimestamp(Graphics2D g2) {
            Font base = getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            Font big = base.deriveFont(Font.BOLD, 30f);
            Font small = base.deriveFont(Font.PLAIN, 14f);
            var local = clock.local(photo);
            String time = TIME.format(local);
            String offset = clock.offsetLabel(photo);
            String date = DATE.format(local) + (null == offset ? "" : "  ·  " + offset);
            String info = showFileInfo ? (index + 1) + " / " + total + "   " + photo.getFile().getName() : "";
            String place = null == photo.getPlace() ? null : photo.getPlace().shortLabel();
            FontMetrics fb = g2.getFontMetrics(big);
            FontMetrics fs = g2.getFontMetrics(small);
            int w = Math.max(fb.stringWidth(time), Math.max(fs.stringWidth(date), fs.stringWidth(info))) + 28;
            if (null != place) {
                w = Math.max(w, fs.stringWidth(place) + 28);
            }
            int lines = (showFileInfo ? 2 : 1) + (null == place ? 0 : 1);
            int h = fb.getHeight() + lines * fs.getHeight() + (showFileInfo ? 18 : 14);
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(16, 16, w, h, 14, 14);
            g2.setColor(Color.WHITE);
            g2.setFont(big);
            int y = 16 + 8 + fb.getAscent();
            g2.drawString(time, 30, y);
            g2.setFont(small);
            y += fs.getHeight() + 2;
            g2.drawString(date, 30, y);
            if (null != place) {
                y += fs.getHeight();
                g2.drawString(place, 30, y);
            }
            g2.setColor(new Color(200, 200, 200));
            y += fs.getHeight();
            g2.drawString(info, 30, y);
        }
    }
}
