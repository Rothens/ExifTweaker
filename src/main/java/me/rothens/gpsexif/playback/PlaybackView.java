package me.rothens.gpsexif.playback;

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

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
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
        private ImageFile photo;
        private BufferedImage image;
        private int index;
        private int total;

        void set(ImageFile photo, BufferedImage image, int index, int total) {
            this.photo = photo;
            this.image = image;
            this.index = index;
            this.total = total;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (null == photo) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
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
                    String text = "Loading " + photo.getFile().getName() + "...";
                    g2.drawString(text, (getWidth() - g2.getFontMetrics().stringWidth(text)) / 2, getHeight() / 2);
                }
                paintTimestamp(g2);
            } finally {
                g2.dispose();
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
            FontMetrics fb = g2.getFontMetrics(big);
            FontMetrics fs = g2.getFontMetrics(small);
            int w = Math.max(fb.stringWidth(time), Math.max(fs.stringWidth(date), fs.stringWidth(info))) + 28;
            int h = fb.getHeight() + (showFileInfo ? 2 : 1) * fs.getHeight() + (showFileInfo ? 18 : 14);
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(16, 16, w, h, 14, 14);
            g2.setColor(Color.WHITE);
            g2.setFont(big);
            int y = 16 + 8 + fb.getAscent();
            g2.drawString(time, 30, y);
            g2.setFont(small);
            y += fs.getHeight() + 2;
            g2.drawString(date, 30, y);
            g2.setColor(new Color(200, 200, 200));
            y += fs.getHeight();
            g2.drawString(info, 30, y);
        }
    }
}
