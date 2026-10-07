package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.util.Settings;
import me.rothens.gpsexif.video.Ffmpeg;
import me.rothens.gpsexif.video.FrameSource;
import me.rothens.gpsexif.video.VideoEncoder;
import me.rothens.gpsexif.video.VideoExport;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;

/**
 * Exports a playback as an MP4 (H.264) video: pick the size and frame rate, then the frames are rendered and
 * encoded in the background, with progress and Cancel. Uses FFmpeg when it's installed, else a built-in encoder.
 */
public class VideoExportDialog extends JDialog {

    /** Size and frame rate of the video. */
    public record Format(int width, int height, int fps) {
    }

    /** Creates the frames for a format; called on the Swing thread when the export starts. */
    public interface Frames {
        FrameSource create(Format format) throws Exception;
    }

    private record Size(int width, int height, String label) {
        @Override
        public String toString() {
            return width + " × " + height + "  " + label;
        }

        String key() {
            return width + "x" + height;
        }
    }

    private static final Size[] SIZES = {
            new Size(1280, 720, "(HD)"),
            new Size(1920, 1080, "(Full HD)"),
            new Size(2560, 1440, "(QHD)"),
            new Size(3840, 2160, "(4K)"),
            new Size(1080, 1920, "(portrait, for phones)"),
            new Size(1080, 1080, "(square)")};
    private static final Integer[] FPS = {24, 25, 30, 50, 60};
    /** Rough speed of the built-in encoder at 1920×1080, for the time estimate. */
    private static final double BUILT_IN_FPS_AT_1080P = 4;

