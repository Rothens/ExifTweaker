package me.rothens.gpsexif.playback;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.ui.VideoExportDialog;
import me.rothens.gpsexif.util.PhotoLoader;
import me.rothens.gpsexif.util.Settings;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.cache.LocalCache;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;

import javax.swing.*;
import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Plays the photos back in the order they were taken: the photo fills the window, the time it was taken is shown
 * top left, and a small map bottom right follows the route.
 * <p>
 * The frame is drawn by a {@link PlaybackView}; the video export draws its frames with another one, off screen.
 */
public class PlaybackWindow extends JFrame {

    private static final int PHOTO_SIZE = 2400;
    private static final int CACHE_SIZE = 6;
    private static final int[] SECONDS = {1, 2, 3, 5, 8, 15};

    private final PlaybackSequence sequence;
    private final Settings settings;
    private final PlaybackView view;
    private final DefaultTileFactory tileFactory;
    private final Map<ImageFile, BufferedImage> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ImageFile, BufferedImage> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private final JButton btnPlay = new JButton("▶");
    private final JSlider slider;
    private final JComboBox<String> cbSpeed = new JComboBox<>();
    private final JCheckBox chkLoop = new JCheckBox(tr("Loop"));
    private final JSpinner spFade;
    private final JComboBox<String> cbZone;
    private ClockZone clock;
    private final Timer timer;
    private boolean updatingSlider;
    private int shownIndex = -1;

