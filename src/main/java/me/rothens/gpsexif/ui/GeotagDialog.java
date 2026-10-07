package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.gpx.GpxParser;
import me.rothens.gpsexif.gpx.PhotoTime;
import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackMatcher;
import me.rothens.gpsexif.gpx.TrackPoint;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.PositionUtil;
import me.rothens.gpsexif.util.Settings;
import org.jxmapviewer.viewer.GeoPosition;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Geotags photos from GPX tracks: load tracks, correct the camera clock, review the proposed positions (also
 * drawn on the main map) and apply them. Modeless, so the main map stays usable while it's open.
 */
public class GeotagDialog extends JDialog {

    /** What the dialog needs from the main window. */
    public interface Host {
        /** Draws the tracks and proposed photo positions on the main map; {@code highlight} may be null. */
        void showPreview(List<Track> tracks, List<GeoPosition> proposed, GeoPosition highlight);

        void clearPreview();

        /** Moves the main map so all positions are visible. */
        void zoomTo(List<GeoPosition> positions);

        /** The location right-clicked on the main map, or null. */
        GeoPosition pickedPosition();

        /** Writes the positions (and altitudes, when given) in one undoable batch. */
        void apply(Map<ImageFile, TrackMatcher.Match> matches, boolean writeAltitude);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Host host;
    private final Settings settings;
    private final List<Track> tracks = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private TrackMatcher matcher = new TrackMatcher(List.of());
    private Duration clockOffset = Duration.ZERO;

    private final JLabel lblTracks = new JLabel("No GPX file loaded");
    private final JComboBox<String> cbZone;
    private final JTextField tfOffset = new JTextField("+0:00:00", 8);
    private final JSpinner spMaxGap;
    private final JCheckBox chkAltitude = new JCheckBox("Write altitude from the track", true);
    private final RowModel tableModel = new RowModel();
    private final JTable table = new JTable(tableModel);
    private final JLabel lblSummary = new JLabel(" ");
    private final JButton btnApply = new JButton("Apply");

    private static final class Row {
        final ImageFile image;
        boolean use;
        boolean userChoseUse;
        TrackMatcher.Match match;

        Row(ImageFile image) {
            this.image = image;
        }
    }

