package me.rothens.gpsexif.model;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/** The badge of a trip mark: a gold star for "prefer", a crossed-out circle for "skip". */
public final class TripMarkIcon implements Icon {

    private static final Color STAR = new Color(245, 180, 20);
    private static final Color SKIP = new Color(130, 130, 130);

    private final TripMark mark;
    private final int size;

    public TripMarkIcon(TripMark mark, int size) {
        this.mark = mark;
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        paint((Graphics2D) g, mark, x, y, size);
    }

    /** Paints the badge into the square at (x, y); nothing for {@link TripMark#NORMAL}. */
    public static void paint(Graphics2D g, TripMark mark, double x, double y, double size) {
        if (TripMark.NORMAL == mark) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double cx = x + size / 2;
            double cy = y + size / 2;
            if (TripMark.PREFER == mark) {
                Path2D star = new Path2D.Double();
                double outer = size / 2;
                double inner = outer * 0.45;
                for (int i = 0; i < 10; i++) {
                    double r = i % 2 == 0 ? outer : inner;
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    double px = cx + r * Math.cos(a);
                    double py = cy + r * Math.sin(a) + size * 0.04;
                    if (0 == i) {
                        star.moveTo(px, py);
                    } else {
                        star.lineTo(px, py);
                    }
                }
                star.closePath();
                g2.setColor(STAR);
                g2.fill(star);
                g2.setColor(STAR.darker());
                g2.setStroke(new BasicStroke((float) Math.max(1, size / 14)));
                g2.draw(star);
            } else {
                double r = size / 2 - 1;
                g2.setColor(Color.WHITE);
                g2.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                g2.setColor(SKIP);
                g2.setStroke(new BasicStroke((float) Math.max(1.5, size / 7)));
                g2.draw(new Ellipse2D.Double(cx - r * 0.8, cy - r * 0.8, r * 1.6, r * 1.6));
                double d = r * 0.8 * Math.sqrt(0.5);
                g2.draw(new Line2D.Double(cx - d, cy + d, cx + d, cy - d));
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
