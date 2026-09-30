package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.metadata.ExifTool;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.Locale;

/**
 * Explains what ExifTool is for, links to its download page, and lets the user pick the executable, so it
 * doesn't have to be on the PATH. ExifTool isn't bundled with ExifTweaker.
 */
public class ExifToolDialog extends JDialog {

    public static final String DOWNLOAD_URL = "https://exiftool.org/";

    private final JTextField tfPath = new JTextField(32);
    private final JLabel lblStatus = new JLabel(" ");
    private boolean accepted;

    /**
     * @param configured the currently configured path (may be empty)
     * @param active     the executable in use, or {@code null} if ExifTool wasn't found
     */
    public ExifToolDialog(Window owner, String configured, String active) {
        super(owner, "ExifTool", ModalityType.APPLICATION_MODAL);
        tfPath.setText(configured);
        tfPath.putClientProperty("JTextField.placeholderText", "Empty: look for \"exiftool\" on the PATH");

        JLabel intro = new JLabel("<html><body style='width:430px'>"
                + "JPEG photos work out of the box. <b>HEIC, PNG, TIFF, WebP and RAW</b> files (CR2, CR3, NEF, ARW, "
                + "DNG, ...) need the free <b>ExifTool</b> by Phil Harvey. RAW files are never modified: their "
                + "metadata goes into an .xmp sidecar file next to them.</body></html>");

        JButton link = new JButton("<html><a href=''>" + DOWNLOAD_URL + "</a></html>");
        link.setBorderPainted(false);
        link.setContentAreaFilled(false);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("Open the ExifTool download page in your browser");
        link.addActionListener(e -> openDownloadPage());

        JLabel howTo = new JLabel("<html><body style='width:430px'>" + installHint() + "</body></html>");
        howTo.putClientProperty("FlatLaf.styleClass", "small");

        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> browse());
        JButton test = new JButton("Test");
        test.addActionListener(e -> test());

        JPanel pathRow = new JPanel(new BorderLayout(4, 0));
        pathRow.add(tfPath, BorderLayout.CENTER);
        JPanel pathButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pathButtons.add(browse);
        pathButtons.add(test);
        pathRow.add(pathButtons, BorderLayout.EAST);

        JPanel settings = new JPanel(new BorderLayout(0, 4));
        settings.setBorder(BorderFactory.createTitledBorder("Settings"));
        settings.add(new JLabel("ExifTool executable:"), BorderLayout.NORTH);
        settings.add(pathRow, BorderLayout.CENTER);
        settings.add(lblStatus, BorderLayout.SOUTH);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{intro, link, howTo}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            top.add(c);
            top.add(Box.createVerticalStrut(6));
        }

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            accepted = true;
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(settings, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        showStatus(null != active ? "In use: ExifTool " + ExifTool.version(active) + " (" + active + ")"
                : "ExifTool wasn't found.", null != active);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; returns the path to save (possibly empty), or {@code null} if cancelled. */
    public String showDialog() {
        setVisible(true);
        return accepted ? tfPath.getText().strip() : null;
    }

    private static String installHint() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return "Windows: download the Windows executable, unzip it, and rename <i>exiftool(-k).exe</i> to "
                    + "<i>exiftool.exe</i>. Keep the <i>exiftool_files</i> folder next to it. Then pick the .exe below.";
        }
        if (os.contains("mac")) {
            return "macOS: install the MacOS package from the download page, or run <i>brew install exiftool</i>. "
                    + "It's usually found automatically; otherwise pick /usr/local/bin/exiftool below.";
        }
        return "Linux: install your distribution's package (e.g. <i>libimage-exiftool-perl</i> or "
                + "<i>perl-Image-ExifTool</i>). It's usually found automatically.";
    }

    private void openDownloadPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(DOWNLOAD_URL));
                return;
            }
        } catch (Exception ignored) {
            // fall through: copy the link instead
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(DOWNLOAD_URL), null);
        showStatus("Couldn't open a browser - the link was copied to the clipboard.", false);
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser(tfPath.getText().isBlank() ? null : new File(tfPath.getText()));
        chooser.setDialogTitle("Select the ExifTool executable");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            tfPath.setText(chooser.getSelectedFile().getPath());
            test();
        }
    }

    private void test() {
        String path = tfPath.getText().strip();
        String executable = ExifTool.locate(path);
        if (null == executable) {
            showStatus(path.isEmpty() ? "ExifTool wasn't found on the PATH." : "That doesn't run as ExifTool.", false);
        } else if (!path.isEmpty() && !executable.equals(path)) {
            showStatus("That doesn't run as ExifTool (but " + executable + " on the PATH would be used).", false);
        } else {
            showStatus("Works: ExifTool " + ExifTool.version(executable) + " (" + executable + ")", true);
        }
    }

    private void showStatus(String text, boolean ok) {
        lblStatus.setText(text);
        Color error = UIManager.getColor("Component.error.focusedBorderColor");
        lblStatus.setForeground(ok ? new Color(0, 150, 0) : null != error ? error : new Color(200, 40, 40));
    }
}
