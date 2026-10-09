package me.rothens.gpsexif.playback;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * One travel-mode frame: the full-screen map, the photos cross-fading over it, the clock and the map inset.
 * Used on screen by {@link TravelWindow} and off screen, at the video's size, by the video export.
 */
final class TravelView extends JLayeredPane {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(tr("EEE, d MMM yyyy"),
            me.rothens.gpsexif.i18n.I18n.locale());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final JXMapViewer fullMap = new JXMapViewer();
    private final JXMapViewer insetMap = new JXMapViewer();
    private final RoutePainter routePainter = new RoutePainter();
    private final PhotoLayer photoLayer = new PhotoLayer();
    private final Function<ImageFile, BufferedImage> images;
    private TravelTimeline timeline;
    private ClockZone clock;
    private List<List<GeoPosition>> route = List.of();
    private int mapSlot = -1;
    /** Width of the small map as a share of the view's width. */
    private double insetFraction = 0.25;
    private InsetGrip grip;
    /** Whether the small map keeps its zoom and follows the marker, instead of showing the whole route. */
    private boolean followMarker;
    /** Whether "Osaka, Namba → Tokyo, Chiyoda" shows while travelling. */
    private boolean showTransfers = true;

    /**
     * @param images the loaded photo to draw, or {@code null} while it isn't loaded yet
     */
    TravelView(TileFactory tileFactory, Function<ImageFile, BufferedImage> images, ClockZone clock) {
        this.images = images;
        this.clock = clock;
        for (JXMapViewer map : new JXMapViewer[]{fullMap, insetMap}) {
            map.setTileFactory(tileFactory);
            map.setOverlayPainter(new CompoundPainter<>(routePainter, new AttributionPainter()));
        }
        insetMap.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 160), 2));
        setOpaque(true);
        setBackground(new Color(18, 18, 20));
        add(fullMap, JLayeredPane.DEFAULT_LAYER);
        add(photoLayer, JLayeredPane.PALETTE_LAYER);
        add(insetMap, JLayeredPane.MODAL_LAYER);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutView();
            }
        });
    }

    /** Both maps, e.g. to add mouse panning or to wait for their tiles. */
    JXMapViewer[] getMaps() {
        return new JXMapViewer[]{fullMap, insetMap};
    }

    void setClock(ClockZone clock) {
        this.clock = clock;
        repaint();
    }

    /** A new timeline and the route line to draw; frames the whole route on the inset. */
    void setTimeline(TravelTimeline timeline, List<List<GeoPosition>> route) {
        this.timeline = timeline;
        this.route = route;
        routePainter.route = route;
        if (!followMarker) {
            fitInset();
        }
        mapSlot = -1;
    }

    /** Shows a frame: moves the marker, frames a travel stretch on the full map and shows the photos. */
    void show(TravelTimeline.Frame frame) {
        routePainter.marker = frame.marker();
        routePainter.photoAt = frame.photoAt();
        if (frame.slot() >= 0 && frame.slot() != mapSlot && (null == frame.from() || null == frame.to())) {
            // A travel stretch starts: frame it on the full-screen map
            fitFullMap(frame.slot());
            mapSlot = frame.slot();
        }
        if (followMarker && null != frame.marker()) {
            insetMap.setCenterPosition(frame.marker());
        }
        insetMap.setVisible(null != frame.visible() && !timeline.isEmpty());
        if (null != grip) {
            grip.setVisible(insetMap.isVisible());
        }
        photoLayer.frame = frame;
        repaint();
    }

    private void fitInset() {
        List<GeoPosition> all = route.stream().flatMap(List::stream).toList();
        if (all.size() > 1) {
            insetMap.zoomToBestFit(new HashSet<>(all), 0.85);
        } else if (all.size() == 1) {
            insetMap.setZoom(5);
            insetMap.setAddressLocation(all.get(0));
        }
    }

    private void fitFullMap(int slot) {
        List<TravelTimeline.Slot> slots = timeline.getSlots();
        Instant from = slots.get(slot).stop().time();
        Instant to = slot + 1 < slots.size() ? slots.get(slot + 1).stop().time() : from;
        Set<GeoPosition> points = new HashSet<>();
        for (int i = 0; i <= 20; i++) {
            GeoPosition p = timeline.markerAt(from.plusMillis(Duration.between(from, to).toMillis() * i / 20));
            if (null != p) {
                points.add(p);
            }
        }
        if (points.size() > 1) {
            fullMap.zoomToBestFit(points, 0.7);
        } else if (points.size() == 1) {
            fullMap.setZoom(Math.min(fullMap.getZoom(), 6));
            fullMap.setAddressLocation(points.iterator().next());
        }
    }

    /** Lays out the layers for the current size; refits the inset and the full map, which depend on it. */
    void layoutView() {
        fullMap.setBounds(0, 0, getWidth(), getHeight());
        photoLayer.setBounds(0, 0, getWidth(), getHeight());
        layoutInset();
        if (null != timeline) {
            if (!followMarker) {
                fitInset();
            }
            mapSlot = -1;
        }
    }

    /** Places the small map in the bottom right corner, at its share of the width (fitting the height). */
    private void layoutInset() {
        int w = (int) Math.round(Math.max(240, getWidth() * insetFraction));
        w = Math.max(160, Math.min(w, Math.min(getWidth() - 32, (getHeight() - 32) * 4 / 3)));
        int h = w * 3 / 4;
        insetMap.setBounds(getWidth() - w - 16, getHeight() - h - 16, w, h);
        if (null != grip) {
            grip.setBounds(insetMap.getX(), insetMap.getY(), InsetGrip.SIZE, InsetGrip.SIZE);
        }
    }

    /**
     * Lets the small map follow the marker at {@code zoom} (or at its current zoom if {@code zoom} is negative), or
     * show the whole route again.
     */
    void setFollowMarker(boolean follow, int zoom) {
        followMarker = follow;
        if (follow) {
            if (zoom >= 0) {
                insetMap.setZoom(zoom);
            }
            if (null != routePainter.marker) {
                insetMap.setCenterPosition(routePainter.marker);
            }
        } else if (null != timeline) {
            fitInset();
        }
        repaint();
    }

    /** Whether to show where the trip goes from and to while travelling. */
    void setShowTransfers(boolean show) {
        showTransfers = show;
        repaint();
    }

    /** The settlement a photo was taken in, e.g. "Osaka, Namba", or {@code null}. */
    private static String settlement(ImageFile photo) {
        return null == photo || null == photo.getPlace() ? null : photo.getPlace().settlement();
    }

    /** The small map's zoom level. */
    int getInsetZoom() {
        return insetMap.getZoom();
    }

    double getInsetFraction() {
        return insetFraction;
    }

    /** Sets the small map's width as a share of the view's width (0.15-0.6). */
    void setInsetFraction(double fraction) {
        insetFraction = Math.max(0.15, Math.min(0.6, fraction));
        layoutInset();
        revalidate();
        repaint();
    }

    /**
     * Adds a grip to the small map's top left corner to drag its size; {@code onResized} gets the new share of the
     * width when the drag ends. On screen only, not in videos.
     */
    void enableInsetResize(java.util.function.DoubleConsumer onResized) {
        grip = new InsetGrip(onResized);
        add(grip, JLayeredPane.DRAG_LAYER);
        layoutInset();
    }

    /** The handle in the small map's top left corner; dragging it resizes the map, anchored bottom right. */
    private final class InsetGrip extends JComponent {
        static final int SIZE = 18;

        InsetGrip(java.util.function.DoubleConsumer onResized) {
            setCursor(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR));
            setToolTipText(tr("Drag to resize the map"));
            java.awt.event.MouseAdapter drag = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseDragged(java.awt.event.MouseEvent e) {
                    Point p = SwingUtilities.convertPoint(InsetGrip.this, e.getPoint(), TravelView.this);
                    int right = getWidthOfView() - 16;
                    int bottom = TravelView.this.getHeight() - 16;
                    // Follow whichever of the two edges was dragged further, keeping 4:3
                    int byX = right - p.x;
                    int byY = (bottom - p.y) * 4 / 3;
                    setInsetFraction((double) Math.max(byX, byY) / Math.max(1, getWidthOfView()));
                    fitInset();
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    onResized.accept(insetFraction);
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
        }

        private int getWidthOfView() {
            return TravelView.this.getWidth();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 120));
                g2.fillRect(0, 0, SIZE, SIZE);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.5f));
                for (int i = 5; i <= 13; i += 4) {
                    g2.drawLine(3, i, i, 3);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Route line, the moving marker and the small dot at the shown photo, on both maps. */
    private static final class RoutePainter implements Painter<JXMapViewer> {
        volatile List<List<GeoPosition>> route = List.of();
        volatile GeoPosition marker;
        volatile GeoPosition photoAt;

        @Override
        public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Rectangle viewport = map.getViewportBounds();
                g2.translate(-viewport.x, -viewport.y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int zoom = map.getZoom();
                for (List<GeoPosition> segment : route) {
                    Path2D path = new Path2D.Double();
                    boolean first = true;
                    for (GeoPosition p : segment) {
                        Point2D px = map.getTileFactory().geoToPixel(p, zoom);
                        if (first) {
                            path.moveTo(px.getX(), px.getY());
                            first = false;
                        } else {
                            path.lineTo(px.getX(), px.getY());
                        }
                    }
                    g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(0, 0, 0, 90));
                    g2.draw(path);
                    g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(230, 90, 20, 200));
                    g2.draw(path);
                }
                if (null != photoAt) {
                    dot(g2, map.getTileFactory().geoToPixel(photoAt, zoom), 4, new Color(40, 110, 230));
                }
                if (null != marker) {
                    dot(g2, map.getTileFactory().geoToPixel(marker, zoom), 8, new Color(230, 60, 40));
                }
            } finally {
                g2.dispose();
            }
        }

        private static void dot(Graphics2D g, Point2D p, double r, Color color) {
            Ellipse2D circle = new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r);
            g.setColor(color);
            g.fill(circle);
            g.setStroke(new BasicStroke(2));
            g.setColor(Color.WHITE);
            g.draw(circle);
        }
    }

    /** Photos cross-fading over the map, the travel clock and the stay caption. */
    private final class PhotoLayer extends JComponent {
        TravelTimeline.Frame frame;

        @Override
        protected void paintComponent(Graphics g) {
            if (null == frame || null == frame.time()) {
                g.setColor(new Color(18, 18, 20));
                g.fillRect(0, 0, getWidth(), getHeight());
                if (null != timeline && timeline.isEmpty()) {
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString(tr("None of these photos has a date."), 20, 30);
                }
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (null != frame.from()) {
                    drawPhoto(g2, frame.from(), null == frame.to() ? 1 - frame.alpha() : 1);
                }
                if (null != frame.to() && frame.to() != frame.from()) {
                    drawPhoto(g2, frame.to(), frame.alpha());
                }
                double scale = PlaybackView.overlayScale(this);
                g2.scale(scale, scale);
                paintClock(g2);
            } finally {
                g2.dispose();
            }
        }

        private void drawPhoto(Graphics2D g2, ImageFile photo, double opacity) {
            if (opacity <= 0) {
                return;
            }
            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, opacity)));
            g2.setColor(new Color(18, 18, 20));
            g2.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage image = images.apply(photo);
            if (null != image) {
                double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale);
                int h = (int) (image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            g2.setComposite(old);
        }

        private void paintClock(Graphics2D g2) {
            Font base = getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            Font big = base.deriveFont(Font.BOLD, 30f);
            Font small = base.deriveFont(Font.PLAIN, 14f);
            var local = clock.local(frame.time());
            String time = TIME.format(local);
            String offset = clock.offsetLabel(frame.time());
            String date = DATE.format(local) + (null == offset ? "" : "  ·  " + offset);
            FontMetrics fb = g2.getFontMetrics(big);
            FontMetrics fs = g2.getFontMetrics(small);
            String caption = frame.caption();
            int captionWidth = null == caption ? 0 : g2.getFontMetrics(big).stringWidth(caption) + 24;
            // The place of the photo on screen; none while travelling on the map
            ImageFile shown = null != frame.to()
                    ? (frame.alpha() >= 0.5 || null == frame.from() ? frame.to() : frame.from())
                    : (frame.alpha() < 0.5 ? frame.from() : null);
            String place = settlement(shown);
            if (showTransfers && null != frame.travelTo() && frame.slot() >= 0) {
                String from = settlement(timeline.getSlots().get(frame.slot()).stop().photo());
                String to = settlement(frame.travelTo());
                if (null != from && null != to && !from.equals(to)) {
                    place = from + "  →  " + to;
                }
            }
            int w = Math.max(fb.stringWidth(time) + captionWidth, fs.stringWidth(date)) + 28;
            if (null != place) {
                w = Math.max(w, fs.stringWidth(place) + 28);
            }
            int h = fb.getHeight() + (null == place ? 1 : 2) * fs.getHeight() + 14;
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(16, 16, w, h, 14, 14);
            g2.setColor(Color.WHITE);
            g2.setFont(big);
            int y = 16 + 6 + fb.getAscent();
            g2.drawString(time, 30, y);
            if (null != caption) {
                g2.setColor(new Color(255, 200, 90));
                g2.drawString(caption, 30 + fb.stringWidth(time) + 24, y);
                g2.setColor(Color.WHITE);
            }
            g2.setFont(small);
            g2.drawString(date, 30, y + fs.getHeight() + 2);
            if (null != place) {
                g2.drawString(place, 30, y + 2 * fs.getHeight() + 2);
            }
        }
    }
}
