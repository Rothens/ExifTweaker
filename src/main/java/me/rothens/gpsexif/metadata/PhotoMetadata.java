package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.jxmapviewer.viewer.GeoPosition;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The metadata of one photo as far as ExifTweaker cares about it.
 *
 * @param position    GPS position, or {@code null} if the photo has none
 * @param orientation EXIF orientation (1-8), 1 when unknown
 * @param fields      selected EXIF fields for display, sorted by key
 * @param taken       when the photo was taken, as shown by the camera's clock (EXIF has no time zone), or
 *                    {@code null}
 * @param takenOffset the camera's UTC offset when the photo was taken, if recorded (EXIF 2.31), or {@code null}
 * @param altitude    GPS altitude in metres above sea level (negative below), or {@code null}
 */
public record PhotoMetadata(GeoPosition position, int orientation, List<ExifData> fields, LocalDateTime taken,
                            ZoneOffset takenOffset, Double altitude) {

    public static final PhotoMetadata EMPTY = new PhotoMetadata(null, 1, List.of(), null, null, null);

    public PhotoMetadata {
        fields = List.copyOf(fields);
    }
}
