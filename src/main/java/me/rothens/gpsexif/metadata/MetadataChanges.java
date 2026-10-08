package me.rothens.gpsexif.metadata;

import org.jxmapviewer.viewer.GeoPosition;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * A set of changes to write to one photo in one go. Anything not set is left as it is.
 */
public final class MetadataChanges {

    private GeoPosition position;
    private boolean removePosition;
    private Double altitude;
    private boolean removeAltitude;
    private Double direction;
    private boolean removeDirection;
    private final Map<TextTag, String> text = new EnumMap<>(TextTag.class);
    private LocalDateTime taken;
    private Place place;
    private Duration timeShift;

    public MetadataChanges position(GeoPosition position) {
        this.position = position;
        return this;
    }

    /**
     * Removes all GPS data (position, altitude, direction) and the place name. Applied before any new GPS values in
     * this change.
     */
    public MetadataChanges removePosition() {
        this.removePosition = true;
        if (null == place) {
            place = Place.NONE;
        }
        return this;
    }

    /** Sets the place name (City, State, Country, ...); {@link Place#NONE} removes it. */
    public MetadataChanges place(Place place) {
        this.place = place;
        return this;
    }

    /** The place to write, {@link Place#NONE} to remove it, or {@code null} to leave it as it is. */
    public Place getPlace() {
        return place;
    }

    /** Altitude in metres above sea level (negative below); {@code null} removes it. */
    public MetadataChanges altitude(Double altitude) {
        this.altitude = altitude;
        this.removeAltitude = null == altitude;
        return this;
    }

    /** Camera direction in degrees clockwise from true north; {@code null} removes it. */
    public MetadataChanges direction(Double degrees) {
        this.direction = null == degrees ? null : normalizeDegrees(degrees);
        this.removeDirection = null == degrees;
        return this;
    }

    /** Sets a text field; blank removes it. */
    public MetadataChanges text(TextTag field, String value) {
        text.put(field, null == value ? "" : value.strip());
        return this;
    }

    /** Sets the time the photo was taken (DateTimeOriginal). */
    public MetadataChanges taken(LocalDateTime taken) {
        this.taken = taken;
        return this;
    }

    /** Moves all of the photo's date/time fields by this amount. */
    public MetadataChanges shiftTime(Duration shift) {
        this.timeShift = shift;
        return this;
    }

    public GeoPosition getPosition() {
        return position;
    }

    public boolean isRemovePosition() {
        return removePosition;
    }

    public Double getAltitude() {
        return altitude;
    }

    public boolean isRemoveAltitude() {
        return removeAltitude;
    }

    public Double getDirection() {
        return direction;
    }

    public boolean isRemoveDirection() {
        return removeDirection;
    }

    public Map<TextTag, String> getText() {
        return Collections.unmodifiableMap(text);
    }

    public LocalDateTime getTaken() {
        return taken;
    }

    public Duration getTimeShift() {
        return timeShift;
    }

    public static double normalizeDegrees(double degrees) {
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }
}
