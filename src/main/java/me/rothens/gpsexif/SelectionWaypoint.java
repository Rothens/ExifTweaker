package me.rothens.gpsexif;

import org.jxmapviewer.viewer.DefaultWaypoint;
import org.jxmapviewer.viewer.GeoPosition;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class SelectionWaypoint extends DefaultWaypoint {
    public SelectionWaypoint() {
    }

    public SelectionWaypoint(double latitude, double longitude) {
        super(latitude, longitude);
    }

    public SelectionWaypoint(GeoPosition coord) {
        super(coord);
    }
}
