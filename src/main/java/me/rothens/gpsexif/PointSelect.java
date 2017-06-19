package me.rothens.gpsexif;

import org.jxmapviewer.viewer.GeoPosition;

import java.awt.*;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public interface PointSelect {
    void onSelect(GeoPosition position);
}
