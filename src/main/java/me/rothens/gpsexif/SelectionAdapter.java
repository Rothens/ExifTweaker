package me.rothens.gpsexif;

import org.jxmapviewer.JXMapViewer;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class SelectionAdapter extends MouseAdapter {

    JXMapViewer viewer;
    PointSelect ps;

    public SelectionAdapter(JXMapViewer viewer, PointSelect ps) {
        this.viewer = viewer;
        this.ps = ps;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON3)
            return;
        ps.onSelect(viewer.convertPointToGeoPosition(e.getPoint()));
    }


}
