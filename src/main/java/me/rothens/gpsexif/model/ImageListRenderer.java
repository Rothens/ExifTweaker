package me.rothens.gpsexif.model;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import java.awt.*;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImageListRenderer extends JLabel implements ListCellRenderer<ImageFile> {

    private static final Color HAS_GPS = new Color(0, 140, 0);
    private static final Color NO_GPS = new Color(190, 0, 0);
    private static final Color HAS_GPS_DARK = new Color(110, 210, 110);
    private static final Color NO_GPS_DARK = new Color(240, 110, 110);

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

        boolean dark = FlatLaf.isLafDark();
        if (value.hasExifGPS()) {
            setForeground(dark ? HAS_GPS_DARK : HAS_GPS);
        } else {
            setForeground(dark ? NO_GPS_DARK : NO_GPS);
        }
        setText(value.getFile().getName());
        setToolTipText(value.hasExifGPS() ? "Has GPS position" : "No GPS position");
        return this;
    }
}
