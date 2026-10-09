package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.tools.ToolInstaller;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offers to download ExifTool and FFmpeg (on Windows; elsewhere it says how to install FFmpeg), with progress and
 * Cancel. Shown on the first start, from the tool dialogs and for updates of a downloaded ExifTool.
 */
public class ToolInstallDialog extends JDialog {

    /** What was installed: the executables, or {@code null}. */
    public record Result(String exifTool, String ffmpeg) {
    }

    private final ToolInstaller installer;
    private final Runnable stopExifTool;
    private final JCheckBox chkExifTool = new JCheckBox();
    private final JCheckBox chkFfmpeg = new JCheckBox();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel lblStatus = new JLabel(" ");
    private final JButton btnInstall = new JButton("Download and install");
    private final JButton btnClose;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private SwingWorker<Result, Object[]> worker;
    private String exifTool;
    private String ffmpeg;

    /**
     * @param exifToolOffer what the ExifTool row says (e.g. "not found", "13.11 is available"), or {@code null} to
     *                      leave ExifTool out
     * @param ffmpegOffer   the same for FFmpeg
     * @param firstStart    whether this is the offer on the first start ("Not now" instead of "Cancel")
     * @param stopExifTool  stops the ExifTool in use before a downloaded one is replaced
     */
    public ToolInstallDialog(Window owner, ToolInstaller installer, String exifToolOffer, String ffmpegOffer,
                             boolean firstStart, Runnable stopExifTool) {
        super(owner, firstStart ? "Set up ExifTweaker" : "Download tools", ModalityType.APPLICATION_MODAL);
        this.installer = installer;
        this.stopExifTool = stopExifTool;
        btnClose = new JButton(firstStart ? "Not now" : "Cancel");

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JLabel intro = new JLabel("<html><body style='width:440px'>" + (firstStart
                ? "JPEG photos work right away. Two free programs make ExifTweaker do more; they aren't part of it, "
                + "but it can download them for you:" : "ExifTweaker can download these for you:") + "</body></html>");
        rows.add(left(intro));
        rows.add(Box.createVerticalStrut(8));
        if (null != exifToolOffer) {
            chkExifTool.setSelected(true);
            rows.add(left(option(chkExifTool, "<b>ExifTool</b> by Phil Harvey (about 10 MB) - " + exifToolOffer,
                    "Reads and writes HEIC (iPhone), PNG, TIFF, WebP and RAW files.")));
        }
        if (null != ffmpegOffer) {
            if (installer.canInstallFfmpeg()) {
                chkFfmpeg.setSelected(true);
                rows.add(left(option(chkFfmpeg, "<b>FFmpeg</b> (about 90 MB) - " + ffmpegOffer,
                        "Makes the video export many times faster, with smaller files.")));
            } else {
                JLabel hint = new JLabel("<html><body style='width:440px'><b>FFmpeg</b> makes the video export many "
                        + "times faster. " + (ToolDialog.isMac() ? "Install it with <i>brew install ffmpeg</i>."
                        : "Install your distribution's <i>ffmpeg</i> package.") + " It's found automatically.</body></html>");
                rows.add(left(hint));
            }
        }
        rows.add(Box.createVerticalStrut(8));
        JLabel source = new JLabel("<html><body style='width:440px'>Downloaded from exiftool.org"
                + (installer.canInstallFfmpeg() && null != ffmpegOffer ? " and gyan.dev (the FFmpeg builds ffmpeg.org "
                + "links to)" : "") + ", checked against their published checksums, into "
                + escape(installer.getToolsDir().toString()) + ". You can change or remove them any time in "
                + "Settings.</body></html>");
        source.putClientProperty("FlatLaf.styleClass", "small");
        source.setEnabled(false);
        rows.add(left(source));
        rows.add(Box.createVerticalStrut(8));
        progress.setStringPainted(true);
        progress.setString("");
        rows.add(left(progress));
        rows.add(left(lblStatus));

        boolean anything = null != exifToolOffer || (null != ffmpegOffer && installer.canInstallFfmpeg());
        btnInstall.setEnabled(anything);
        btnInstall.addActionListener(e -> install());
        chkExifTool.addActionListener(e -> updateButton());
        chkFfmpeg.addActionListener(e -> updateButton());
        btnClose.addActionListener(e -> close());
        getRootPane().setDefaultButton(btnInstall);
        getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                close();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(btnInstall);
        buttons.add(btnClose);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(rows, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; returns what was installed (both {@code null} if nothing). */
    public Result showDialog() {
        setVisible(true);
        return new Result(exifTool, ffmpeg);
    }

    private static JComponent option(JCheckBox box, String title, String description) {
        box.setText("<html>" + title + "</html>");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(box, BorderLayout.NORTH);
        JLabel text = new JLabel(description);
        text.putClientProperty("FlatLaf.styleClass", "small");
        text.setEnabled(false);
        text.setBorder(BorderFactory.createEmptyBorder(0, 24, 6, 0));
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (c instanceof JProgressBar) {
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        }
        return c;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private void updateButton() {
        if (null == worker) {
            btnInstall.setEnabled(chkExifTool.isSelected() || chkFfmpeg.isSelected());
        }
    }

    private void close() {
        if (null != worker && !worker.isDone()) {
            cancelled.set(true);
            lblStatus.setText("Cancelling...");
            return;
        }
        dispose();
    }

    private void install() {
        boolean wantExifTool = chkExifTool.isSelected();
        boolean wantFfmpeg = chkFfmpeg.isSelected() && installer.canInstallFfmpeg();
        if (!wantExifTool && !wantFfmpeg) {
            return;
        }
        btnInstall.setEnabled(false);
        chkExifTool.setEnabled(false);
        chkFfmpeg.setEnabled(false);
        btnClose.setText("Cancel");
        cancelled.set(false);
        lblStatus.setText(" ");
        ToolInstaller.Progress listener = new ToolInstaller.Progress() {
            @Override
            public void update(String what, long done, long total) {
                worker.firePropertyChange("download", null, new Object[]{what, done, total});
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        worker = new SwingWorker<>() {
            @Override
            protected Result doInBackground() throws Exception {
                String e = null;
                String f = null;
                if (wantExifTool) {
                    Path installed = installer.installExifTool(listener, () -> {
                        try {
                            SwingUtilities.invokeAndWait(stopExifTool);
                        } catch (Exception ignored) {
                            // the replacement then fails and says so
                        }
                    });
                    e = installed.toString();
                    exifTool = e;
                }
                if (wantFfmpeg) {
                    f = installer.installFfmpeg(listener).toString();
                    ffmpeg = f;
                }
                return new Result(e, f);
            }

            @Override
            protected void done() {
                chkExifTool.setEnabled(true);
                chkFfmpeg.setEnabled(true);
                btnClose.setText("Close");
                try {
                    get();
                    dispose();
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = null == ex.getCause() ? ex : ex.getCause();
                    progress.setValue(0);
                    progress.setString("");
                    lblStatus.setForeground(UIManager.getColor("Component.error.focusedBorderColor"));
                    lblStatus.setText("<html><body style='width:440px'>" + (cancelled.get() ? "Cancelled."
                            : escape(String.valueOf(cause.getMessage()))) + (null != exifTool ? " (ExifTool was "
                            + "installed.)" : "") + "</body></html>");
                    btnInstall.setEnabled(true);
                    worker = null;
                    pack();
                }
            }
        };
        worker.addPropertyChangeListener(e -> {
            if ("download".equals(e.getPropertyName()) && e.getNewValue() instanceof Object[] v) {
                SwingUtilities.invokeLater(() -> showProgress((String) v[0], (Long) v[1], (Long) v[2]));
            }
        });
        worker.execute();
    }

    private void showProgress(String what, long done, long total) {
        if (total > 0) {
            progress.setIndeterminate(false);
            progress.setMaximum(1000);
            progress.setValue((int) (1000 * done / total));
            progress.setString(what + ": " + done / (1024 * 1024) + " of " + Math.max(1, total / (1024 * 1024)) + " MB");
        } else {
            progress.setIndeterminate(true);
            progress.setString(what + ": " + done / (1024 * 1024) + " MB");
        }
    }
}
