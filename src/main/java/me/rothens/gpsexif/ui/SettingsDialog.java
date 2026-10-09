package me.rothens.gpsexif.ui;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.map.TileDiskCache;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import java.awt.*;

/** Modal dialog for the persisted preferences. */
public class SettingsDialog extends JDialog {

    private final JComboBox<Theme> cbTheme = new JComboBox<>(Theme.values());
    private final JComboBox<MapLayer> cbMapLayer = new JComboBox<>(MapLayer.values());
    private final JCheckBox chkBackups = new JCheckBox(tr("Keep a backup of each photo before it's first changed"));
    private final JSpinner spCacheLimit = new JSpinner(new SpinnerNumberModel(Settings.DEFAULT_TILE_CACHE_MAX_MB,
            Settings.MIN_TILE_CACHE_MAX_MB, 100_000, 50));
    private final JLabel lblCacheSize = new JLabel();
    private final JSpinner spMaxMarkers = new JSpinner(new SpinnerNumberModel(Settings.DEFAULT_MAX_PHOTO_MARKERS,
            Settings.MIN_PHOTO_MARKERS, Settings.MAX_PHOTO_MARKERS_LIMIT, 10));
    private final JButton btnClearCache = new JButton(tr("Clear map cache"));
    private final JCheckBox chkPlaces = new JCheckBox(tr("Add the place name when saving a location"));
    private final JCheckBox chkUpdates = new JCheckBox(tr("Tell me when a new version is out (checked once a day)"));
    private final JComboBox<String> cbLanguage = new JComboBox<>();
    private final JCheckBox chkTour = new JCheckBox(tr("Show the guided tour again on the next start"));
    private final TileDiskCache tileCache;
    private boolean accepted;

