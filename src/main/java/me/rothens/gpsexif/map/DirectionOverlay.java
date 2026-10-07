package me.rothens.gpsexif.map;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.GeoPosition;

import javax.swing.*;
import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.function.DoubleConsumer;

/**
 * Shows which way the camera pointed as a view cone from the photo's position, with a handle at its tip that can
 * be dragged around to set the direction. Without a direction, only a hollow handle north of the photo is shown.
 */
public class DirectionOverlay implements Painter<JXMapViewer> {

    static final double RADIUS = 70;
    private static final double HALF_ANGLE = 30;
    private static final double HANDLE = 6;
    private static final Color CONE = new Color(255, 140, 0);

    private final JXMapViewer map;
    private GeoPosition anchor;
    private Double direction;
    private DoubleConsumer onDrag = d -> { };
    private boolean dragging;

    public DirectionOverlay(JXMapViewer map) {
        this.map = map;
    }

    /** Called with the new direction (degrees clockwise from north) while the handle is dragged. */
    public void setOnDrag(DoubleConsumer onDrag) {
        this.onDrag = onDrag;
    }

    /** The photo's position (or the pending one), or {@code null} to hide the overlay. */
    public void set(GeoPosition anchor, Double direction) {
        this.anchor = anchor;
        this.direction = direction;
        map.repaint();
    }

    public boolean isDragging() {
        return dragging;
    }

    /** Screen bearing from {@code from} to {@code to}: 0 = up (north), clockwise, 0 <= result < 360. */
    static double bearing(Point2D from, Point2D to) {
        double degrees = Math.toDegrees(Math.atan2(to.getX() - from.getX(), from.getY() - to.getY()));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /** Point {@code distance} pixels from {@code from} in direction {@code degrees}. */
    static Point2D towards(Point2D from, double degrees, double distance) {
        double rad = Math.toRadians(degrees);
        return new Point2D.Double(from.getX() + Math.sin(rad) * distance, from.getY() - Math.cos(rad) * distance);
    }

    private Point2D anchorOnScreen() {
        Point2D world = map.getTileFactory().geoToPixel(anchor, map.getZoom());
        Rectangle viewport = map.getViewportBounds();
        return new Point2D.Double(world.getX() - viewport.getX(), world.getY() - viewport.getY());
    }

    private Point2D handleOnScreen() {
        return towards(anchorOnScreen(), null == direction ? 0 : direction, RADIUS);
    }

    @Override
    public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
        if (null == anchor) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Point2D a = anchorOnScreen();
            Point2D handle = handleOnScreen();
            if (null != direction) {
                // Arc2D angles: 0 = east, counter-clockwise; compass: 0 = north, clockwise
                double start = 90 - direction - HALF_ANGLE;
                Arc2D cone = new Arc2D.Double(a.getX() - RADIUS, a.getY() - RADIUS, 2 * RADIUS, 2 * RADIUS,
                        start, 2 * HALF_ANGLE, Arc2D.PIE);
                g2.setColor(new Color(CONE.getRed(), CONE.getGreen(), CONE.getBlue(), 70));
                g2.fill(cone);
                g2.setColor(CONE);
                g2.setStroke(new BasicStroke(2));
                g2.draw(cone);
                g2.draw(new Line2D.Double(a, handle));
            }
            Ellipse2D knob = new Ellipse2D.Double(handle.getX() - HANDLE, handle.getY() - HANDLE, 2 * HANDLE, 2 * HANDLE);
            g2.setColor(null == direction ? new Color(255, 255, 255, 170) : Color.WHITE);
            g2.fill(knob);
            g2.setStroke(null == direction
                    ? new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3, 3}, 0)
                    : new BasicStroke(2));
            g2.setColor(CONE);
            g2.draw(knob);
        } finally {
            g2.dispose();
        }
    }

    private boolean onHandle(Point p) {
        return null != anchor && handleOnScreen().distance(p) <= HANDLE + 4;
    }

    /** Wraps the map's pan listener: drags that start on the handle turn the direction instead of panning. */
    public MouseInputListener wrap(MouseInputListener pan) {
        return new MouseInputListener() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && onHandle(e.getPoint())) {
                    dragging = true;
                    e.consume();
                } else {
                    pan.mousePressed(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging) {
                    double d = Math.round(bearing(anchorOnScreen(), e.getPoint()));
                    direction = d % 360;
                    onDrag.accept(direction);
                    map.repaint();
                    e.consume();
                } else {
                    pan.mouseDragged(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragging) {
                    dragging = false;
                    e.consume();
                } else {
                    pan.mouseReleased(e);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                map.setCursor(onHandle(e.getPoint())
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                map.setToolTipText(onHandle(e.getPoint()) ? "Drag to set the camera direction" : null);
                pan.mouseMoved(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                pan.mouseClicked(e);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                pan.mouseEntered(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                pan.mouseExited(e);
            }
        };
    }
}
