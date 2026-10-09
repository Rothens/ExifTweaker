package me.rothens.gpsexif.map;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.GeoPosition;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Optional overview of the opened photos on the map. Nearby photos are clustered into one marker with a count,
 * and at most {@code cap} markers are drawn, so thousands of photos stay readable. Clustering is recomputed only
 * once panning or zooming has stopped.
 * <p>
 * Clicking a single photo selects it; clicking a cluster zooms in on it, or lists its photos when it can't be
 * split any further.
 */
public class PhotoMarkerLayer implements Painter<JXMapViewer> {

    static final double CELL_SIZE = 60;
    private static final int RECOMPUTE_DELAY_MS = 150;
    private static final int MAX_LISTED = 25;
    private static final Color MARKER = new Color(40, 110, 230);
    private static final Color HIGHLIGHT = new Color(255, 170, 0);

    private final JXMapViewer map;
    private final IntSupplier cap;
    private final Consumer<ImageFile> onSelect;
    private final Timer recomputeTimer;

    private boolean enabled;
    private List<ImageFile> photos = List.of();
    private Set<ImageFile> highlighted = Set.of();
    private MarkerClusterer.Result<ImageFile> result = new MarkerClusterer.Result<>(List.of(), 0);
    private int resultZoom = -1;

    public PhotoMarkerLayer(JXMapViewer map, IntSupplier cap, Consumer<ImageFile> onSelect) {
        this.map = map;
        this.cap = cap;
        this.onSelect = onSelect;
        recomputeTimer = new Timer(RECOMPUTE_DELAY_MS, e -> recompute());
        recomputeTimer.setRepeats(false);
        map.addPropertyChangeListener("zoom", e -> scheduleRecompute());
        map.addPropertyChangeListener("center", e -> scheduleRecompute());
        map.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scheduleRecompute();
            }
        });
        map.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (enabled && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    click(e.getPoint());
                }
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        recompute();
    }

    /** The photos to show (only those with a location are drawn). */
    public void setPhotos(List<ImageFile> photos) {
        this.photos = List.copyOf(photos);
        recompute();
    }

    /** Photos whose markers get a highlight ring, e.g. the selection. */
    public void setHighlighted(List<ImageFile> selection) {
        this.highlighted = new HashSet<>(selection);
        map.repaint();
    }

    /** Recomputation is debounced so dragging and wheel-zooming stay smooth. */
    public void scheduleRecompute() {
        if (enabled) {
            recomputeTimer.restart();
        }
    }

    /** Clusters the photos for the current zoom level and visible area. Called on the event thread. */
    public void recompute() {
        recomputeTimer.stop();
        if (!enabled) {
            result = new MarkerClusterer.Result<>(List.of(), 0);
            map.repaint();
            return;
        }
        int zoom = map.getZoom();
        List<MarkerClusterer.Input<ImageFile>> inputs = new ArrayList<>();
        for (ImageFile photo : photos) {
            GeoPosition position = photo.getGp();
            if (null != position) {
                inputs.add(new MarkerClusterer.Input<>(photo, map.getTileFactory().geoToPixel(position, zoom)));
            }
        }
        result = MarkerClusterer.cluster(inputs, map.getViewportBounds(), CELL_SIZE, cap.getAsInt());
        resultZoom = zoom;
        map.repaint();
    }

    MarkerClusterer.Result<ImageFile> getResult() {
        return result;
    }

    @Override
    public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
        // Until the clusters are recomputed for a new zoom level, their pixel positions would be wrong
        if (!enabled || resultZoom != map.getZoom()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle viewport = map.getViewportBounds();
            Font font = map.getFont().deriveFont(Font.BOLD, 11f);
            g2.setFont(font);
            // Draw small clusters first so big ones end up on top
            List<MarkerClusterer.Cluster<ImageFile>> clusters = new ArrayList<>(result.clusters());
            java.util.Collections.reverse(clusters);
            for (MarkerClusterer.Cluster<ImageFile> cluster : clusters) {
                double x = cluster.center().getX() - viewport.getX();
                double y = cluster.center().getY() - viewport.getY();
                double r = radius(cluster.size());
                boolean highlight = cluster.items().stream().anyMatch(highlighted::contains);
                if (highlight) {
                    g2.setColor(HIGHLIGHT);
                    g2.fill(new Ellipse2D.Double(x - r - 3, y - r - 3, 2 * r + 6, 2 * r + 6));
                }
                Ellipse2D circle = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
                g2.setColor(MARKER);
                g2.fill(circle);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2));
                g2.draw(circle);
                if (cluster.size() > 1) {
                    String text = cluster.size() > 9999 ? "9999+" : String.valueOf(cluster.size());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(text, (float) (x - fm.stringWidth(text) / 2.0),
                            (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0));
                }
            }
            if (result.isCapped()) {
                drawNotice(g2, tr("Showing {0} of {1} photo markers - zoom in to see more", result.clusters().size(),
                        result.totalInView()));
            }
        } finally {
            g2.dispose();
        }
    }

    private static double radius(int size) {
        if (size == 1) {
            return 6;
        }
        return Math.min(22, 10 + 3 * Math.log10(size) * 2);
    }

    private void drawNotice(Graphics2D g, String text) {
        g.setFont(map.getFont().deriveFont(Font.PLAIN, 12f));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 12;
        int h = fm.getHeight() + 6;
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRoundRect(8, 8, w, h, 8, 8);
        g.setColor(new Color(40, 40, 40));
        g.drawString(text, 14, 8 + 3 + fm.getAscent());
    }

    private void click(Point point) {
        if (resultZoom != map.getZoom()) {
            return;
        }
        Rectangle viewport = map.getViewportBounds();
        Point2D world = new Point2D.Double(point.x + viewport.getX(), point.y + viewport.getY());
        MarkerClusterer.Cluster<ImageFile> hit = null;
        for (MarkerClusterer.Cluster<ImageFile> cluster : result.clusters()) {
            // Clusters are sorted largest first, which are also drawn on top
            if (cluster.center().distance(world) <= radius(cluster.size()) + 3) {
                hit = cluster;
                break;
            }
        }
        if (null == hit) {
            return;
        }
        if (hit.size() == 1) {
            onSelect.accept(hit.items().get(0));
            return;
        }
        Set<GeoPosition> positions = new HashSet<>();
        hit.items().forEach(p -> positions.add(p.getGp()));
        int zoom = map.getZoom();
        int mostDetailed = map.getTileFactory().getInfo().getMinimumZoomLevel();
        if (positions.size() > 1 && zoom > mostDetailed) {
            map.zoomToBestFit(positions, 0.7);
            if (map.getZoom() >= zoom) {
                // Best fit didn't get closer (the photos span the view) - zoom in one step on the cluster
                GeoPosition center = map.getTileFactory().pixelToGeo(hit.center(), zoom);
                map.setZoom(zoom - 1);
                map.setCenterPosition(center);
            }
            recompute();
        } else {
            showList(hit, point);
        }
    }

    /** Photos taken at (practically) the same spot can't be told apart by zooming, so offer them in a menu. */
    private void showList(MarkerClusterer.Cluster<ImageFile> cluster, Point point) {
        JPopupMenu menu = new JPopupMenu();
        List<ImageFile> items = cluster.items().stream()
                .sorted(java.util.Comparator.comparing(p -> p.getFile().getName())).toList();
        for (ImageFile photo : items.subList(0, Math.min(MAX_LISTED, items.size()))) {
            JMenuItem item = new JMenuItem(photo.getFile().getName());
            item.addActionListener(e -> onSelect.accept(photo));
            menu.add(item);
        }
        if (items.size() > MAX_LISTED) {
            JMenuItem more = new JMenuItem(tr("... and {0} more", items.size() - MAX_LISTED));
            more.setEnabled(false);
            menu.add(more);
        }
        menu.show(map, point.x, point.y);
    }
}
