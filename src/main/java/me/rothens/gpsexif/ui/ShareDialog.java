package me.rothens.gpsexif.ui;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.share.ShareExport;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Asks where to save copies for sharing, and what they keep: metadata, size, quality. */
public class ShareDialog extends JDialog {

    private static final int[] SIZES = {0, 3840, 2048, 1600, 1024};
    private static final String[] SIZE_LABELS = {tr("Original size"), "3840 px (4K)", "2048 px", "1600 px", "1024 px"};

    private final List<ImageFile> photos;
    private final Settings settings;
    private final JTextField tfFolder = new JTextField(30);
    private final JRadioButton rbAll = new JRadioButton(tr("All metadata"));
    private final JRadioButton rbNoLocation = new JRadioButton(tr("Without location"));
    private final JRadioButton rbNone = new JRadioButton(tr("Without any metadata"));
    private final JComboBox<String> cbSize = new JComboBox<>(SIZE_LABELS);
    private final JSlider slQuality = new JSlider(50, 100, 85);
    private final JLabel lblQuality = new JLabel();
    private final JLabel lblError = new JLabel(" ");
    private boolean accepted;

    public ShareDialog(Frame owner, List<ImageFile> photos, Settings settings) {
        super(owner, photos.size() == 1 ? tr("Export 1 copy for sharing") : tr("Export {0} copies for sharing", photos.size()), true);
        this.photos = photos;
        this.settings = settings;

        File photoFolder = photos.get(0).getFile().getAbsoluteFile().getParentFile();
        String last = settings.getShareDirectory();
        tfFolder.setText(!last.isEmpty() && new File(last).isDirectory() && !new File(last).equals(photoFolder)
                ? last : new File(photoFolder, "Shared").getPath());
        JButton browse = new JButton("...");
        browse.addActionListener(e -> browse());

        ButtonGroup group = new ButtonGroup();
        group.add(rbAll);
        group.add(rbNoLocation);
        group.add(rbNone);
        switch (settings.getSharePrivacy()) {
            case "ALL" -> rbAll.setSelected(true);
            case "NONE" -> rbNone.setSelected(true);
            default -> rbNoLocation.setSelected(true);
        }
        int size = settings.getShareSize();
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i] == size) {
                cbSize.setSelectedIndex(i);
            }
        }
        slQuality.setValue(settings.getShareQuality());
        slQuality.addChangeListener(e -> updateQuality());
        cbSize.addActionListener(e -> updateQuality());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel(tr("Save to:")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(tfFolder, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(browse, c);

        c.gridx = 0;
        c.gridy = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(10, 4, 4, 4);
        form.add(new JLabel(tr("Keep:")), c);
        c.gridx = 1;
        c.gridwidth = 2;
        JPanel privacy = new JPanel(new GridLayout(0, 1, 0, 2));
        privacy.add(option(rbNoLocation, tr("No GPS position and place name; the date, camera and the rest stay.")));
        privacy.add(option(rbNone, tr("Only the picture: no date, camera, location or anything else. It still shows the right way up.")));
        privacy.add(option(rbAll, tr("An exact copy, location included.")));
        form.add(privacy, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(tr("Size:")), c);
        c.gridx = 1;
        form.add(cbSize, c);
        c.gridx = 0;
        c.gridy = 3;
        c.insets = new Insets(4, 4, 4, 4);
        form.add(new JLabel(tr("JPEG quality:")), c);
        c.gridx = 1;
        JPanel quality = new JPanel(new BorderLayout(8, 0));
        quality.add(slQuality, BorderLayout.CENTER);
        quality.add(lblQuality, BorderLayout.EAST);
        form.add(quality, c);
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 3;
        JLabel note = new JLabel(tr("<html>The originals aren't changed. Smaller copies are saved as JPEG, turned upright; so are RAW files.</html>"));
        note.putClientProperty("FlatLaf.styleClass", "small");
        note.setEnabled(false);
        form.add(note, c);
        c.gridy = 5;
        lblError.setForeground(UIManager.getColor("Component.error.focusedBorderColor"));
        form.add(lblError, c);

        JButton ok = new JButton(tr("Export"));
        ok.addActionListener(e -> accept());
        JButton cancel = new JButton(tr("Cancel"));
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        updateQuality();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private static JComponent option(JRadioButton button, String description) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(button, BorderLayout.NORTH);
        JLabel text = new JLabel(description);
        text.putClientProperty("FlatLaf.styleClass", "small");
        text.setEnabled(false);
        text.setBorder(BorderFactory.createEmptyBorder(0, 24, 4, 0));
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    private void updateQuality() {
        lblQuality.setText(slQuality.getValue() + " %");
        boolean anyEncoded = cbSize.getSelectedIndex() > 0
                || photos.stream().anyMatch(p -> me.rothens.gpsexif.metadata.ExifToolBackend.isRaw(p.getPath()));
        slQuality.setEnabled(anyEncoded);
        slQuality.setToolTipText(anyEncoded ? null : tr("Only used for smaller copies and RAW files; the others keep the original picture"));
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser(tfFolder.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(tr("Save the copies to"));
        if (chooser.showDialog(this, tr("Choose")) == JFileChooser.APPROVE_OPTION) {
            tfFolder.setText(chooser.getSelectedFile().getPath());
        }
    }

    private void accept() {
        String text = tfFolder.getText().strip();
        if (text.isEmpty()) {
            lblError.setText(tr("Choose a folder for the copies."));
            return;
        }
        File folder = new File(text).getAbsoluteFile();
        if (photos.stream().anyMatch(p -> p.getFile().getAbsoluteFile().getParentFile().equals(folder))) {
            lblError.setText(tr("Choose another folder than the photos' own, so copies and originals don't mix."));
            return;
        }
        if (folder.exists() && !folder.isDirectory()) {
            lblError.setText(tr("{0} is a file, not a folder.", folder.getName()));
            return;
        }
        settings.setShareDirectory(folder.getPath());
        settings.setSharePrivacy(getOptions().privacy().name());
        settings.setShareSize(SIZES[cbSize.getSelectedIndex()]);
        settings.setShareQuality(slQuality.getValue());
        accepted = true;
        dispose();
    }

    /** Shows the dialog; returns whether to export. */
    public boolean showDialog() {
        setVisible(true);
        return accepted;
    }

    public Path getFolder() {
        return new File(tfFolder.getText().strip()).getAbsoluteFile().toPath();
    }

    public ShareExport.Options getOptions() {
        ShareExport.Privacy privacy = rbAll.isSelected() ? ShareExport.Privacy.ALL
                : rbNone.isSelected() ? ShareExport.Privacy.NONE : ShareExport.Privacy.NO_LOCATION;
        return new ShareExport.Options(privacy, SIZES[cbSize.getSelectedIndex()], slQuality.getValue() / 100f);
    }
}
