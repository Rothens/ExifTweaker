package me.rothens.gpsexif.map;

import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackPoint;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.GeoPosition;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/** Draws loaded GPX tracks and the positions proposed for photos while geotagging. */
public class TrackPainter implements Painter<JXMapViewer> {

    private static final Color TRACK = new Color(230, 90, 20);
    private static final Color OUTLINE = new Color(0, 0, 0, 120);
    private static final Color PHOTO = new Color(40, 110, 230);

    private volatile List<Track> tracks = List.of();
    private volatile List<GeoPosition> photos = List.of();
    private volatile GeoPosition highlight;

    public void set(List<Track> tracks, List<GeoPosition> photos, GeoPosition highlight) {
        this.tracks = List.copyOf(tracks);
        this.photos = List.copyOf(photos);
        this.highlight = highlight;
    }

    public void clear() {
        set(List.of(), List.of(), null);
    }

    @Override
    public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
        if (tracks.isEmpty() && photos.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Rectangle viewport = map.getViewportBounds();
            g2.translate(-viewport.x, -viewport.y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int zoom = map.getZoom();
            for (Track track : tracks) {
                for (List<TrackPoint> segment : track.segments()) {
                    Path2D path = new Path2D.Double();
                    boolean first = true;
                    for (TrackPoint p : segment) {
                        Point2D px = map.getTileFactory().geoToPixel(p.position(), zoom);
                        if (first) {
                            path.moveTo(px.getX(), px.getY());
                            first = false;
                        } else {
                            path.lineTo(px.getX(), px.getY());
                        }
                    }
                    g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(OUTLINE);
                    g2.draw(path);
                    g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(TRACK);
                    g2.draw(path);
                }
            }
            for (GeoPosition position : photos) {
                dot(g2, map.getTileFactory().geoToPixel(position, zoom), 5);
            }
            if (null != highlight) {
                dot(g2, map.getTileFactory().geoToPixel(highlight, zoom), 9);
            }
        } finally {
            g2.dispose();
        }
    }

    private static void dot(Graphics2D g, Point2D p, double r) {
        Ellipse2D circle = new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r);
        g.setColor(PHOTO);
        g.fill(circle);
        g.setStroke(new BasicStroke(2));
        g.setColor(Color.WHITE);
        g.draw(circle);
    }
}
