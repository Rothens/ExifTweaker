package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.model.ExifData;
import org.jxmapviewer.viewer.GeoPosition;

import java.util.List;

/**
 * The metadata of one photo as far as ExifTweaker cares about it.
 *
 * @param position    GPS position, or {@code null} if the photo has none
 * @param orientation EXIF orientation (1-8), 1 when unknown
 * @param fields      selected EXIF fields for display, sorted by key
 */
public record PhotoMetadata(GeoPosition position, int orientation, List<ExifData> fields) {

    public static final PhotoMetadata EMPTY = new PhotoMetadata(null, 1, List.of());

    public PhotoMetadata {
        fields = List.copyOf(fields);
    }
}
