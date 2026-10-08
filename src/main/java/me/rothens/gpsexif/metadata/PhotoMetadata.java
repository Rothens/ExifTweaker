package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.jxmapviewer.viewer.GeoPosition;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
 * @param direction   direction the camera pointed, degrees clockwise from north, or {@code null}
 * @param text        editable text fields that are set
 * @param place       the place name (IPTC location), or {@code null}
 */
public record PhotoMetadata(GeoPosition position, int orientation, List<ExifData> fields, LocalDateTime taken,
                            ZoneOffset takenOffset, Double altitude, Double direction, Map<TextTag, String> text,
                            Place place) {

    public static final PhotoMetadata EMPTY = new PhotoMetadata(null, 1, List.of(), null, null, null, null, Map.of());

    public PhotoMetadata {
        fields = List.copyOf(fields);
        text = Map.copyOf(text);
        place = null == place || place.isEmpty() ? null : place;
    }

    /** Without a place name. */
    public PhotoMetadata(GeoPosition position, int orientation, List<ExifData> fields, LocalDateTime taken,
                         ZoneOffset takenOffset, Double altitude, Double direction, Map<TextTag, String> text) {
        this(position, orientation, fields, taken, takenOffset, altitude, direction, text, null);
    }

    /** The same with another place name. */
    public PhotoMetadata withPlace(Place place) {
        return new PhotoMetadata(position, orientation, fields, taken, takenOffset, altitude, direction, text, place);
    }
}
