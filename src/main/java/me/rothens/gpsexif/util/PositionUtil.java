package me.rothens.gpsexif.util;

import org.jxmapviewer.viewer.GeoPosition;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Rothens on 2017. 06. 20..
 */
public final class PositionUtil {

    /** Decimal pair, e.g. "47.4979;19.0402", "47.4979, 19.0402" or "-33.86 151.21". */
    private static final Pattern DECIMAL = Pattern.compile(
            "^\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*[;,\\s]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*$");

    /** One degrees/minutes/seconds component, e.g. 47°29'52.4"N (minutes and seconds optional). */
    private static final String DMS_PART =
            "(\\d+(?:\\.\\d+)?)\\s*°\\s*(?:(\\d+(?:\\.\\d+)?)\\s*['′]\\s*)?(?:(\\d+(?:\\.\\d+)?)\\s*(?:\"|″|'')\\s*)?([NSEWnsew])";
    private static final Pattern DMS = Pattern.compile(
            "^\\s*" + DMS_PART + "\\s*[;,]?\\s*" + DMS_PART + "\\s*$");

    private PositionUtil() {
    }

    public static String getPositionString(GeoPosition gp) {
        return String.format(Locale.ROOT, "%.6f;%.6f", gp.getLatitude(), gp.getLongitude());
    }

    /**
     * Parses a coordinate typed by the user. Accepts decimal degrees ("lat;lon", "lat, lon", "lat lon")
     * and degrees/minutes/seconds notation such as {@code 47°29'52"N 19°2'24"E}.
     *
     * @throws IllegalArgumentException if the text can't be parsed or is out of range
     */
    public static GeoPosition parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("No coordinate given");
        }
        double lat;
        double lon;
        Matcher m = DECIMAL.matcher(text);
        if (m.matches()) {
            lat = Double.parseDouble(m.group(1));
            lon = Double.parseDouble(m.group(2));
        } else {
            m = DMS.matcher(text);
            if (!m.matches()) {
                throw new IllegalArgumentException("Unrecognised coordinate format: " + text);
            }
            double first = dmsToDegrees(m.group(1), m.group(2), m.group(3), m.group(4));
            double second = dmsToDegrees(m.group(5), m.group(6), m.group(7), m.group(8));
            boolean firstIsLat = "NS".indexOf(Character.toUpperCase(m.group(4).charAt(0))) >= 0;
            boolean secondIsLat = "NS".indexOf(Character.toUpperCase(m.group(8).charAt(0))) >= 0;
            if (firstIsLat == secondIsLat) {
                throw new IllegalArgumentException("Need one N/S and one E/W component: " + text);
            }
            lat = firstIsLat ? first : second;
            lon = firstIsLat ? second : first;
        }
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Longitude out of range: " + lon);
        }
        return new GeoPosition(lat, lon);
    }

    private static double dmsToDegrees(String deg, String min, String sec, String hemisphere) {
        double value = Double.parseDouble(deg);
        if (min != null) {
            value += Double.parseDouble(min) / 60.0;
        }
        if (sec != null) {
            value += Double.parseDouble(sec) / 3600.0;
        }
        char h = Character.toUpperCase(hemisphere.charAt(0));
        return (h == 'S' || h == 'W') ? -value : value;
    }
}
