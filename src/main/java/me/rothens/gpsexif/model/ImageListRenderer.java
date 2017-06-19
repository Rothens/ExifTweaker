package me.rothens.gpsexif.model;

import javax.swing.*;
import java.awt.*;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImageListRenderer extends JLabel implements ListCellRenderer<ImageFile> {

    public ImageListRenderer() {
        setOpaque(true);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends ImageFile> list, ImageFile value, int index, boolean isSelected, boolean cellHasFocus) {
        if (isSelected) {
            setBackground(list.getSelectionBackground());
        } else {
            setBackground(list.getBackground());
        }

        if (value.hasExifGPS()) {
            setForeground(Color.GREEN);
        } else {
            setForeground(Color.RED);
        }
        setText(value.file.getName());
        return this;
    }
}
