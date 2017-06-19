package me.rothens.gpsexif;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImagePanel extends JPanel {
    private BufferedImage image;

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null) {
            int h = (int) (getWidth() * ((double) image.getHeight() / image.getWidth()));
            g.drawImage(image, 0, (getHeight() / 2) - h / 2, getWidth(), h, this);
        }
    }
}
