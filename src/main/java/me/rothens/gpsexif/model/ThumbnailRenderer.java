package me.rothens.gpsexif.model;

import com.formdev.flatlaf.FlatLaf;
import me.rothens.gpsexif.util.ThumbnailCache;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A cell of the thumbnail grid: the picture, a green / red dot for "has a location", the name and the date. */
public class ThumbnailRenderer extends JComponent implements ListCellRenderer<ImageFile> {

    /** Longest side of a thumbnail. */
    public static final int THUMBNAIL_SIZE = 96;
    public static final int CELL_WIDTH = THUMBNAIL_SIZE + 12;

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss",
            Locale.ENGLISH);
    private static final Color HAS_GPS = new Color(40, 160, 60);
    private static final Color NO_GPS = new Color(210, 40, 40);

    private final ThumbnailCache thumbnails;
    private ImageFile photo;
    private BufferedImage image;
    private boolean selected;
    private boolean focused;
    private Color selectionBackground;
    private Color foreground;
    private Color dimForeground;

    public ThumbnailRenderer(ThumbnailCache thumbnails) {
        this.thumbnails = thumbnails;
        setOpaque(false);
    }

    /** Cell height for the given font: thumbnail plus two lines of text. */
    public static int cellHeight(Font font, JComponent c) {
        FontMetrics fm = c.getFontMetrics(font);
        return THUMBNAIL_SIZE + 10 + 2 * fm.getHeight() + 4;
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends ImageFile> list, ImageFile value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        photo = value;
        image = thumbnails.get(value);
        selected = isSelected;
        focused = cellHasFocus;
        selectionBackground = list.getSelectionBackground();
        foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
        dimForeground = isSelected ? list.getSelectionForeground() : UIManager.getColor("Label.disabledForeground");
        setFont(list.getFont());
        String place = value.hasExifGPS() ? "has a location" : "no location yet";
        setToolTipText("<html><b>" + escape(value.getFile().getName()) + "</b><br>"
                + (null == value.getTaken() ? "no date" : FULL_DATE.format(value.getTaken())) + "<br>" + place
                + (value.isWritable() ? "" : "<br>Read-only: this file type needs ExifTool") + "</html>");
        return this;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            if (selected) {
                g2.setColor(selectionBackground);
                g2.fill(new RoundRectangle2D.Double(1, 1, w - 2, getHeight() - 2, 10, 10));
            } else if (focused) {
                g2.setColor(selectionBackground);
                g2.draw(new RoundRectangle2D.Double(1, 1, w - 3, getHeight() - 3, 10, 10));
            }
            // The picture, centred in a square box
            int box = THUMBNAIL_SIZE;
            int bx = (w - box) / 2;
            int by = 5;
            if (null != image) {
                int x = bx + (box - image.getWidth()) / 2;
                int y = by + (box - image.getHeight()) / 2;
                g2.drawImage(image, x, y, null);
                if (!photo.isWritable()) {
                    g2.setColor(new Color(128, 128, 128, 140));
                    g2.fillRect(x, y, image.getWidth(), image.getHeight());
                }
            } else {
                g2.setColor(FlatLaf.isLafDark() ? new Color(60, 63, 65) : new Color(225, 225, 225));
                g2.fill(new RoundRectangle2D.Double(bx + 8, by + 18, box - 16, box - 36, 6, 6));
                if (thumbnails.hasFailed(photo)) {
                    g2.setColor(dimForeground);
                    g2.setFont(getFont().deriveFont(getFont().getSize2D() - 2f));
                    String text = "no preview";
                    g2.drawString(text, bx + (box - g2.getFontMetrics().stringWidth(text)) / 2, by + box / 2 + 4);
                }
            }
            // Location badge, top right of the box
            double r = 6;
            double cx = bx + box - r - 2;
            double cy = by + r + 2;
            Ellipse2D dot = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            g2.setColor(photo.hasExifGPS() ? HAS_GPS : NO_GPS);
            g2.fill(dot);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(dot);
            // Name and date
            Font font = getFont();
            FontMetrics fm = g2.getFontMetrics(font);
            int ty = by + box + 4 + fm.getAscent();
            g2.setFont(font);
            g2.setColor(foreground);
            drawCentered(g2, fm, photo.getFile().getName(), w, ty);
            Font small = font.deriveFont(font.getSize2D() - 2f);
            FontMetrics sm = g2.getFontMetrics(small);
            g2.setFont(small);
            g2.setColor(dimForeground);
            drawCentered(g2, sm, null == photo.getTaken() ? "no date" : SHORT_DATE.format(photo.getTaken()), w,
                    ty + fm.getDescent() + sm.getAscent() + 1);
        } finally {
            g2.dispose();
        }
    }

    /** Draws {@code text} centred, shortened with "…" if it doesn't fit. */
    private static void drawCentered(Graphics2D g2, FontMetrics fm, String text, int width, int y) {
        int max = width - 6;
        String shown = text;
        if (fm.stringWidth(shown) > max) {
            while (shown.length() > 1 && fm.stringWidth(shown + "…") > max) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown += "…";
        }
        g2.drawString(shown, (width - fm.stringWidth(shown)) / 2, y);
    }
}
