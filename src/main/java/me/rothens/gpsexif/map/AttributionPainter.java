package me.rothens.gpsexif.map;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.Painter;

import java.awt.*;

/** Draws the tile provider's required attribution in the bottom-right corner of the map. */
public class AttributionPainter implements Painter<JXMapViewer> {

    private static final int PADDING = 3;

    @Override
    public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
        String text = map.getTileFactory().getInfo().getAttribution();
        if (null == text || text.isBlank()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(map.getFont().deriveFont(Font.PLAIN, 11f));
            FontMetrics fm = g2.getFontMetrics();
            int boxWidth = fm.stringWidth(text) + 2 * PADDING;
            int boxHeight = fm.getHeight() + PADDING;
            int x = map.getWidth() - boxWidth;
            int y = map.getHeight() - boxHeight;
            g2.setColor(new Color(255, 255, 255, 190));
            g2.fillRect(x, y, boxWidth, boxHeight);
            g2.setColor(new Color(40, 40, 40));
            g2.drawString(text, x + PADDING, y + fm.getAscent() + PADDING / 2);
        } finally {
            g2.dispose();
        }
    }
}
