package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.metadata.ExifToolBackend;
import me.rothens.gpsexif.metadata.TextTag;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.rename.RenamePattern;
import me.rothens.gpsexif.rename.RenamePlan;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Renames photos with a pattern such as {@code {date} {place} {n:000}}, showing the old and new names before
 * anything is renamed.
 */
public class RenameDialog extends JDialog {

    static final List<String> PRESETS = List.of("{date} {place} {n:000}", "{date:yyyy-MM-dd HH.mm.ss}",
            "{date:yyyyMMdd}_{n:0000}", "{place} {date} {n:00}", "{date} {name}");

    private final List<ImageFile> photos;
    private final List<RenamePlan.Source> sources = new ArrayList<>();
    private final Settings settings;
    private final JComboBox<String> cbPattern = new JComboBox<>(PRESETS.toArray(new String[0]));
    private final JLabel lblResult = new JLabel(" ");
    private final JButton btnOk = new JButton("Rename");
    private final PreviewModel preview = new PreviewModel();
    private final Timer refresh = new Timer(200, e -> update());
    private RenamePlan plan;
    private boolean accepted;

    public RenameDialog(Frame owner, List<ImageFile> photos, Settings settings) {
        super(owner, "Rename " + photos.size() + (photos.size() == 1 ? " photo" : " photos"), true);
        this.photos = photos;
        this.settings = settings;
        for (ImageFile p : photos) {
            String name = p.getFile().getName();
            int dot = name.lastIndexOf('.');
            sources.add(new RenamePlan.Source(p.getPath(), p.getTaken(), new RenamePattern.Values(p.getTaken(),
                    p.getPlace(), dot > 0 ? name.substring(0, dot) : name, p.getText(TextTag.MODEL)),
                    ExifToolBackend.sidecarOf(p.getPath())));
        }
        refresh.setRepeats(false);

        cbPattern.setEditable(true);
        cbPattern.setSelectedItem(settings.getRenamePattern());
        cbPattern.setToolTipText("<html>{date} or e.g. {date:yyyy-MM-dd HH.mm}: the date taken<br>"
                + "{place}: the city, {country}, {state}, {sublocation}: the place name<br>"
                + "{name}: the current name, {camera}: the camera model<br>"
                + "{n}: a number in date order, {n:000} with 3 digits</html>");
        JTextComponent editor = (JTextComponent) cbPattern.getEditor().getEditorComponent();
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh.restart();
            }
        });
        cbPattern.addActionListener(e -> refresh.restart());

        JPanel insert = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        insert.add(new JLabel("Add:"));
        String[][] fields = {{"Date", "{date}"}, {"Time", "{date:HH.mm.ss}"}, {"Place", "{place}"},
                {"Country", "{country}"}, {"Number", "{n:000}"}, {"Current name", "{name}"}, {"Camera", "{camera}"}};
        for (String[] field : fields) {
            JButton b = new JButton(field[0]);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setToolTipText(field[1]);
            b.addActionListener(e -> {
                int at = editor.getCaretPosition();
                String text = editor.getText();
                String before = text.substring(0, at);
                String add = (before.isEmpty() || before.endsWith(" ") ? "" : " ") + field[1];
                editor.setText(before + add + text.substring(at));
                editor.setCaretPosition(at + add.length());
                editor.requestFocusInWindow();
            });
            insert.add(b);
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("New names:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(cbPattern, c);
        c.gridy = 1;
        c.insets = new Insets(0, 0, 4, 4);
        form.add(insert, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = new Insets(4, 4, 4, 4);
        form.add(lblResult, c);

        JTable table = new JTable(preview);
        table.setFocusable(false);
        table.setRowSelectionAllowed(false);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, false, false, row, column);
                RenamePlan.Item item = null == plan ? null : plan.items().get(row);
                boolean stays = null != item && !item.isChanged();
                setForeground(stays || column == 2 ? UIManager.getColor("Label.disabledForeground") : t.getForeground());
                setToolTipText(null == value ? null : value.toString());
                return this;
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);

        btnOk.addActionListener(e -> {
            update();
            if (null != plan && plan.changedCount() > 0) {
                settings.setRenamePattern(cbPattern.getEditor().getItem().toString());
                accepted = true;
                dispose();
            }
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(btnOk);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JLabel note = new JLabel("Backups (.bak) and XMP sidecars are renamed along. Undo puts the old names back.");
        note.putClientProperty("FlatLaf.styleClass", "small");
        note.setEnabled(false);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(note, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(btnOk);
        right.add(cancel);
        buttons.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(form, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(760, 520);
        setLocationRelativeTo(owner);
        update();
    }

    /** Shows the dialog; returns what to rename, or {@code null} if cancelled. */
    public RenamePlan showDialog() {
        setVisible(true);
        return accepted ? plan : null;
    }

    private void update() {
        refresh.stop();
        String text = String.valueOf(cbPattern.getEditor().getItem());
        try {
            RenamePattern pattern = new RenamePattern(text);
            plan = RenamePlan.of(sources, pattern);
            long changed = plan.changedCount();
            long noPlace = photos.stream().filter(p -> null == p.getPlace()).count();
            String hint = "";
            if (text.matches(".*\\{(place|city|country|state|sublocation)}.*") && noPlace > 0) {
                hint = "  " + noPlace + (noPlace == 1 ? " has" : " have") + " no place name"
                        + (photos.stream().anyMatch(p -> null == p.getPlace() && p.hasExifGPS())
                        ? " yet: Edit → Look up place names" : "");
            }
            lblResult.setForeground(UIManager.getColor("Label.foreground"));
            lblResult.setText(changed == 0 ? "The photos are already named like this."
                    : changed + (changed == 1 ? " photo gets" : " photos get") + " a new name"
                    + (changed < photos.size() ? ", " + (photos.size() - changed) + " stay as they are." : ".")
                    + hint);
            btnOk.setEnabled(changed > 0);
        } catch (IllegalArgumentException e) {
            plan = null;
            lblResult.setForeground(UIManager.getColor("Component.error.focusedBorderColor"));
            lblResult.setText(e.getMessage());
            btnOk.setEnabled(false);
        }
        preview.fireTableDataChanged();
    }

    /** E.g. "+ backup, XMP sidecar": the files renamed along with the photo. */
    private static String alongWith(RenamePlan.Item item) {
        List<String> kinds = new ArrayList<>();
        for (RenamePlan.Move move : item.moves().subList(1, item.moves().size())) {
            String name = move.from().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String kind = name.endsWith(".xmp.bak") ? "sidecar backup" : name.endsWith(".xmp") ? "XMP sidecar"
                    : "backup";
            if (!kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        return kinds.isEmpty() ? "" : "+ " + String.join(", ", kinds);
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
            return switch (column) {
                case 0 -> "Now";
                case 1 -> "New name";
                default -> "Note";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            ImageFile photo = photos.get(row);
            RenamePlan.Item item = null == plan ? null : plan.items().get(row);
            return switch (column) {
                case 0 -> photo.getFile().getName();
                case 1 -> null == item ? "" : item.newName();
                default -> null == item ? "" : null != item.warning() ? item.warning()
                        : item.isChanged() ? alongWith(item) : "stays";
            };
        }
    }
}
