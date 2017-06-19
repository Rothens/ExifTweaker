package me.rothens.gpsexif;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.viewer.DefaultWaypointRenderer;
import org.jxmapviewer.viewer.Waypoint;

import java.awt.*;
import java.awt.geom.Point2D;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class SelectionWaypointRenderer extends DefaultWaypointRenderer {

    @Override
    public void paintWaypoint(Graphics2D g, JXMapViewer map, Waypoint w) {
        super.paintWaypoint(g, map, w);
        if (w instanceof SelectionWaypoint) {
            Point2D point = map.getTileFactory().geoToPixel(w.getPosition(), map.getZoom());
            int x = (int) (point.getX() - 2);
            int y = (int) (point.getY() - 18);
            g.setColor(Color.BLACK);
            g.drawString("*", x - 1, y);
            g.drawString("*", x + 1, y);
            g.drawString("*", x, y - 1);
            g.drawString("*", x, y + 1);
            g.setColor(Color.WHITE);
            g.drawString("*", x, y);
        }
    }


}
