package me.rothens.gpsexif.gpx;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the camera's clock time to real (UTC) time, and handles the camera clock offset: how far the camera's
 * clock was <em>ahead</em> of the real time (negative when it was behind).
 */
public final class PhotoTime {

    private static final Pattern CLOCK = Pattern.compile("^([+-])?(?:(\\d+):)?(\\d+):(\\d{1,2})$");
    private static final Pattern UNITS = Pattern.compile("^([+-])?\\s*(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m(?:in)?)?\\s*(?:(\\d+)\\s*s)?$");

    private PhotoTime() {
    }

    /**
     * The real time a photo was taken.
     *
     * @param taken        camera clock time from EXIF
     * @param takenOffset  UTC offset the camera recorded (EXIF 2.31), preferred over {@code cameraZone} when present
     * @param cameraZone   time zone the camera clock was set to
     * @param clockOffset  how far the camera clock was ahead of the real time
     */
    public static Instant toInstant(LocalDateTime taken, ZoneOffset takenOffset, ZoneId cameraZone,
                                    Duration clockOffset) {
        Instant camera = null != takenOffset ? taken.toInstant(takenOffset) : taken.atZone(cameraZone).toInstant();
        return camera.minus(clockOffset);
    }

    /**
     * Clock offset from a photo of a clock: the camera recorded {@code taken} while the clock showed
     * {@code clockShowed}. Assumes they're less than 12 hours apart, so photos around midnight work.
     */
    public static Duration offsetFromClockPhoto(LocalDateTime taken, LocalTime clockShowed) {
        long diff = Duration.between(clockShowed, taken.toLocalTime()).getSeconds();
        long day = Duration.ofDays(1).getSeconds();
        diff = Math.floorMod(diff + day / 2, day) - day / 2;
        return Duration.ofSeconds(diff).plusNanos(taken.getNano());
    }

    /** Parses an offset like {@code +3:12} (m:ss), {@code -1:00:00} (h:mm:ss), {@code 2h 5m} or {@code -90s}. */
    public static Duration parseOffset(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("0")) {
            return Duration.ZERO;
        }
        Matcher m = CLOCK.matcher(t);
        if (m.matches()) {
            long h = null != m.group(2) ? Long.parseLong(m.group(2)) : 0;
            long minutes = Long.parseLong(m.group(3));
            long seconds = Long.parseLong(m.group(4));
            if (seconds >= 60 || (null != m.group(2) && minutes >= 60)) {
                throw new IllegalArgumentException("Invalid time offset: " + text);
            }
            return signed(m.group(1), Duration.ofHours(h).plusMinutes(minutes).plusSeconds(seconds));
        }
        m = UNITS.matcher(t);
        if (m.matches() && (null != m.group(2) || null != m.group(3) || null != m.group(4))) {
            Duration d = Duration.ZERO;
            if (null != m.group(2)) {
                d = d.plusHours(Long.parseLong(m.group(2)));
            }
            if (null != m.group(3)) {
                d = d.plusMinutes(Long.parseLong(m.group(3)));
            }
            if (null != m.group(4)) {
                d = d.plusSeconds(Long.parseLong(m.group(4)));
            }
            return signed(m.group(1), d);
        }
        throw new IllegalArgumentException("Invalid time offset: " + text + " (use e.g. +3:12, -1:00:00 or 2h 5m)");
    }

    /** Formats as {@code +h:mm:ss} / {@code -h:mm:ss}, parseable by {@link #parseOffset}. */
    public static String formatOffset(Duration offset) {
        long s = offset.getSeconds() + (offset.getNano() >= 500_000_000 ? 1 : 0);
        String sign = s < 0 ? "-" : "+";
        s = Math.abs(s);
        return String.format(Locale.ROOT, "%s%d:%02d:%02d", sign, s / 3600, (s / 60) % 60, s % 60);
    }

    /** Human-readable duration for gaps, e.g. "45 s", "12 min", "3 h 5 min", "2 days". */
    public static String describe(Duration d) {
        long s = d.abs().getSeconds();
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min";
        }
        if (s < 48 * 3600) {
            long minutes = (s / 60) % 60;
            return (s / 3600) + " h" + (minutes > 0 ? " " + minutes + " min" : "");
        }
        return (s / 86400) + " days";
    }

    private static Duration signed(String sign, Duration d) {
        return "-".equals(sign) ? d.negated() : d;
    }
}
