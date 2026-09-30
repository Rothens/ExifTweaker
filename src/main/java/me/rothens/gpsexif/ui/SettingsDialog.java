package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.map.TileDiskCache;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import java.awt.*;

/** Modal dialog for the persisted preferences. */
public class SettingsDialog extends JDialog {

    private final JComboBox<Theme> cbTheme = new JComboBox<>(Theme.values());
    private final JComboBox<MapLayer> cbMapLayer = new JComboBox<>(MapLayer.values());
    private final JCheckBox chkBackups = new JCheckBox("Keep a backup of each photo before it's first changed");
    private final JSpinner spCacheLimit = new JSpinner(new SpinnerNumberModel(Settings.DEFAULT_TILE_CACHE_MAX_MB,
            Settings.MIN_TILE_CACHE_MAX_MB, 100_000, 50));
    private final JLabel lblCacheSize = new JLabel();
    private final JButton btnClearCache = new JButton("Clear map cache");
    private final TileDiskCache tileCache;
    private boolean accepted;

    public SettingsDialog(Frame owner, Settings settings, TileDiskCache tileCache) {
        super(owner, "Settings", true);
        this.tileCache = tileCache;
        spCacheLimit.setValue(settings.getTileCacheMaxMb());
        updateCacheSize();
        btnClearCache.addActionListener(e -> clearCache());
        cbTheme.setSelectedItem(settings.getTheme());
        cbMapLayer.setSelectedItem(settings.getMapLayer());
        chkBackups.setSelected(settings.isBackupsEnabled());

        JLabel backupHint = new JLabel("Backups are saved next to the photo as <name>.bak and are never overwritten.");
        backupHint.putClientProperty("FlatLaf.styleClass", "small");
        backupHint.setEnabled(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Theme:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(cbTheme, c);

        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Map layer:"), c);
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
        c.gridwidth = 1;
        c.insets = new Insets(12, 4, 4, 4);
        form.add(new JLabel("Map cache limit (MB):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(spCacheLimit, c);
        c.gridx = 0;
        c.gridy = 5;
        c.insets = new Insets(4, 4, 4, 4);
        form.add(lblCacheSize, c);
        c.gridx = 1;
        form.add(btnClearCache, c);
        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        c.insets = new Insets(0, 4, 4, 4);
        JLabel cacheHint = new JLabel("Downloaded map tiles are kept for 30 days; the oldest are removed above the limit.");
        cacheHint.putClientProperty("FlatLaf.styleClass", "small");
        cacheHint.setEnabled(false);
        form.add(cacheHint, c);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            settings.setTheme((Theme) cbTheme.getSelectedItem());
            settings.setMapLayer((MapLayer) cbMapLayer.getSelectedItem());
            settings.setBackupsEnabled(chkBackups.isSelected());
            settings.setTileCacheMaxMb((Integer) spCacheLimit.getValue());
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

    private void updateCacheSize() {
        long bytes = tileCache.getSize();
        lblCacheSize.setText(bytes < 0 ? "Map cache: measuring..." : "Map cache: " + formatSize(bytes));
    }

    private void clearCache() {
        try {
            tileCache.clear();
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Couldn't clear the map cache:\n" + e.getMessage(), getTitle(),
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