    public GeotagDialog(Frame owner, List<ImageFile> photos, Settings settings, Host host) {
        super(owner, "Geotag from GPX", false);
        this.host = host;
        this.settings = settings;
        for (ImageFile photo : photos) {
            rows.add(new Row(photo));
        }

        TreeSet<String> zones = new TreeSet<>(ZoneId.getAvailableZoneIds());
        cbZone = new JComboBox<>(zones.toArray(new String[0]));
        cbZone.setSelectedItem(settings.getCameraZone().getId());
        cbZone.setToolTipText("The time zone the camera's clock was set to. Photos that record their own UTC "
                + "offset use that instead.");
        spMaxGap = new JSpinner(new SpinnerNumberModel(settings.getGpxMaxGapMinutes(), 1, 24 * 60, 1));
        spMaxGap.setToolTipText("Photos taken further than this from any track point stay unmatched");
        tfOffset.setToolTipText("How far the camera's clock was AHEAD of the real time (negative if behind), "
                + "e.g. +3:12, -1:00:00 or 2h 5m");

        JButton btnAdd = new JButton("Add GPX files...");
        btnAdd.addActionListener(e -> addFiles());
        JButton btnClear = new JButton("Remove tracks");
        btnClear.addActionListener(e -> {
            tracks.clear();
            trackChanged();
        });
        JButton btnFromClock = new JButton("From clock photo...");
        btnFromClock.setToolTipText("Select a photo showing a clock, then enter the time the clock shows");
        btnFromClock.addActionListener(e -> offsetFromClockPhoto());
        JButton btnFromMap = new JButton("From map...");
        btnFromMap.setToolTipText("Select a photo, right-click where it was taken on the main map, then press this");
        btnFromMap.addActionListener(e -> offsetFromMap());

        cbZone.addActionListener(e -> {
            settings.setCameraZone(ZoneId.of((String) cbZone.getSelectedItem()));
            updateTrackLabel();
            recompute();
        });
        spMaxGap.addChangeListener(e -> {
            settings.setGpxMaxGapMinutes((Integer) spMaxGap.getValue());
            recompute();
        });
        tfOffset.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                offsetEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                offsetEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                offsetEdited();
            }
        });

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(300);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreview();
            }
        });

        btnApply.addActionListener(e -> apply());
        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(e -> dispose());

        JPanel trackRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        trackRow.add(btnAdd);
        trackRow.add(btnClear);
        trackRow.add(lblTracks);

        JPanel clockRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        clockRow.add(new JLabel("Camera time zone:"));
        clockRow.add(cbZone);
        clockRow.add(new JLabel("  Camera clock ahead by:"));
        clockRow.add(tfOffset);

        JPanel helperRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        helperRow.add(new JLabel("Work out the clock offset:"));
        helperRow.add(btnFromClock);
        helperRow.add(btnFromMap);

        JPanel gapRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        gapRow.add(new JLabel("Max. time from track:"));
        gapRow.add(spMaxGap);
        gapRow.add(new JLabel("min"));
        gapRow.add(Box.createHorizontalStrut(12));
        gapRow.add(chkAltitude);

        JPanel top = new JPanel(new GridLayout(4, 1, 0, 6));
        top.add(trackRow);
        top.add(clockRow);
        top.add(helperRow);
        top.add(gapRow);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(lblSummary, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(btnApply);
        buttons.add(btnClose);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(900, 520);
        setLocationRelativeTo(owner);
        recompute();
    }

    @Override
    public void dispose() {
        host.clearPreview();
        super.dispose();
    }

    /** Loads GPX files and re-matches the photos. */
    public void addTracks(List<File> files) {
        List<String> errors = new ArrayList<>();
        for (File file : files) {
            try {
                Track track = GpxParser.parse(file.toPath());
                if (track.pointCount() == 0) {
                    errors.add(file.getName() + ": no track points");
                } else {
                    tracks.add(track);
                }
            } catch (IOException e) {
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors), getTitle(), JOptionPane.WARNING_MESSAGE);
        }
        trackChanged();
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("GPX tracks (*.gpx)", "gpx"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            addTracks(List.of(chooser.getSelectedFiles()));
        }
    }

    private void trackChanged() {
        matcher = new TrackMatcher(tracks);
        updateTrackLabel();
        if (!tracks.isEmpty()) {
            List<GeoPosition> all = new ArrayList<>();
            tracks.forEach(t -> t.segments().forEach(s -> s.forEach(p -> all.add(p.position()))));
            host.zoomTo(all);
        }
        recompute();
    }

    /** Track summary; times are shown in the camera's time zone, like the photos' times. */
    private void updateTrackLabel() {
        if (tracks.isEmpty()) {
            lblTracks.setText("No GPX file loaded");
        } else {
            int points = tracks.stream().mapToInt(Track::pointCount).sum();
            Instant start = tracks.stream().map(Track::start).filter(java.util.Objects::nonNull)
                    .min(Instant::compareTo).orElse(null);
            Instant end = tracks.stream().map(Track::end).filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
            String range = null == start ? "no times!"
                    : format(start) + " - " + format(end) + " (" + zone().getId() + ")";
            lblTracks.setText(tracks.size() + (tracks.size() == 1 ? " file, " : " files, ") + points
                    + " points, " + range);
        }
    }

    private String format(Instant instant) {
        return TIME.format(instant.atZone(zone()));
    }

    private ZoneId zone() {
        return ZoneId.of((String) cbZone.getSelectedItem());
    }

    private Duration maxGap() {
        return Duration.ofMinutes((Integer) spMaxGap.getValue());
    }

    private void offsetEdited() {
        try {
            clockOffset = PhotoTime.parseOffset(tfOffset.getText());
            tfOffset.putClientProperty("JComponent.outline", null);
            recompute();
        } catch (IllegalArgumentException e) {
            tfOffset.putClientProperty("JComponent.outline", "error");
        }
    }

    private void setOffset(Duration offset) {
        tfOffset.setText(PhotoTime.formatOffset(offset));
    }

    /** Re-matches every photo with the current tracks and settings. */
    private void recompute() {
        for (Row row : rows) {
            Instant time = realTime(row.image);
            row.match = null == time || !matcher.hasTimedPoints() ? null : matcher.match(time, maxGap());
            if (!row.userChoseUse) {
                // Don't overwrite existing locations unless the user ticks them
                row.use = null != row.match && row.match.isMatched() && !row.image.hasExifGPS();
            }
        }
        tableModel.fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
        updateSummary();
        updatePreview();
    }

    private Instant realTime(ImageFile image) {
        if (null == image.getTaken()) {
            return null;
        }
        return PhotoTime.toInstant(image.getTaken(), image.getTakenOffset(), zone(), clockOffset);
    }

    private List<Row> toApply() {
        return rows.stream().filter(r -> r.use && null != r.match && r.match.isMatched()).toList();
    }

    private void updateSummary() {
        long matched = rows.stream().filter(r -> null != r.match && r.match.isMatched()).count();
        long noDate = rows.stream().filter(r -> null == r.image.getTaken()).count();
        int selected = toApply().size();
        StringBuilder sb = new StringBuilder();
        if (!matcher.hasTimedPoints()) {
            sb.append(tracks.isEmpty() ? "Add a GPX file recorded while taking the photos."
                    : "The loaded tracks have no times, so photos can't be matched.");
        } else {
            sb.append(matched).append(" of ").append(rows.size()).append(" photos matched");
            if (noDate > 0) {
                sb.append(", ").append(noDate).append(" without date");
            }
        }
        lblSummary.setText(sb.toString());
        btnApply.setText(selected == 0 ? "Apply" : "Apply to " + selected + (selected == 1 ? " photo" : " photos"));
        btnApply.setEnabled(selected > 0);
    }

    private void updatePreview() {
        List<GeoPosition> proposed = toApply().stream().map(r -> r.match.position()).toList();
        GeoPosition highlight = null;
        int viewRow = table.getSelectedRow();
        if (viewRow >= 0) {
            Row row = rows.get(table.convertRowIndexToModel(viewRow));
            if (null != row.match && row.match.isMatched()) {
                highlight = row.match.position();
            }
        }
        host.showPreview(tracks, proposed, highlight);
    }

    private Row selectedRow() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : rows.get(table.convertRowIndexToModel(viewRow));
    }

    private void offsetFromClockPhoto() {
        Row row = selectedRow();
        if (null == row || null == row.image.getTaken()) {
            message("Select a photo with a date in the table first - ideally one showing a clock or a phone screen.");
            return;
        }
        String input = JOptionPane.showInputDialog(this, "What time does the clock in " + row.image
                + " show? (HH:mm:ss)\nThe camera recorded " + TIME.format(row.image.getTaken()) + ".",
                row.image.getTaken().toLocalTime().withNano(0).toString());
        if (null == input) {
            return;
        }
        try {
            setOffset(PhotoTime.offsetFromClockPhoto(row.image.getTaken(), LocalTime.parse(input.trim())));
        } catch (DateTimeParseException e) {
            message("\"" + input + "\" isn't a time. Use e.g. 14:05:30.");
        }
    }

    private void offsetFromMap() {
        Row row = selectedRow();
        GeoPosition picked = host.pickedPosition();
        if (null == row || null == row.image.getTaken() || null == picked || !matcher.hasTimedPoints()) {
            message("Load a GPX track, select a photo with a date in the table, and right-click on the main map "
                    + "where that photo was taken. Then press this button again.");
            return;
        }
        TrackPoint nearest = matcher.nearestPoint(picked);
        double metres = TrackMatcher.distanceMetres(picked, nearest.position());
        Instant cameraTime = PhotoTime.toInstant(row.image.getTaken(), row.image.getTakenOffset(), zone(),
                Duration.ZERO);
        Duration offset = Duration.between(nearest.time(), cameraTime);
        int answer = JOptionPane.showConfirmDialog(this, String.format(Locale.ROOT,
                "The nearest track point is %.0f m from the spot you picked, recorded at %s.%n"
                        + "That means the camera clock was %s by %s.%n%nUse this offset?",
                metres, format(nearest.time()), offset.isNegative() ? "behind" : "ahead",
                PhotoTime.describe(offset)), getTitle(), JOptionPane.OK_CANCEL_OPTION);
        if (answer == JOptionPane.OK_OPTION) {
            setOffset(offset);
        }
    }

    private void apply() {
        Map<ImageFile, TrackMatcher.Match> matches = new LinkedHashMap<>();
        for (Row row : toApply()) {
            matches.put(row.image, row.match);
        }
        if (!matches.isEmpty()) {
            host.apply(matches, chkAltitude.isSelected());
            dispose();
        }
    }

    private void message(String text) {
        JOptionPane.showMessageDialog(this, text, getTitle(), JOptionPane.INFORMATION_MESSAGE);
    }

    private final class RowModel extends AbstractTableModel {
        private final String[] columns = {"Use", "Photo", "Taken (camera clock)", "Result", "Altitude"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return column == 0 && null != row.match && row.match.isMatched();
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            row.use = Boolean.TRUE.equals(value);
            row.userChoseUse = true;
            fireTableCellUpdated(rowIndex, column);
            updateSummary();
            updatePreview();
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return switch (column) {
                case 0 -> row.use;
                case 1 -> row.image.getFile().getName();
                case 2 -> null == row.image.getTaken() ? "-" : TIME.format(row.image.getTaken())
                        + (null != row.image.getTakenOffset() ? " " + row.image.getTakenOffset() : "");
                case 3 -> result(row);
                default -> null != row.match && row.match.isMatched() && null != row.match.elevation()
                        ? String.format(Locale.ROOT, "%.0f m", row.match.elevation()) : "";
            };
        }

        private String result(Row row) {
            if (null == row.image.getTaken()) {
                return "No date in EXIF";
            }
            if (null == row.match) {
                return "";
            }
            if (!row.match.isMatched()) {
                return null == row.match.gap() ? "No match" : "No match - " + PhotoTime.describe(row.match.gap())
                        + " from the track";
            }
            String text = PositionUtil.getPositionString(row.match.position());
            if (row.image.hasExifGPS()) {
                text += " (replaces current location)";
            }
            return text;
        }
    }
}
