package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.gpx.PhotoTime;
import me.rothens.gpsexif.model.ImageFile;

import javax.swing.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Which time zone the playback windows show times in: the camera's clock (as the photos recorded it), or a
 * chosen zone, e.g. the local time of a trip abroad when the camera stayed on home time.
 */
public final class ClockZone {

    public static final String CAMERA_CLOCK = "Camera clock";

    private final ZoneId cameraZone;
    private final ZoneId displayZone;

    /**
     * @param cameraZone  zone the camera's clock was set to (for photos that don't record their own offset)
     * @param displayZone zone to show times in; {@code null} for the camera's clock
     */
    public ClockZone(ZoneId cameraZone, ZoneId displayZone) {
        this.cameraZone = cameraZone;
        this.displayZone = displayZone;
    }

    public ZoneId getCameraZone() {
        return cameraZone;
    }

    /** {@code null} when times are shown as the camera's clock. */
    public ZoneId getDisplayZone() {
        return displayZone;
    }

    /** The moment a photo was taken, or {@code null} without a date. */
    public Instant instant(ImageFile photo) {
        return null == photo.getTaken() ? null
                : PhotoTime.toInstant(photo.getTaken(), photo.getTakenOffset(), cameraZone, Duration.ZERO);
    }

    /** The time to show for a photo: as recorded for the camera's clock, else converted to the chosen zone. */
    public LocalDateTime local(ImageFile photo) {
        if (null == displayZone || null == photo.getTaken()) {
            return photo.getTaken();
        }
        return instant(photo).atZone(displayZone).toLocalDateTime();
    }

    /** The time to show for a moment on the timeline. */
    public LocalDateTime local(Instant time) {
        return time.atZone(null == displayZone ? cameraZone : displayZone).toLocalDateTime();
    }

    /** The UTC offset shown next to the date (e.g. "UTC+9"), or {@code null} for the camera's clock. */
    public String offsetLabel(Instant time) {
        if (null == displayZone || null == time) {
            return null;
        }
        return formatOffset(displayZone.getRules().getOffset(time));
    }

    public String offsetLabel(ImageFile photo) {
        return null == photo.getTaken() ? null : offsetLabel(instant(photo));
    }

    static String formatOffset(ZoneOffset offset) {
        int seconds = offset.getTotalSeconds();
        if (seconds == 0) {
            return "UTC";
        }
        int minutes = Math.abs(seconds) / 60;
        String sign = seconds < 0 ? "-" : "+";
        return minutes % 60 == 0 ? "UTC" + sign + minutes / 60
                : String.format(Locale.ROOT, "UTC%s%d:%02d", sign, minutes / 60, minutes % 60);
    }

    /** A chooser listing "Camera clock", UTC and every time zone; selects {@code displayZone}. */
    public static JComboBox<String> createChooser(ZoneId cameraZone, ZoneId displayZone) {
        List<String> items = new ArrayList<>();
        items.add(CAMERA_CLOCK);
        items.add("UTC");
        TreeSet<String> zones = new TreeSet<>(ZoneId.getAvailableZoneIds());
        zones.removeIf(z -> z.equals("UTC") || !z.contains("/") || z.startsWith("Etc/") || z.startsWith("SystemV/"));
        items.addAll(zones);
        JComboBox<String> chooser = new JComboBox<>(items.toArray(new String[0]));
        chooser.setMaximumRowCount(20);
        chooser.setPrototypeDisplayValue("America/Argentina/Buenos_Aires");
        chooser.setSelectedItem(null == displayZone ? CAMERA_CLOCK : displayZone.getId());
        if (null != displayZone && chooser.getSelectedIndex() < 0) {
            chooser.addItem(displayZone.getId());
            chooser.setSelectedItem(displayZone.getId());
        }
        chooser.setToolTipText("<html>Show the times as the camera's clock recorded them ("
                + cameraZone.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + cameraZone.getId()
                + ")<br>or in another time zone, e.g. the local time of the trip.</html>");
        return chooser;
    }

    /** The zone selected in a chooser from {@link #createChooser}; {@code null} for the camera's clock. */
    public static ZoneId selected(JComboBox<String> chooser) {
        Object item = chooser.getSelectedItem();
        return null == item || CAMERA_CLOCK.equals(item) ? null : ZoneId.of((String) item);
    }
}
