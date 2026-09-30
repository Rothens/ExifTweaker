package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import java.awt.*;

/** Modal dialog for the persisted preferences. */
public class SettingsDialog extends JDialog {

    private final JComboBox<Theme> cbTheme = new JComboBox<>(Theme.values());
    private final JComboBox<MapLayer> cbMapLayer = new JComboBox<>(MapLayer.values());
    private final JCheckBox chkBackups = new JCheckBox("Keep a backup of each photo before it's first changed");
    private boolean accepted;

    public SettingsDialog(Frame owner, Settings settings) {
        super(owner, "Settings", true);
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

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            settings.setTheme((Theme) cbTheme.getSelectedItem());
            settings.setMapLayer((MapLayer) cbMapLayer.getSelectedItem());
            settings.setBackupsEnabled(chkBackups.isSelected());
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

    /** Shows the dialog and returns whether the user pressed OK (the settings are then already saved). */
    public boolean showDialog() {
        setVisible(true);
        return accepted;
    }
}
