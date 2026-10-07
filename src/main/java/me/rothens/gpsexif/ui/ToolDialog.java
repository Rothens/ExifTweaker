package me.rothens.gpsexif.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Explains what an external program (ExifTool, FFmpeg) is for, links to its download page, and lets the user pick
 * the executable, so it doesn't have to be on the PATH. These programs aren't bundled with ExifTweaker.
 */
public class ToolDialog extends JDialog {

    /**
     * @param name        e.g. "ExifTool"
     * @param command     the command looked for on the PATH, e.g. "exiftool"
     * @param introHtml   what it's for (HTML body text)
     * @param installHint how to install it on this OS (HTML body text)
     * @param locate      configured path (may be empty) to the executable to use, or {@code null} if none works
     * @param version     executable to its version, or {@code null} if it doesn't run
     */
    public record Tool(String name, String command, String downloadUrl, String introHtml, String installHint,
                       UnaryOperator<String> locate, UnaryOperator<String> version) {
    }

    private final Tool tool;
    private final JTextField tfPath = new JTextField(32);
    private final JLabel lblStatus = new JLabel(" ");
    private boolean accepted;

    /**
     * @param configured the currently configured path (may be empty)
     * @param active     the executable in use, or {@code null} if it wasn't found
     */
    public ToolDialog(Window owner, Tool tool, String configured, String active) {
        super(owner, tool.name(), ModalityType.APPLICATION_MODAL);
        this.tool = tool;
        tfPath.setText(configured);
        tfPath.putClientProperty("JTextField.placeholderText",
                "Empty: look for \"" + tool.command() + "\" on the PATH");

        JLabel intro = new JLabel("<html><body style='width:430px'>" + tool.introHtml() + "</body></html>");

        JButton link = new JButton("<html><a href=''>" + tool.downloadUrl() + "</a></html>");
        link.setBorderPainted(false);
        link.setContentAreaFilled(false);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("Open the " + tool.name() + " download page in your browser");
        link.addActionListener(e -> openDownloadPage());

        JLabel howTo = new JLabel("<html><body style='width:430px'>" + tool.installHint() + "</body></html>");
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
        settings.add(new JLabel(tool.name() + " executable:"), BorderLayout.NORTH);
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
        showStatus(null != active ? "In use: " + tool.name() + " " + tool.version().apply(active) + " (" + active + ")"
                : tool.name() + " wasn't found.", null != active);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; returns the path to save (possibly empty), or {@code null} if cancelled. */
    public String showDialog() {
        setVisible(true);
        return accepted ? tfPath.getText().strip() : null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private void openDownloadPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(tool.downloadUrl()));
                return;
            }
        } catch (Exception ignored) {
            // fall through: copy the link instead
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(tool.downloadUrl()), null);
        showStatus("Couldn't open a browser - the link was copied to the clipboard.", false);
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser(tfPath.getText().isBlank() ? null : new File(tfPath.getText()));
        chooser.setDialogTitle("Select the " + tool.name() + " executable");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            tfPath.setText(chooser.getSelectedFile().getPath());
            test();
        }
    }

    private void test() {
        String path = tfPath.getText().strip();
        String executable = tool.locate().apply(path);
        String name = tool.name();
        if (null == executable) {
            showStatus(path.isEmpty() ? name + " wasn't found on the PATH." : "That doesn't run as " + name + ".", false);
        } else if (!path.isEmpty() && !executable.equals(path)) {
            showStatus("That doesn't run as " + name + " (but " + executable + " on the PATH would be used).", false);
        } else {
            showStatus("Works: " + name + " " + tool.version().apply(executable) + " (" + executable + ")", true);
        }
    }

    private void showStatus(String text, boolean ok) {
        lblStatus.setText(text);
        Color error = UIManager.getColor("Component.error.focusedBorderColor");
        lblStatus.setForeground(ok ? new Color(0, 150, 0) : null != error ? error : new Color(200, 40, 40));
    }
}
