package me.rothens.gpsexif;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImagePanel extends JPanel {
    private BufferedImage image;
    private String message;

    public void setImage(BufferedImage image) {
        this.image = image;
        this.message = null;
        repaint();
    }

    /** Shows a short text instead of an image, e.g. why there's no preview. */
    public void setMessage(String message) {
        this.image = null;
        this.message = message;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null) {
            // Fit the image into the panel while keeping its aspect ratio
            double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int w = (int) (image.getWidth() * scale);
            int h = (int) (image.getHeight() * scale);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, this);
        } else if (message != null) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(UIManager.getColor("Label.disabledForeground"));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(message, (getWidth() - fm.stringWidth(message)) / 2, getHeight() / 2);
        }
    }
}
