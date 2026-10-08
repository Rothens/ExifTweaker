package me.rothens.gpsexif.rename;

import me.rothens.gpsexif.metadata.Place;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file name pattern such as {@code {date:yyyy-MM-dd} {place} {n:000}}, giving {@code 2026-07-11 Tihany 001}.
 *
 * <ul>
 *     <li>{@code {date}} the date taken as {@code yyyy-MM-dd}; {@code {date:FORMAT}} in any format, e.g.
 *         {@code {date:yyyy-MM-dd HH.mm.ss}}</li>
 *     <li>{@code {place}} the city (or the most specific part of the place name), {@code {country}}, {@code {state}}
 *         and {@code {sublocation}}</li>
 *     <li>{@code {name}} the current name without the extension, {@code {camera}} the camera model</li>
 *     <li>{@code {n}} a running number; {@code {n:000}} padded to that many digits</li>
 * </ul>
 * Parts that a photo doesn't have (no place yet) are left out together with the spaces and separators around them.
 */
public final class RenamePattern {

    /** What a pattern can use from one photo. */
    public record Values(LocalDateTime taken, Place place, String name, String camera) {
    }

    private static final Pattern TOKEN = Pattern.compile("\\{([a-z]+)(?::([^}]*))?}");
    private static final List<String> NAMES = List.of("date", "place", "city", "country", "state", "sublocation",
            "name", "camera", "n");
    private static final String SEPARATORS = " -_.,";

    private final String pattern;

    /** @throws IllegalArgumentException with a readable message if the pattern can't be used */
    public RenamePattern(String pattern) {
        this.pattern = pattern;
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Enter a pattern, e.g. {date} {place} {n:000}");
        }
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (m.find()) {
            rest.append(pattern, last, m.start());
            last = m.end();
            if (!NAMES.contains(m.group(1))) {
                throw new IllegalArgumentException("Unknown field {" + m.group(1) + "}. Use one of: {"
                        + String.join("}, {", NAMES) + "}");
            }
            if ("date".equals(m.group(1)) && null != m.group(2)) {
                try {
                    DateTimeFormatter.ofPattern(m.group(2), Locale.ROOT).format(LocalDateTime.of(2026, 1, 1, 0, 0));
                } catch (IllegalArgumentException | java.time.DateTimeException e) {
                    throw new IllegalArgumentException("{date:" + m.group(2) + "} isn't a date format. Use e.g. "
                            + "{date:yyyy-MM-dd HH.mm}");
                }
            }
            if ("n".equals(m.group(1)) && null != m.group(2) && !m.group(2).matches("0{1,9}")) {
                throw new IllegalArgumentException("Write the number as {n} or with its digits, e.g. {n:000}");
            }
        }
        rest.append(pattern.substring(last));
        if (rest.indexOf("{") >= 0 || rest.indexOf("}") >= 0) {
            throw new IllegalArgumentException("A { or } doesn't belong to a field. Fields look like {date} or {n:000}");
        }
    }

    public String pattern() {
        return pattern;
    }

    /** Whether the pattern has a running number (then names can't collide among the renamed photos). */
    public boolean hasNumber() {
        Matcher m = TOKEN.matcher(pattern);
        while (m.find()) {
            if ("n".equals(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** The new name, without the extension; may be empty if nothing was known. */
    public String apply(Values values, int number) {
        Matcher m = TOKEN.matcher(pattern);
        // Literal text and values in turn; an empty value swallows the separators before it (or after, at the start)
        List<String> parts = new ArrayList<>();
        List<Boolean> isValue = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            parts.add(pattern.substring(last, m.start()));
            isValue.add(false);
            parts.add(value(m.group(1), m.group(2), values, number));
            isValue.add(true);
            last = m.end();
        }
        parts.add(pattern.substring(last));
        isValue.add(false);
        for (int i = 0; i < parts.size(); i++) {
            if (isValue.get(i) && parts.get(i).isEmpty()) {
                if (i > 0 && !trimSeparators(parts.get(i - 1), true).equals(parts.get(i - 1))
                        && hasContentBefore(parts, i - 1)) {
                    parts.set(i - 1, trimSeparators(parts.get(i - 1), true));
                } else if (i + 1 < parts.size()) {
                    parts.set(i + 1, trimSeparators(parts.get(i + 1), false));
                }
            }
        }
        return sanitize(String.join("", parts));
    }

    private static boolean hasContentBefore(List<String> parts, int index) {
        for (int i = 0; i < index; i++) {
            if (!parts.get(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Drops separators at the end ({@code atEnd}) or start of {@code s}. */
    private static String trimSeparators(String s, boolean atEnd) {
        int from = 0;
        int to = s.length();
        if (atEnd) {
            while (to > 0 && SEPARATORS.indexOf(s.charAt(to - 1)) >= 0) {
                to--;
            }
        } else {
            while (from < to && SEPARATORS.indexOf(s.charAt(from)) >= 0) {
                from++;
            }
        }
        return s.substring(from, to);
    }

    private static String value(String field, String format, Values v, int number) {
        Place place = v.place();
        String text = switch (field) {
            case "date" -> null == v.taken() ? null
                    : DateTimeFormatter.ofPattern(null == format ? "yyyy-MM-dd" : format, Locale.ROOT).format(v.taken());
            case "place" -> null == place ? null
                    : null != place.city() ? place.city() : null != place.sublocation() ? place.sublocation()
                    : null != place.state() ? place.state() : place.country();
            case "city" -> null == place ? null : place.city();
            case "country" -> null == place ? null : place.country();
            case "state" -> null == place ? null : place.state();
            case "sublocation" -> null == place ? null : place.sublocation();
            case "name" -> v.name();
            case "camera" -> v.camera();
            case "n" -> null == format ? Integer.toString(number)
                    : String.format(Locale.ROOT, "%0" + format.length() + "d", number);
            default -> null;
        };
        return null == text ? "" : clean(text);
    }

    /** Characters that aren't allowed in file names (on Windows, the strictest) become spaces. */
    private static String clean(String text) {
        return text.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
    }

    /** No illegal characters, no double spaces, no leading or trailing dots and spaces, no reserved names. */
    static String sanitize(String name) {
        String s = clean(name);
        s = s.replaceAll("^[ .]+|[ .]+$", "");
        if (s.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)")) {
            s = s + "_";
        }
        return s.length() > 200 ? s.substring(0, 200).strip() : s;
    }
}
