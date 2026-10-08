package me.rothens.gpsexif.gpx;

import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.viewer.GeoPosition;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Photos that have a location (e.g. taken with a phone) used as a track, to place the photos of a camera without
 * GPS by time - like a GPX file, only with fewer points.
 */
public final class PhotoTrack {

    private PhotoTrack() {
    }

    /**
     * A track through the photos that have both a location and a date, in the order they were taken.
     *
     * @param zone time zone for photos that don't record their own UTC offset
     * @return the track, or {@code null} if fewer than one photo qualifies
     */
    public static Track of(String name, List<ImageFile> photos, ZoneId zone) {
        List<TrackPoint> points = new ArrayList<>();
        for (ImageFile photo : photos) {
            GeoPosition position = photo.getGp();
            if (null == position || null == photo.getTaken()) {
                continue;
            }
            Instant time = PhotoTime.toInstant(photo.getTaken(), photo.getTakenOffset(), zone, Duration.ZERO);
            points.add(new TrackPoint(time, position, photo.getAltitude()));
        }
        if (points.isEmpty()) {
            return null;
        }
        points.sort(Comparator.comparing(TrackPoint::time));
        // Several photos taken in the same second (bursts): keep the first, so times stay increasing
        List<TrackPoint> unique = new ArrayList<>();
        for (TrackPoint p : points) {
            if (unique.isEmpty() || !Objects.equals(unique.get(unique.size() - 1).time(), p.time())) {
                unique.add(p);
            }
        }
        return new Track(name, List.of(unique));
    }

    /** How many of {@code photos} could be used as track points. */
    public static long usable(List<ImageFile> photos) {
        return photos.stream().filter(p -> null != p.getGp() && null != p.getTaken()).count();
    }
}
