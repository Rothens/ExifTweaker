package me.rothens.gpsexif.gpx;

import me.rothens.gpsexif.util.JsonElements;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.io.Reader;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Maps location history:
 * <ul>
 *     <li>Takeout's {@code Records.json}: {@code {"locations": [{"latitudeE7", "longitudeE7", "timestamp"}, ...]}}</li>
 *     <li>the on-device Timeline export from Android: {@code {"semanticSegments": [...], "rawSignals": [...]}}</li>
 *     <li>the on-device Timeline export from iPhone: {@code [{"startTime", "visit"/"activity"/"timelinePath"}, ...]}</li>
 * </ul>
 * Places visited become two points (arrival and leaving), so photos taken there match; journeys become their path.
 */
final class GoogleTimeline {

    /** Points recorded this imprecisely (e.g. from a cell tower) are left out. */
    private static final double MAX_ACCURACY_M = 1000;
    private static final Pattern DEGREES = Pattern.compile("(-?\\d+(?:\\.\\d+)?)°?,\\s*(-?\\d+(?:\\.\\d+)?)°?");

    private GoogleTimeline() {
    }

    /**
     * @param keep which times to keep (e.g. only around the photos), to keep years of history out of memory
     * @return the points in time order, or an empty list if this isn't a Google timeline
     */
    static List<TrackPoint> read(Reader json, Predicate<Instant> keep) throws IOException {
        List<TrackPoint> points = new ArrayList<>();
        JsonElements.forEach(json, (key, element) -> {
            if (!(element instanceof Map<?, ?> map)) {
                return;
            }
            if ("locations".equals(key)) {
                record(map, points, keep);
            } else if ("rawSignals".equals(key)) {
                if (map.get("position") instanceof Map<?, ?> position) {
                    rawSignal(position, points, keep);
                }
            } else if ("semanticSegments".equals(key) || null == key) {
                segment(map, points, keep);
            }
        });
        points.sort(Comparator.comparing(TrackPoint::time));
        // One point per moment
        List<TrackPoint> unique = new ArrayList<>();
        for (TrackPoint p : points) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).time().equals(p.time())) {
                unique.add(p);
            }
        }
        return unique;
    }

    /** Takeout's Records.json. */
    private static void record(Map<?, ?> location, List<TrackPoint> points, Predicate<Instant> keep) {
        if (!(location.get("latitudeE7") instanceof Double lat) || !(location.get("longitudeE7") instanceof Double lon)) {
            return;
        }
        if (location.get("accuracy") instanceof Double accuracy && accuracy > MAX_ACCURACY_M) {
            return;
        }
        Instant time = location.get("timestamp") instanceof String s ? instant(s)
                : location.get("timestampMs") instanceof String ms && ms.matches("\\d{1,15}")
                ? Instant.ofEpochMilli(Long.parseLong(ms)) : null;
        Double altitude = location.get("altitude") instanceof Double a ? a : null;
        add(points, keep, time, position(lat / 1e7, lon / 1e7), altitude);
    }

    /** Android export: a raw position. */
    private static void rawSignal(Map<?, ?> position, List<TrackPoint> points, Predicate<Instant> keep) {
        if (position.get("accuracyMeters") instanceof Double accuracy && accuracy > MAX_ACCURACY_M) {
            return;
        }
        Object latLng = null != position.get("LatLng") ? position.get("LatLng") : position.get("latLng");
        Double altitude = position.get("altitudeMeters") instanceof Double a ? a : null;
        add(points, keep, position.get("timestamp") instanceof String s ? instant(s) : null, latLng(latLng),
                altitude);
    }

    /** Android or iPhone export: a visit, a journey, or a path. */
    private static void segment(Map<?, ?> segment, List<TrackPoint> points, Predicate<Instant> keep) {
        Instant start = segment.get("startTime") instanceof String s ? instant(s) : null;
        Instant end = segment.get("endTime") instanceof String s ? instant(s) : null;
        if (segment.get("visit") instanceof Map<?, ?> visit && visit.get("topCandidate") instanceof Map<?, ?> top) {
            Object place = top.get("placeLocation");
            GeoPosition at = latLng(place instanceof Map<?, ?> m ? m.get("latLng") : place);
            add(points, keep, start, at, null);
            add(points, keep, end, at, null);
        }
        if (segment.get("activity") instanceof Map<?, ?> activity) {
            Object from = activity.get("start");
            Object to = activity.get("end");
            add(points, keep, start, latLng(from instanceof Map<?, ?> m ? m.get("latLng") : from), null);
            add(points, keep, end, latLng(to instanceof Map<?, ?> m ? m.get("latLng") : to), null);
        }
        if (segment.get("timelinePath") instanceof List<?> path) {
            for (Object o : path) {
                if (o instanceof Map<?, ?> p) {
                    Instant time = p.get("time") instanceof String s ? instant(s) : null;
                    if (null == time && null != start && p.get("durationMinutesOffsetFromStartTime") instanceof String m
                            && m.matches("\\d{1,7}")) {
                        time = start.plus(Duration.ofMinutes(Long.parseLong(m)));
                    }
                    add(points, keep, time, latLng(p.get("point")), null);
                }
            }
        }
    }

    private static void add(List<TrackPoint> points, Predicate<Instant> keep, Instant time, GeoPosition position,
                            Double altitude) {
        if (null != time && null != position && keep.test(time)) {
            points.add(new TrackPoint(time, position, altitude));
        }
    }

    /** "47.4979414°, 19.0402350°" (Android) or "geo:47.497941,19.040235" (iPhone). */
    static GeoPosition latLng(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        Matcher m = DEGREES.matcher(s.startsWith("geo:") ? s.substring(4) : s);
        if (!m.find()) {
            return null;
        }
        return position(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)));
    }

    private static GeoPosition position(double lat, double lon) {
        return Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0) ? new GeoPosition(lat, lon) : null;
    }

    private static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text.strip()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text.strip());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
