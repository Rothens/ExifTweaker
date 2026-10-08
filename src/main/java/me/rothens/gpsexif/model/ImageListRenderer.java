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

    private static final Icon PREFER = new TripMarkIcon(TripMark.PREFER, 13);
    private static final Icon SKIP = new TripMarkIcon(TripMark.SKIP, 13);

    public ImageListRenderer() {
        setOpaque(true);
        setHorizontalTextPosition(LEADING); // the trip badge after the name
        setIconTextGap(6);
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
        TripMark mark = value.getTripMark();
        setIcon(TripMark.PREFER == mark ? PREFER : TripMark.SKIP == mark ? SKIP : null);
        String trip = TripMark.PREFER == mark ? " - preferred in trips" : TripMark.SKIP == mark ? " - skipped in trips" : "";
        if (TripMark.SKIP == mark && !isSelected) {
            Color c = getForeground();
            setForeground(new Color(c.getRed(), c.getGreen(), c.getBlue(), 130));
        }
        if (value.isWritable()) {
            setFont(list.getFont());
            setToolTipText((value.hasExifGPS() ? null == value.getPlace() ? "Has GPS position"
                    : value.getPlace().label() : "No GPS position") + trip);
        } else {
            setFont(list.getFont().deriveFont(Font.ITALIC));
            setForeground(UIManager.getColor("Label.disabledForeground"));
            setToolTipText("Read-only: this file type needs ExifTool (see the banner at the top)");
        }
        return this;
    }
}