    /**
     * @param exifToolStatus describes the ExifTool in use (shown in the dialog)
     * @param onExifTool     opens the ExifTool dialog; returns the new status
     */
    public SettingsDialog(Frame owner, Settings settings, TileDiskCache tileCache, String exifToolStatus,
                          java.util.function.Function<Window, String> onExifTool) {
        super(owner, tr("Settings"), true);
        this.tileCache = tileCache;
        JLabel lblExifTool = new JLabel(exifToolStatus);
        JButton btnExifTool = new JButton("ExifTool...");
        btnExifTool.addActionListener(e -> lblExifTool.setText(onExifTool.apply(this)));
        spCacheLimit.setValue(settings.getTileCacheMaxMb());
        spMaxMarkers.setValue(settings.getMaxPhotoMarkers());
        updateCacheSize();
        btnClearCache.addActionListener(e -> clearCache());
        cbTheme.setSelectedItem(settings.getTheme());
        cbMapLayer.setSelectedItem(settings.getMapLayer());
        chkBackups.setSelected(settings.isBackupsEnabled());
        chkPlaces.setSelected(settings.isPlaceNames());

        JLabel backupHint = new JLabel(tr("Backups are saved next to the photo as <name>.bak and are never overwritten."));
        backupHint.putClientProperty("FlatLaf.styleClass", "small");
        backupHint.setEnabled(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel(tr("Theme:")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(cbTheme, c);

        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(tr("Map layer:")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(cbMapLayer, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = new Insets(12, 4, 0, 4);
        form.add(chkBackups, c);
        c.gridy = 3;
        c.insets = new Insets(0, 28, 4, 4);
        form.add(backupHint, c);

        c.gridy = 4;
        c.insets = new Insets(8, 4, 0, 4);
        form.add(chkPlaces, c);
        c.gridy = 5;
        c.insets = new Insets(0, 28, 4, 4);
        JLabel placesHint = new JLabel(tr("<html>Writes the city, state and country (IPTC/XMP), looked up with OpenStreetMap Nominatim.<br>Edit → Look up place names does it for photos that already have a location.</html>"));
        placesHint.putClientProperty("FlatLaf.styleClass", "small");
        placesHint.setEnabled(false);
        form.add(placesHint, c);

        c.gridy = 6;
        c.gridwidth = 1;
        c.insets = new Insets(12, 4, 4, 4);
        form.add(new JLabel(tr("Map cache limit (MB):")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(spCacheLimit, c);
        c.gridx = 0;
        c.gridy = 7;
        c.insets = new Insets(4, 4, 4, 4);
        form.add(lblCacheSize, c);
        c.gridx = 1;
        form.add(btnClearCache, c);
        c.gridx = 0;
        c.gridy = 8;
        c.gridwidth = 2;
        c.insets = new Insets(0, 4, 4, 4);
        JLabel cacheHint = new JLabel(tr("Downloaded map tiles are kept for 30 days; the oldest are removed above the limit."));
        cacheHint.putClientProperty("FlatLaf.styleClass", "small");
        cacheHint.setEnabled(false);
        form.add(cacheHint, c);

        c.gridy = 9;
        c.gridwidth = 1;
        c.insets = new Insets(12, 4, 4, 4);
        form.add(new JLabel(tr("Max. photo markers:")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(spMaxMarkers, c);
        c.gridx = 0;
        c.gridy = 10;
        c.gridwidth = 2;
        c.insets = new Insets(0, 4, 4, 4);
        JLabel markerHint = new JLabel(tr("View > Show photos on map: nearby photos are grouped; more markers than this ask you to zoom in."));
        markerHint.putClientProperty("FlatLaf.styleClass", "small");
        markerHint.setEnabled(false);
        form.add(markerHint, c);

        c.gridx = 0;
        c.gridy = 11;
        c.gridwidth = 1;
        c.insets = new Insets(12, 4, 4, 4);
        form.add(btnExifTool, c);
        c.gridx = 1;
        form.add(lblExifTool, c);

        String ffmpeg = me.rothens.gpsexif.video.Ffmpeg.locate(settings.getFfmpegPath());
        JLabel lblFfmpeg = new JLabel(ffmpegStatus(ffmpeg));
        JButton btnFfmpeg = new JButton("FFmpeg...");
        btnFfmpeg.addActionListener(e -> {
            String path = new FfmpegDialog(this, settings.getFfmpegPath(),
                    me.rothens.gpsexif.video.Ffmpeg.locate(settings.getFfmpegPath())).showDialog();
            if (null != path) {
                settings.setFfmpegPath(path);
                lblFfmpeg.setText(ffmpegStatus(me.rothens.gpsexif.video.Ffmpeg.locate(path)));
            }
        });
        c.gridx = 0;
        c.gridy = 12;
        c.insets = new Insets(4, 4, 4, 4);
        form.add(btnFfmpeg, c);
        c.gridx = 1;
        form.add(lblFfmpeg, c);

        chkTour.setSelected(!settings.isTutorialShown());
        c.gridx = 0;
        c.gridy = 13;
        c.gridwidth = 2;
        c.insets = new Insets(12, 4, 0, 4);
        form.add(chkTour, c);
        JLabel tourHint = new JLabel(tr("To take the tour right now: Help → Show tutorial."));
        tourHint.putClientProperty("FlatLaf.styleClass", "small");
        tourHint.setEnabled(false);
        c.gridy = 14;
        c.insets = new Insets(0, 28, 4, 4);
        form.add(tourHint, c);

        chkUpdates.setSelected(settings.isUpdateCheck());
        c.gridy = 15;
        c.insets = new Insets(8, 4, 0, 4);
        form.add(chkUpdates, c);
        JLabel updatesHint = new JLabel(tr("Asks GitHub for the latest release; nothing is installed by itself."));
        updatesHint.putClientProperty("FlatLaf.styleClass", "small");
        updatesHint.setEnabled(false);
        c.gridy = 16;
        c.insets = new Insets(0, 28, 4, 4);
        form.add(updatesHint, c);

        cbLanguage.addItem(tr("Same as the system"));
        cbLanguage.addItem("English");
        java.util.List<String> codes = new java.util.ArrayList<>(java.util.List.of("", "en"));
        me.rothens.gpsexif.i18n.I18n.LANGUAGES.forEach((code, name) -> {
            cbLanguage.addItem(name);
            codes.add(code);
        });
        cbLanguage.setSelectedIndex(Math.max(0, codes.indexOf(settings.getLanguage())));
        c.gridx = 0;
        c.gridy = 17;
        c.gridwidth = 1;
        c.insets = new Insets(10, 4, 4, 4);
        form.add(new JLabel(tr("Language:")), c);
        c.gridx = 1;
        JPanel languageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        languageRow.add(cbLanguage);
        JLabel languageHint = new JLabel("  " + tr("after a restart"));
        languageHint.putClientProperty("FlatLaf.styleClass", "small");
        languageHint.setEnabled(false);
        languageRow.add(languageHint);
        form.add(languageRow, c);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton(tr("Cancel"));
        ok.addActionListener(e -> {
            settings.setTheme((Theme) cbTheme.getSelectedItem());
            settings.setMapLayer((MapLayer) cbMapLayer.getSelectedItem());
            settings.setBackupsEnabled(chkBackups.isSelected());
            settings.setPlaceNames(chkPlaces.isSelected());
            settings.setTileCacheMaxMb((Integer) spCacheLimit.getValue());
            settings.setMaxPhotoMarkers((Integer) spMaxMarkers.getValue());
            settings.setTutorialShown(!chkTour.isSelected());
            settings.setUpdateCheck(chkUpdates.isSelected());
            settings.setLanguage(codes.get(Math.max(0, cbLanguage.getSelectedIndex())));
            tileCache.scheduleMaintenance();
            accepted = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static String ffmpegStatus(String executable) {
        return null == executable ? tr("Not found - videos are exported with the slower built-in encoder")
                : "FFmpeg " + me.rothens.gpsexif.video.Ffmpeg.version(executable) + " (" + executable + ")";
    }

    private void updateCacheSize() {
        long bytes = tileCache.getSize();
        lblCacheSize.setText(bytes < 0 ? tr("Map cache: measuring...") : tr("Map cache: {0}", formatSize(bytes)));
    }

    private void clearCache() {
        try {
            tileCache.clear();
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, tr("Couldn't clear the map cache:") + "\n" + e.getMessage(), getTitle(),
                    JOptionPane.WARNING_MESSAGE);
        }
        updateCacheSize();
    }

    static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Shows the dialog and returns whether the user pressed OK (the settings are then already saved). */
    public boolean showDialog() {
        setVisible(true);
        return accepted;
    }
}
