package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.gpx.PhotoTime;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.model.MetadataTableModel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Asks how far to move the date/time of the selected photos, e.g. for a camera left on home time. Either a
 * fixed amount, or "the earliest photo was actually taken at ...". Shows old and new times before applying.
 */
public class ShiftTimeDialog extends JDialog {

    private final List<ImageFile> photos;
    private final ImageFile earliest;
    private final JRadioButton rbBy = new JRadioButton("Shift by:", true);
    private final JRadioButton rbTo = new JRadioButton("The earliest photo was taken at:");
    private final JTextField tfBy = new JTextField("+1:00:00", 12);
    private final JTextField tfTo = new JTextField(16);
    private final JLabel lblResult = new JLabel(" ");
    private final JButton btnOk = new JButton("Shift");
    private final PreviewModel preview = new PreviewModel();
    private Duration shift;
    private boolean accepted;

    /** @param photos photos to shift; those without a date are skipped */
    public ShiftTimeDialog(Frame owner, List<ImageFile> photos) {
        super(owner, "Shift date/time", true);
        this.photos = photos.stream().filter(p -> null != p.getTaken())
                .sorted(Comparator.comparing(ImageFile::getTaken)).toList();
        this.earliest = this.photos.isEmpty() ? null : this.photos.get(0);
        if (null != earliest) {
            tfTo.setText(MetadataTableModel.DATE_TIME.format(earliest.getTaken().withNano(0)));
        }
        ButtonGroup group = new ButtonGroup();
        group.add(rbBy);
        group.add(rbTo);
        tfBy.setToolTipText("e.g. +2:00:00, -30:00 (minutes:seconds), 1d 2h, -365d");
        tfTo.setToolTipText("e.g. 2026-09-30 14:05:00");

        DocumentListener update = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }
        };
        tfBy.getDocument().addDocumentListener(update);
        tfTo.getDocument().addDocumentListener(update);
        rbBy.addActionListener(e -> update());
        rbTo.addActionListener(e -> update());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(rbBy, c);
        c.gridx = 1;
        form.add(tfBy, c);
        c.gridx = 0;
        c.gridy = 1;
        form.add(rbTo, c);
        c.gridx = 1;
        form.add(tfTo, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        form.add(lblResult, c);

        JTable table = new JTable(preview);
        table.setEnabled(false);

        btnOk.addActionListener(e -> {
            accepted = true;
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(btnOk);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(btnOk);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(form, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(620, 460);
        setLocationRelativeTo(owner);
        update();
    }

    /** Shows the dialog; returns the shift to apply, or {@code null} if cancelled. */
    public Duration showDialog() {
        setVisible(true);
        return accepted ? shift : null;
    }

    /** Photos that have a date and will be shifted. */
    public List<ImageFile> getPhotos() {
        return photos;
    }

    private void update() {
        tfBy.setEnabled(rbBy.isSelected());
        tfTo.setEnabled(rbTo.isSelected());
        shift = null;
        String error = null;
        try {
            if (rbBy.isSelected()) {
                shift = PhotoTime.parseOffset(tfBy.getText());
            } else if (null != earliest) {
                LocalDateTime target = MetadataTableModel.parseDateTime(tfTo.getText());
                shift = Duration.between(earliest.getTaken().withNano(0), target);
            }
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        if (photos.isEmpty()) {
            lblResult.setText("None of the selected photos has a date to shift.");
        } else if (null != error) {
            lblResult.setText(error);
        } else {
            lblResult.setText("Moves " + photos.size()
                    + (photos.size() == 1 ? " photo " : " photos ") + (shift.isNegative() ? "back" : "forward")
                    + " by " + PhotoTime.formatOffset(shift).substring(1)
                    + (Math.abs(shift.toDays()) > 0 ? " (" + Math.abs(shift.toDays()) + " days)" : ""));
        }
        btnOk.setEnabled(null != shift && !shift.isZero() && !photos.isEmpty());
        preview.fireTableDataChanged();
    }

    private final class PreviewModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return photos.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return new String[]{"Photo", "Now", "After"}[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            ImageFile photo = photos.get(row);
            LocalDateTime taken = photo.getTaken().withNano(0);
            return switch (column) {
                case 0 -> photo.getFile().getName();
                case 1 -> MetadataTableModel.DATE_TIME.format(taken);
                default -> null == shift ? "" : MetadataTableModel.DATE_TIME.format(taken.plus(shift));
            };
        }
    }
}
