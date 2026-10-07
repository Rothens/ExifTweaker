package me.rothens.gpsexif.gpx;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A loaded GPX file. Each segment is a continuous recording; positions are never interpolated across the break
 * between two segments.
 */
public record Track(String name, List<List<TrackPoint>> segments) {

    public Track {
        segments = segments.stream().map(List::copyOf).toList();
    }

    public int pointCount() {
        return segments.stream().mapToInt(List::size).sum();
    }

    /** Time of the first recorded point, or {@code null} if the track has no times. */
    public Instant start() {
        return segments.stream().flatMap(List::stream).map(TrackPoint::time).filter(Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
    }

    /** Time of the last recorded point, or {@code null} if the track has no times. */
    public Instant end() {
        return segments.stream().flatMap(List::stream).map(TrackPoint::time).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }
}
