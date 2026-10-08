package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.Place;
import me.rothens.gpsexif.metadata.TextTag;

import javax.swing.table.AbstractTableModel;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Metadata of the selected photos: editable fields first, then read-only technical information of the lead
 * photo. With several photos selected, a field whose values differ shows {@link #MULTIPLE}; it's only written
 * if the user actually changes it.
 */
public class MetadataTableModel extends AbstractTableModel {

    public static final String MULTIPLE = "(multiple values)";
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** Receives validated edits; the table itself never writes files. */
    public interface EditHandler {
        void edit(String fieldLabel, String newValue, List<ImageFile> photos, MetadataChanges changes);

        /** The input couldn't be used; {@code message} says why. */
        default void invalid(String message) {
        }
    }

    /** One editable field: how to show it, and how to turn user input into changes. */
    enum Editable {
        TAKEN("Date taken", p -> null == p.getTaken() ? null : DATE_TIME.format(p.getTaken().withNano(0))),
        MAKE(TextTag.MAKE),
        MODEL(TextTag.MODEL),
        ARTIST(TextTag.ARTIST),
        COPYRIGHT(TextTag.COPYRIGHT),
        DESCRIPTION(TextTag.DESCRIPTION),
        ALTITUDE("Altitude (m)", p -> null == p.getAltitude() ? null : number(p.getAltitude())),
        DIRECTION("Direction (°)", p -> null == p.getDirection() ? null : number(p.getDirection())),
        LANDMARK(Place.Part.SUBLOCATION),
        CITY(Place.Part.CITY),
        DISTRICT(Place.Part.DISTRICT),
        STATE(Place.Part.STATE),
        COUNTRY(Place.Part.COUNTRY),
        COUNTRY_CODE(Place.Part.COUNTRY_CODE);

        final String label;
        final Function<ImageFile, String> value;
        final TextTag textField;
        final Place.Part placePart;

        Editable(String label, Function<ImageFile, String> value) {
            this.label = label;
            this.value = value;
            this.textField = null;
            this.placePart = null;
        }

        Editable(TextTag field) {
            this.label = field.label();
            this.value = p -> p.getText(field);
            this.textField = field;
            this.placePart = null;
        }

        Editable(Place.Part part) {
            this.label = part.label();
            this.value = p -> null == p.getPlace() ? null : p.getPlace().get(part);
            this.textField = null;
            this.placePart = part;
        }
    }

    private final List<Editable> editable = List.of(Editable.values());
    private List<ImageFile> photos = List.of();
    private List<ExifData> info = List.of();
    private EditHandler handler = (label, value, p, c) -> { };

    public void setEditHandler(EditHandler handler) {
        this.handler = handler;
    }

    /** Shows the given photos; {@code lead} provides the read-only information rows. */
    public void setPhotos(List<ImageFile> photos, ImageFile lead) {
        this.photos = List.copyOf(photos);
        this.info = null == lead ? List.of() : lead.getExifData();
        fireTableDataChanged();
    }

    public void clear() {
        setPhotos(List.of(), null);
    }

    public boolean isEditableRow(int row) {
        return !photos.isEmpty() && row < editable.size();
    }

    /** Whether the value shown in this row differs between the selected photos. */
    public boolean isMultiple(int row) {
        return isEditableRow(row) && MULTIPLE.equals(getValueAt(row, 1));
    }

    @Override
    public int getRowCount() {
        return photos.isEmpty() ? 0 : editable.size() + info.size();
    }

    @Override
    public int getColumnCount() {
        return 2;
    }

    @Override
    public String getColumnName(int column) {
        return column == 0 ? "Field" : "Value";
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == 1 && isEditableRow(row);
    }

    @Override
    public Object getValueAt(int row, int column) {
        if (row < editable.size()) {
            Editable field = editable.get(row);
            if (column == 0) {
                return field.label;
            }
            String first = field.value.apply(photos.get(0));
            for (ImageFile photo : photos) {
                if (!Objects.equals(first, field.value.apply(photo))) {
                    return MULTIPLE;
                }
            }
            return null == first ? "" : first;
        }
        ExifData data = info.get(row - editable.size());
        return column == 0 ? data.getKey() : data.getValue();
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (!isCellEditable(row, column)) {
            return;
        }
        String text = null == value ? "" : value.toString().strip();
        Object current = getValueAt(row, column);
        if (text.equals(current) || (MULTIPLE.equals(current) && MULTIPLE.equals(text))) {
            return; // not changed
        }
        Editable field = editable.get(row);
        MetadataChanges changes;
        try {
            changes = parse(field, text);
        } catch (IllegalArgumentException e) {
            handler.invalid(e.getMessage());
            return;
        }
        handler.edit(field.label, text, photos, changes);
    }

    /**
     * Turns user input for a field into changes.
     *
     * @throws IllegalArgumentException with a user-readable message if the input isn't valid
     */
    static MetadataChanges parse(Editable field, String text) {
        MetadataChanges changes = new MetadataChanges();
        if (null != field.textField) {
            if (text.length() > 2000) {
                throw new IllegalArgumentException(field.label + " is too long (at most 2000 characters)");
            }
            return changes.text(field.textField, text);
        }
        if (null != field.placePart) {
            if (text.length() > 200) {
                throw new IllegalArgumentException(field.label + " is too long (at most 200 characters)");
            }
            if (field.placePart == Place.Part.COUNTRY_CODE) {
                if (!text.isEmpty() && !text.matches("[A-Za-z]{2,3}")) {
                    throw new IllegalArgumentException("The country code has 2 letters, e.g. HU or JP");
                }
                text = text.toUpperCase(Locale.ROOT);
            }
            return changes.placePart(field.placePart, text);
        }
        switch (field) {
            case TAKEN -> {
                if (text.isEmpty()) {
                    throw new IllegalArgumentException("The date taken can't be removed, only changed");
                }
                changes.taken(parseDateTime(text));
            }
            case ALTITUDE -> changes.altitude(text.isEmpty() ? null : parseNumber(text, "Altitude", -1000, 100_000));
            case DIRECTION -> changes.direction(text.isEmpty() ? null : parseNumber(text, "Direction", -360, 360));
            default -> throw new IllegalStateException(field.name());
        }
        return changes;
    }

    /** Parses an altitude in metres; blank gives {@code null}. */
    public static Double parseAltitude(String text) {
        return text.isBlank() ? null : parseNumber(text.strip(), "Altitude", -1000, 100_000);
    }

    /** Parses a direction in degrees (normalized to 0..360); blank gives {@code null}. */
    public static Double parseDirection(String text) {
        return text.isBlank() ? null
                : MetadataChanges.normalizeDegrees(parseNumber(text.strip(), "Direction", -360, 360));
    }

    /** "2026-09-30 14:05:00", "2026-09-30 14:05", or EXIF's own "2026:09:30 14:05:00". */
    public static LocalDateTime parseDateTime(String text) {
        String t = text.strip().replace('T', ' ');
        if (t.matches("\\d{4}:\\d{2}:\\d{2} .*")) {
            t = t.substring(0, 4) + "-" + t.substring(5, 7) + "-" + t.substring(8);
        }
        if (t.matches("\\d{4}-\\d{2}-\\d{2} \\d{1,2}:\\d{2}")) {
            t += ":00";
        }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss", Locale.ROOT));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a date and time. Use e.g. 2026-09-30 14:05:00");
        }
    }

    private static double parseNumber(String text, String name, double min, double max) {
        double value;
        try {
            value = Double.parseDouble(text.replace(',', '.').replaceAll("\\s*(m|°|deg)$", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number: " + text);
        }
        if (value < min || value > max || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be between " + number(min) + " and " + number(max));
        }
        return value;
    }

    /** At most one decimal, no trailing ".0". */
    public static String number(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Labels of the editable rows, in order. */
    public static List<String> editableLabels() {
        List<String> labels = new ArrayList<>();
        for (Editable e : Editable.values()) {
            labels.add(e.label);
        }
        return labels;
    }
}
