package me.rothens.gpsexif.gpx;

import org.jxmapviewer.viewer.GeoPosition;

import java.time.Instant;

/**
 * One recorded point of a track.
 *
 * @param time      when it was recorded, or {@code null} if the file has no time for it
 * @param elevation metres above sea level, or {@code null}
 */
public record TrackPoint(Instant time, GeoPosition position, Double elevation) {
}