    private final Settings settings;
    private final String defaultName;
    private final DoubleSupplier lengthSeconds;
    private final Frames frames;
    private final JComboBox<Size> cbSize = new JComboBox<>(SIZES);
    private final JComboBox<Integer> cbFps = new JComboBox<>(FPS);
    private final JLabel lblLength = new JLabel(" ");
    private final JLabel lblEncoder = new JLabel(" ");
    private final JButton btnExport = new JButton("Export...");
    private final JButton btnClose = new JButton("Close");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel lblProgress = new JLabel(" ");
    private final JPanel options;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private String ffmpeg;
    private SwingWorker<Boolean, Void> worker;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * @param defaultName   suggested file name, without extension
     * @param extraOptions  options of the playback (e.g. seconds per photo), shown above the video options; may be
     *                      {@code null}. Call {@link #refreshLength()} when they change the length.
     * @param lengthSeconds the video's length with the current options
     */
    public VideoExportDialog(Window owner, Settings settings, String defaultName, JComponent extraOptions,
                             DoubleSupplier lengthSeconds, Frames frames) {
        super(owner, "Export video", ModalityType.APPLICATION_MODAL);
        this.settings = settings;
        this.defaultName = defaultName;
        this.lengthSeconds = lengthSeconds;
        this.frames = frames;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                closeOrCancel();
            }
        });

        cbSize.setSelectedItem(SIZES[1]);
        for (Size s : SIZES) {
            if (s.key().equals(settings.getVideoSize())) {
                cbSize.setSelectedItem(s);
            }
        }
        cbFps.setSelectedItem(settings.getVideoFps());
        if (cbFps.getSelectedIndex() < 0) {
            cbFps.setSelectedItem(30);
        }
        cbSize.addActionListener(e -> refreshLength());
        cbFps.addActionListener(e -> refreshLength());
        JButton btnFfmpeg = new JButton("FFmpeg...");
        btnFfmpeg.setToolTipText("Set up FFmpeg for faster exports and smaller files");
        btnFfmpeg.addActionListener(e -> setUpFfmpeg());

        options = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        if (null != extraOptions) {
            c.gridx = 0;
            c.gridy = row++;
            c.gridwidth = 2;
            options.add(extraOptions, c);
            c.gridwidth = 1;
        }
        c.gridx = 0;
        c.gridy = row;
        options.add(new JLabel("Size:"), c);
        c.gridx = 1;
        options.add(cbSize, c);
        c.gridx = 0;
        c.gridy = ++row;
        options.add(new JLabel("Frame rate:"), c);
        JPanel fpsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fpsRow.add(cbFps);
        fpsRow.add(new JLabel("  frames per second"));
        c.gridx = 1;
        options.add(fpsRow, c);
        c.gridx = 0;
        c.gridy = ++row;
        c.gridwidth = 2;
        options.add(lblLength, c);
        c.gridy = ++row;
        c.insets = new Insets(12, 4, 4, 4);
        JPanel encoderRow = new JPanel(new BorderLayout(8, 0));
        encoderRow.add(lblEncoder, BorderLayout.CENTER);
        encoderRow.add(btnFfmpeg, BorderLayout.EAST);
        c.fill = GridBagConstraints.HORIZONTAL;
        options.add(encoderRow, c);

        progress.setStringPainted(true);
        JPanel progressPanel = new JPanel(new GridBagLayout());
        GridBagConstraints p = new GridBagConstraints();
        p.gridx = 0;
        p.fill = GridBagConstraints.HORIZONTAL;
        p.weightx = 1;
        p.insets = new Insets(4, 4, 4, 4);
        progressPanel.add(progress, p);
        progressPanel.add(lblProgress, p);

        body.add(options, "options");
        body.add(progressPanel, "progress");

        btnExport.addActionListener(e -> export());
        btnClose.addActionListener(e -> closeOrCancel());
        getRootPane().setDefaultButton(btnExport);
        getRootPane().registerKeyboardAction(e -> closeOrCancel(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(btnExport);
        buttons.add(btnClose);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        ffmpeg = Ffmpeg.locate(settings.getFfmpegPath());
        refreshLength();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    public void showDialog() {
        setVisible(true);
    }

    private Format format() {
        Size size = (Size) cbSize.getSelectedItem();
        return new Format(size.width(), size.height(), (Integer) cbFps.getSelectedItem());
    }

    /** Updates the length and time estimate after an option changed. */
    public void refreshLength() {
        Format f = format();
        double seconds = lengthSeconds.getAsDouble();
        long frameCount = Math.round(seconds * f.fps());
        lblLength.setText("Length " + clock(seconds) + "  ·  " + String.format(Locale.ROOT, "%,d", frameCount)
                + " frames");
        if (null != ffmpeg) {
            showEncoder("Encoder: FFmpeg " + Ffmpeg.version(ffmpeg) + " (" + ffmpeg + ")", true);
        } else {
            double fps = BUILT_IN_FPS_AT_1080P * 1920.0 * 1080 / (f.width() * f.height());
            showEncoder("<html>FFmpeg wasn't found, so the built-in encoder is used:<br>slower (this video takes "
                    + duration(frameCount / fps) + ") and larger files.</html>", false);
        }
    }

    private void showEncoder(String text, boolean ok) {
        lblEncoder.setText(text);
        Color warning = UIManager.getColor("Component.warning.focusedBorderColor");
        lblEncoder.setForeground(ok ? UIManager.getColor("Label.foreground")
                : null != warning ? warning : new Color(190, 120, 0));
    }

    private void setUpFfmpeg() {
        String path = new FfmpegDialog(this, settings.getFfmpegPath(), ffmpeg).showDialog();
        if (null != path) {
            settings.setFfmpegPath(path);
            ffmpeg = Ffmpeg.locate(path);
            refreshLength();
            pack();
        }
    }

    private void export() {
        Path target = chooseFile();
        if (null == target) {
            return;
        }
        Format format = format();
        settings.setVideoSize(((Size) cbSize.getSelectedItem()).key());
        settings.setVideoFps(format.fps());
        FrameSource source;
        try {
            source = frames.create(format);
        } catch (Exception e) {
            showError("The video can't be made:\n" + e.getMessage());
            return;
        }
        int total = source.getFrameCount();
        progress.setMaximum(total);
        progress.setValue(0);
        lblProgress.setText("Starting...");
        cards.show(body, "progress");
        btnExport.setEnabled(false);
        btnClose.setText("Cancel");
        cancelled.set(false);
        long start = System.nanoTime();
        String encoderPath = ffmpeg;
        worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                VideoEncoder encoder;
                try {
                    encoder = VideoExport.createEncoder(encoderPath, target, format.width(), format.height(),
                            format.fps());
                } catch (Exception e) {
                    source.close();
                    throw e;
                }
                return VideoExport.run(source, encoder, format.width(), format.height(),
                        done -> SwingUtilities.invokeLater(() -> showProgress(done, total, start)),
                        cancelled::get);
            }

            @Override
            protected void done() {
                finished(target, source);
            }
        };
        worker.execute();
    }

    private void showProgress(int done, int total, long start) {
        if (cancelled.get()) {
            return;
        }
        progress.setValue(done);
        double elapsed = (System.nanoTime() - start) / 1e9;
        String text = "Frame " + String.format(Locale.ROOT, "%,d of %,d", done, total);
        if (done >= 10 && elapsed > 2) {
            text += "  ·  " + duration(elapsed / done * (total - done)) + " left";
        }
        lblProgress.setText(text);
    }

    private void finished(Path target, FrameSource source) {
        boolean ok = false;
        String error = null;
        try {
            ok = !cancelled.get() && worker.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = null != e.getCause() ? e.getCause() : e;
            error = null != cause.getMessage() ? cause.getMessage() : cause.toString();
        } catch (java.util.concurrent.CancellationException e) {
            // cancelled
        }
        worker = null;
        if (cancelled.get()) {
            dispose();
            return;
        }
        if (!ok) {
            cards.show(body, "options");
            btnExport.setEnabled(true);
            btnClose.setText("Close");
            showError("The video couldn't be exported:\n" + error);
            return;
        }
        dispose();
        StringBuilder msg = new StringBuilder("Saved " + target.getFileName());
        try {
            msg.append(" (").append(SettingsDialog.formatSize(Files.size(target))).append(")");
        } catch (java.io.IOException ignored) {
            // just leave the size out
        }
        msg.append('.');
        if (!source.getWarnings().isEmpty()) {
            msg.append("\n\n").append(source.getWarnings());
        }
        Object[] choices = {"Open video", "Close"};
        int choice = JOptionPane.showOptionDialog(getOwner(), msg.toString(), "Export video",
                JOptionPane.DEFAULT_OPTION, source.getWarnings().isEmpty() ? JOptionPane.INFORMATION_MESSAGE
                        : JOptionPane.WARNING_MESSAGE, null, choices, choices[1]);
        if (choice == 0) {
            try {
                Desktop.getDesktop().open(target.toFile());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(getOwner(), "Couldn't open the video:\n" + e.getMessage(),
                        "Export video", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void closeOrCancel() {
        if (null == worker) {
            dispose();
            return;
        }
        if (!cancelled.get()) {
            cancelled.set(true);
            lblProgress.setText("Cancelling...");
            btnClose.setEnabled(false);
            worker.cancel(true);
        }
    }

    private Path chooseFile() {
        String dir = settings.getVideoDirectory();
        JFileChooser chooser = new JFileChooser(dir.isEmpty() ? null : new File(dir));
        chooser.setDialogTitle("Export video");
        chooser.setFileFilter(new FileNameExtensionFilter("MP4 video (*.mp4)", "mp4"));
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), defaultName + ".mp4"));
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                file = new File(file.getParentFile(), file.getName() + ".mp4");
            }
            if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " already exists. Replace it?",
                    "Export video", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                continue;
            }
            settings.setVideoDirectory(file.getParent());
            return file.toPath();
        }
        return null;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Export video", JOptionPane.WARNING_MESSAGE);
    }

    private static String clock(double seconds) {
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** A rough duration: "under a minute", "about 12 min", "about 2.5 hours". */
    static String duration(double seconds) {
        if (seconds < 60) {
            return "under a minute";
        }
        long m = Math.round(seconds / 60);
        return m < 90 ? "about " + m + " min" : String.format(Locale.ROOT, "about %.1f hours", m / 60.0);
    }
}