    /**
     * @param tileInfo  map layer to use for the inset
     * @param tileCache disk cache shared with the main window
     * @param settings  camera time zone, and where the chosen display time zone is remembered
     */
    public PlaybackWindow(Window owner, PlaybackSequence sequence, TileFactoryInfo tileInfo, LocalCache tileCache,
                          String userAgent, Settings settings) {
        super(tr("Playback"));
        this.sequence = sequence;
        this.settings = settings;
        this.clock = new ClockZone(settings.getCameraZone(), settings.getDisplayZone());
        cbZone = ClockZone.createChooser(clock.getCameraZone(), clock.getDisplayZone());
        setIconImages(owner.getIconImages());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        tileFactory = new DefaultTileFactory(tileInfo);
        tileFactory.setUserAgent(userAgent);
        tileFactory.setLocalCache(tileCache);
        tileFactory.setThreadPoolSize(4);
        view = new PlaybackView(tileFactory, sequence.getRoute(), clock);
        JXMapViewer map = view.getMap();
        MouseInputListener pan = new PanMouseInputListener(map);
        map.addMouseListener(pan);
        map.addMouseMotionListener(pan);
        map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));
        cbZone.addActionListener(e -> {
            clock = new ClockZone(clock.getCameraZone(), ClockZone.selected(cbZone));
            settings.setDisplayZone(clock.getDisplayZone());
            view.setClock(clock);
        });

        slider = new JSlider(0, Math.max(0, sequence.size() - 1), 0);
        slider.addChangeListener(e -> {
            if (!updatingSlider) {
                sequence.seek(slider.getValue());
                showCurrent();
            }
        });
        for (int s : SECONDS) {
            cbSpeed.addItem(s == 1 ? tr("1 second") : tr("{0} seconds", s));
        }
        cbSpeed.setSelectedIndex(2);
        timer = new Timer(delay(), e -> advance());
        spFade = new JSpinner(new SpinnerNumberModel(settings.getPlaybackFade(), 0.0, 3.0, 0.1));
        spFade.setToolTipText(tr("Each photo fades into the next (0 for a hard cut)"));
        spFade.setEditor(new JSpinner.NumberEditor(spFade, "0.0"));
        JFormattedTextField fadeField = ((JSpinner.DefaultEditor) spFade.getEditor()).getTextField();
        fadeField.setColumns(3);
        fadeField.setFocusable(false); // set with the arrows, keeping Space and Left/Right for the playback
        spFade.addChangeListener(e -> {
            settings.setPlaybackFade((Double) spFade.getValue());
            applyFade();
        });
        cbSpeed.addActionListener(e -> {
            timer.setDelay(delay());
            timer.setInitialDelay(delay());
            applyFade();
        });

        JButton btnPrev = new JButton("⏮");
        JButton btnNext = new JButton("⏭");
        btnPrev.setToolTipText(tr("Previous photo (Left)"));
        btnNext.setToolTipText(tr("Next photo (Right)"));
        btnPlay.setToolTipText(tr("Play / pause (Space)"));
        btnPrev.addActionListener(e -> step(-1));
        btnNext.addActionListener(e -> step(1));
        btnPlay.addActionListener(e -> togglePlay());
        for (JComponent c : new JComponent[]{btnPrev, btnPlay, btnNext, slider, cbSpeed, spFade, chkLoop, cbZone}) {
            c.setFocusable(false); // keep the keyboard shortcuts working
        }

        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(btnPrev);
        left.add(btnPlay);
        left.add(btnNext);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(new JLabel(tr("Each photo:")));
        right.add(cbSpeed);
        right.add(new JLabel("  " + tr("Cross-fade (s):")));
        right.add(spFade);
        right.add(chkLoop);
        right.add(new JLabel("  " + tr("Times in:")));
        right.add(cbZone);
        JButton btnExport = new JButton(tr("Export video..."));
        btnExport.setToolTipText(tr("Save the playback as an MP4 video"));
        btnExport.setFocusable(false);
        btnExport.addActionListener(e -> exportVideo());
        right.add(new JLabel("  "));
        right.add(btnExport);
        controls.add(left, BorderLayout.WEST);
        controls.add(slider, BorderLayout.CENTER);
        controls.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(view, BorderLayout.CENTER);
        content.add(controls, BorderLayout.SOUTH);
        setContentPane(content);
        installKeys(content);
        applyFade();

        setSize(1280, 820);
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                view.fitRoute();
                showCurrent();
            }
        });
    }

    private void exportVideo() {
        if (sequence.isEmpty()) {
            return;
        }
        if (timer.isRunning()) {
            stop();
        }
        JSpinner spSeconds = new JSpinner(new SpinnerNumberModel(
                (double) SECONDS[Math.max(0, cbSpeed.getSelectedIndex())], 0.5, 60.0, 0.5));
        JSpinner spVideoFade = new JSpinner(new SpinnerNumberModel(((Double) spFade.getValue()).doubleValue(), 0.0, 5.0, 0.1));
        spVideoFade.setToolTipText(tr("Each photo fades into the next during its last moments (0 for a hard cut)"));
        JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        extra.add(new JLabel(tr("Each photo (s):")));
        extra.add(spSeconds);
        extra.add(new JLabel("   " + tr("Cross-fade (s):")));
        extra.add(spVideoFade);
        ClockZone videoClock = clock;
        VideoExportDialog dialog = new VideoExportDialog(this, settings,
                TravelWindow.defaultVideoName(sequence.getPhotos(), "playback"), extra,
                () -> sequence.size() * (Double) spSeconds.getValue(),
                f -> new PlaybackFrames(sequence, videoClock, tileFactory, f.width(), f.height(), f.fps(),
                        (Double) spSeconds.getValue(), (Double) spVideoFade.getValue()));
        spSeconds.addChangeListener(e -> dialog.refreshLength());
        dialog.showDialog();
    }

    /** The cross-fade, at most half of each photo's time so a photo is shown fully for a while. */
    private void applyFade() {
        view.setFade(Math.min((Double) spFade.getValue(), delay() / 2000.0));
    }

    private int delay() {
        return SECONDS[Math.max(0, cbSpeed.getSelectedIndex())] * 1000;
    }

    private void installKeys(JComponent root) {
        bind(root, KeyEvent.VK_SPACE, "toggle", this::togglePlay);
        bind(root, KeyEvent.VK_LEFT, "previous", () -> step(-1));
        bind(root, KeyEvent.VK_RIGHT, "next", () -> step(1));
        bind(root, KeyEvent.VK_HOME, "first", () -> jump(0));
        bind(root, KeyEvent.VK_END, "last", () -> jump(sequence.size() - 1));
        bind(root, KeyEvent.VK_ESCAPE, "close", this::dispose);
    }

    private static void bind(JComponent root, int key, String name, Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    public boolean isPlaying() {
        return timer.isRunning();
    }

    public void togglePlay() {
        if (timer.isRunning()) {
            stop();
        } else {
            if (!sequence.hasNext() && !chkLoop.isSelected()) {
                jump(0); // at the end: start over
            }
            timer.start();
            btnPlay.setText("⏸");
        }
    }

    private void stop() {
        timer.stop();
        btnPlay.setText("▶");
    }

    private void advance() {
        if (sequence.next(chkLoop.isSelected())) {
            showCurrent();
        } else {
            stop();
        }
    }

    private void step(int delta) {
        sequence.seek(sequence.getIndex() + delta);
        showCurrent();
        if (timer.isRunning()) {
            timer.restart(); // a full interval for the photo stepped to
        }
    }

    private void jump(int index) {
        sequence.seek(index);
        showCurrent();
    }

    /** Shows the current photo (from the cache, or loaded in the background) and moves the map. */
    private void showCurrent() {
        if (sequence.isEmpty()) {
            return;
        }
        int index = sequence.getIndex();
        updatingSlider = true;
        slider.setValue(index);
        updatingSlider = false;
        if (index == shownIndex) {
            return;
        }
        shownIndex = index;
        ImageFile photo = sequence.current();
        GeoPosition position = sequence.mapPosition();
        view.showPosition(position, sequence.hasOwnLocation());
        view.setPhoto(photo, cache.get(photo), index, sequence.size());
        if (!cache.containsKey(photo)) {
            load(photo, true);
        }
        if (sequence.hasNext()) {
            ImageFile next = sequence.get(index + 1);
            if (!cache.containsKey(next)) {
                load(next, false);
            }
        }
    }

    private void load(ImageFile photo, boolean display) {
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return PhotoLoader.load(photo, PHOTO_SIZE);
            }

            @Override
            protected void done() {
                BufferedImage image = null;
                try {
                    image = get();
                } catch (InterruptedException | ExecutionException e) {
                    // shown as "no preview"
                }
                cache.put(photo, image);
                if (photo == sequence.current()) {
                    view.setPhoto(photo, image, sequence.getIndex(), sequence.size());
                }
            }
        }.execute();
    }

    @Override
    public void dispose() {
        timer.stop();
        tileFactory.dispose();
        super.dispose();
    }
}
