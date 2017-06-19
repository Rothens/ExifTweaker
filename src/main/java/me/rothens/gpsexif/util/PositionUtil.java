package me.rothens.gpsexif.util;

import org.jxmapviewer.viewer.GeoPosition;

/**
 * Created by Rothens on 2017. 06. 20..
 */
public class PositionUtil {
    public static String getPositionString(GeoPosition gp){
        return String.format("%s;%s", gp.getLatitude(), gp.getLongitude());
    }
}
