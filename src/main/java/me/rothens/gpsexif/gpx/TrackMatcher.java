package me.rothens.gpsexif.gpx;

import org.jxmapviewer.viewer.GeoPosition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds where a photo was taken by looking up its (UTC) time on one or more tracks.
 * <ul>
 *     <li>Between two points of the same segment that are at most {@code maxGap} apart, the position is
 *     interpolated linearly.</li>
 *     <li>Otherwise the nearest point in time is used, if it's at most {@code maxGap} away.</li>
 *     <li>Photos further than {@code maxGap} from every point stay unmatched.</li>
 * </ul>
 * Positions are never interpolated across segments, as a segment break usually means the logger was off.
 */
public class TrackMatcher {

    /**
     * Outcome of matching one photo.
     *
     * @param position  the matched position, or {@code null} if unmatched
     * @param elevation interpolated elevation in metres, or {@code null}
     * @param gap       time between the photo and the nearest track point ({@code null} if there are no timed points)
     */
    public record Match(GeoPosition position, Double elevation, Duration gap) {
        public boolean isMatched() {
            return null != position;
        }
    }

    private final List<List<TrackPoint>> segments = new ArrayList<>();

    public TrackMatcher(List<Track> tracks) {
        for (Track track : tracks) {
            for (List<TrackPoint> segment : track.segments()) {
                List<TrackPoint> timed = new ArrayList<>(segment.stream().filter(p -> null != p.time()).toList());
                if (!timed.isEmpty()) {
                    timed.sort(Comparator.comparing(TrackPoint::time));
                    segments.add(timed);
                }
            }
        }
    }

    public boolean hasTimedPoints() {
        return !segments.isEmpty();
    }

    public Match match(Instant time, Duration maxGap) {
        TrackPoint nearest = null;
        Duration nearestGap = null;
        for (List<TrackPoint> segment : segments) {
            int i = indexAtOrBefore(segment, time);
            TrackPoint before = i >= 0 ? segment.get(i) : null;
            TrackPoint after = i + 1 < segment.size() ? segment.get(i + 1) : null;
            if (null != before && null != after
                    && Duration.between(before.time(), after.time()).compareTo(maxGap) <= 0) {
                Duration gap = min(Duration.between(before.time(), time), Duration.between(time, after.time()));
                return interpolate(before, after, time, gap);
            }
            for (TrackPoint candidate : new TrackPoint[]{before, after}) {
                if (null != candidate) {
                    Duration gap = Duration.between(candidate.time(), time).abs();
                    if (null == nearestGap || gap.compareTo(nearestGap) < 0) {
                        nearest = candidate;
                        nearestGap = gap;
                    }
                }
            }
        }
        if (null != nearest && nearestGap.compareTo(maxGap) <= 0) {
            return new Match(nearest.position(), nearest.elevation(), nearestGap);
        }
        return new Match(null, null, nearestGap);
    }

    /** The timed track point closest to {@code position} (by distance), or {@code null} if there is none. */
    public TrackPoint nearestPoint(GeoPosition position) {
        TrackPoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (List<TrackPoint> segment : segments) {
            for (TrackPoint p : segment) {
                double d = distanceMetres(position, p.position());
                if (d < bestDistance) {
                    best = p;
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    /** Great-circle distance (haversine). */
    public static double distanceMetres(GeoPosition a, GeoPosition b) {
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Index of the last point at or before {@code time}, or -1. */
    private static int indexAtOrBefore(List<TrackPoint> segment, Instant time) {
        int lo = 0;
        int hi = segment.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (!segment.get(mid).time().isAfter(time)) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private static Match interpolate(TrackPoint a, TrackPoint b, Instant time, Duration gap) {
        long span = Duration.between(a.time(), b.time()).toMillis();
        double f = span == 0 ? 0 : (double) Duration.between(a.time(), time).toMillis() / span;
        double lat = a.position().getLatitude() + f * (b.position().getLatitude() - a.position().getLatitude());
        double dLon = b.position().getLongitude() - a.position().getLongitude();
        // Take the short way round across the antimeridian
        if (dLon > 180) {
            dLon -= 360;
        } else if (dLon < -180) {
            dLon += 360;
        }
        double lon = a.position().getLongitude() + f * dLon;
        if (lon > 180) {
            lon -= 360;
        } else if (lon < -180) {
            lon += 360;
        }
        Double elevation;
        if (null != a.elevation() && null != b.elevation()) {
            elevation = a.elevation() + f * (b.elevation() - a.elevation());
        } else {
            elevation = null != a.elevation() ? a.elevation() : b.elevation();
        }
        return new Match(new GeoPosition(lat, lon), elevation, gap);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
