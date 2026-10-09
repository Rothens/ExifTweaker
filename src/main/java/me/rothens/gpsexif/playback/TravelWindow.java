package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.gpx.TrackReader;
import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackPoint;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.PhotoLoader;
import me.rothens.gpsexif.ui.GpxFileChooser;
import me.rothens.gpsexif.ui.VideoExportDialog;
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
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Travel mode: plays the trip as a short film. A marker travels along the route; when it reaches a photo, the
 * photo fades in, and during longer travel the map takes over the screen. See {@link TravelTimeline}.
 */
public class TravelWindow extends JFrame {

    private static final int PHOTO_SIZE = 1920;
    private static final int FPS = 30;

    private final List<ImageFile> photos;
    private final Settings settings;
    private final JComboBox<String> cbZone;
    private ClockZone clock;
    private final List<Track> tracks = new ArrayList<>();
    private TravelTimeline timeline;

    private final DefaultTileFactory tileFactory;
    private final TravelView view;
    private List<List<GeoPosition>> route = List.of();
    private final Map<ImageFile, BufferedImage> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ImageFile, BufferedImage> eldest) {
            return size() > 10;
        }
    };
    private final Set<ImageFile> loading = new HashSet<>();

    private final JSpinner spLength = new JSpinner(new SpinnerNumberModel(180, 10, 3600, 10));
    private final JSpinner spMinPhoto = new JSpinner(new SpinnerNumberModel(2.5, 0.5, 30.0, 0.5));
    private final JSpinner spFade = new JSpinner(new SpinnerNumberModel(0.6, 0.0, 3.0, 0.1));
    private final JCheckBox chkSqueeze = new JCheckBox("Squeeze stops longer than", true);
    private final JSpinner spStayMinutes = new JSpinner(new SpinnerNumberModel(60, 5, 24 * 60, 5));
    private final JSpinner spStayKm = new JSpinner(new SpinnerNumberModel(2.0, 0.1, 100.0, 0.5));
    private final JCheckBox chkJourney = new JCheckBox("Travel at most", true);
    private final JSpinner spJourney = new JSpinner(new SpinnerNumberModel(8, 3, 120, 1));
    private final JCheckBox chkFullMap = new JCheckBox("Full-screen map between photos");
    private final JCheckBox chkFollow = new JCheckBox("Small map follows the marker");
    private final JCheckBox chkTransfers = new JCheckBox("Show from → to");
    private final JLabel lblRoute = new JLabel();
    private final JLabel lblSummary = new JLabel(" ");
    private final JButton btnPlay = new JButton("▶");
    private final JSlider slider = new JSlider(0, 1000, 0);
    private final JLabel lblTime = new JLabel("0:00 / 0:00");
    private final Timer timer;

    private double videoTime;
    private long lastTick;
    private boolean updatingSlider;

    /**
     * @param tracks GPX tracks the marker should follow; empty for straight lines between the photos
     */
    public TravelWindow(Window owner, List<ImageFile> photos, Settings settings, List<Track> tracks,
                        TileFactoryInfo tileInfo, LocalCache tileCache, String userAgent) {
        super("Travel mode");
        this.photos = List.copyOf(photos);
        this.settings = settings;
        this.clock = new ClockZone(settings.getCameraZone(), settings.getDisplayZone());
        cbZone = ClockZone.createChooser(clock.getCameraZone(), clock.getDisplayZone());
        this.tracks.addAll(tracks);
        setIconImages(owner.getIconImages());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        tileFactory = new DefaultTileFactory(tileInfo);
        tileFactory.setUserAgent(userAgent);
        tileFactory.setLocalCache(tileCache);
        tileFactory.setThreadPoolSize(4);
        view = new TravelView(tileFactory, cache::get, clock);
        view.setInsetFraction(settings.getTravelInset());
        view.enableInsetResize(settings::setTravelInset);
        // The zoom chosen on the small map (mouse wheel) is kept while it follows the marker
        view.getMaps()[1].addPropertyChangeListener("zoom", e -> {
            if (chkFollow.isSelected()) {
                settings.setTravelInsetZoom(view.getInsetZoom());
            }
        });
        for (JXMapViewer map : view.getMaps()) {
            MouseInputListener pan = new PanMouseInputListener(map);
            map.addMouseListener(pan);
            map.addMouseMotionListener(pan);
            map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));
        }
        cbZone.addActionListener(e -> {
            clock = new ClockZone(clock.getCameraZone(), ClockZone.selected(cbZone));
            settings.setDisplayZone(clock.getDisplayZone());
            view.setClock(clock);
        });

        timer = new Timer(1000 / FPS, e -> tick());

        JPanel settingsPanel = createSettingsPanel();
        JPanel controls = createControls();
        JPanel content = new JPanel(new BorderLayout());
        content.add(settingsPanel, BorderLayout.NORTH);
        content.add(view, BorderLayout.CENTER);
        content.add(controls, BorderLayout.SOUTH);
        setContentPane(content);
        installKeys(content);

        setSize(1280, 860);
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                rebuild();
                if (chkFollow.isSelected()) {
                    view.setFollowMarker(true, settings.getTravelInsetZoom());
                    render();
                }
            }
        });
    }

    private JPanel createSettingsPanel() {
        JButton btnLoad = new JButton("Load track...");
        btnLoad.setToolTipText("Let the marker follow the track you recorded instead of straight lines");
        btnLoad.addActionListener(e -> loadGpx());
        JButton btnStraight = new JButton("Straight lines");
        btnStraight.setToolTipText("Forget the track");
        btnStraight.addActionListener(e -> {
            tracks.clear();
            rebuild();
        });
        spLength.setToolTipText("Length of the whole film, in seconds");
        spMinPhoto.setToolTipText("Each shown photo stays at least this long; photos in between are skipped");
        spStayKm.setToolTipText("A long gap where you moved less than this is a stop (e.g. a night) and is squeezed;"
                + " otherwise it's travel and shown on the map");
        String journeyTip = "Travel between places is shown at most this long; the time saved goes to the photos";
        chkJourney.setToolTipText(journeyTip);
        spJourney.setToolTipText(journeyTip);
        for (JComponent c : new JComponent[]{spLength, spMinPhoto, spFade, spStayMinutes, spStayKm, spJourney}) {
            ((JSpinner) c).addChangeListener(e -> rebuild());
        }
        chkSqueeze.addActionListener(e -> rebuild());
        chkJourney.addActionListener(e -> rebuild());
        chkFullMap.setSelected(settings.isTravelFullMap());
        chkFullMap.setToolTipText("Off: the last photo stays up while the marker travels on the small map");
        chkFullMap.addActionListener(e -> {
            settings.setTravelFullMap(chkFullMap.isSelected());
            rebuild();
        });
        chkTransfers.setSelected(settings.isTravelTransfers());
        view.setShowTransfers(chkTransfers.isSelected());
        chkTransfers.setToolTipText("While travelling, show where from and where to, e.g. Osaka, Namba → Tokyo, Chiyoda"
                + " (from the photos' place names)");
        chkTransfers.addActionListener(e -> {
            settings.setTravelTransfers(chkTransfers.isSelected());
            view.setShowTransfers(chkTransfers.isSelected());
        });
        chkFollow.setSelected(settings.isTravelFollow());
        chkFollow.setToolTipText("<html>On: the small map stays at the zoom you choose with the mouse wheel and keeps"
                + " the marker in the middle.<br>Off: it shows the whole route.</html>");
        chkFollow.addActionListener(e -> {
            settings.setTravelFollow(chkFollow.isSelected());
            view.setFollowMarker(chkFollow.isSelected(), settings.getTravelInsetZoom());
            render();
        });

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.add(new JLabel("Film length (s):"));
        row1.add(spLength);
        row1.add(new JLabel("  Each photo at least (s):"));
        row1.add(spMinPhoto);
        row1.add(new JLabel("  Cross-fade (s):"));
        row1.add(spFade);
        row1.add(new JLabel("   "));
        row1.add(chkSqueeze);
        row1.add(spStayMinutes);
        row1.add(new JLabel("min within"));
        row1.add(spStayKm);
        row1.add(new JLabel("km"));
        row1.add(new JLabel("   "));
        row1.add(chkJourney);
        row1.add(spJourney);
        row1.add(new JLabel("s"));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row2.add(lblRoute);
        row2.add(btnLoad);
        row2.add(btnStraight);
        row2.add(new JLabel("   "));
        row2.add(chkFullMap);
        row2.add(chkFollow);
        row2.add(chkTransfers);
        row2.add(new JLabel("   "));
        row2.add(lblSummary);
        JPanel panel = new JPanel(new GridLayout(2, 1));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(row1);
        panel.add(row2);
        return panel;
    }

    private JPanel createControls() {
        btnPlay.setToolTipText("Play / pause (Space)");
        btnPlay.addActionListener(e -> togglePlay());
        slider.addChangeListener(e -> {
            if (!updatingSlider && null != timeline) {
                videoTime = timeline.getLength() * slider.getValue() / 1000.0;
                render();
            }
        });
        for (JComponent c : new JComponent[]{btnPlay, slider, cbZone}) {
            c.setFocusable(false);
        }
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        controls.add(btnPlay, BorderLayout.WEST);
        controls.add(slider, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(lblTime);
        right.add(new JLabel("   Times in:"));
        right.add(cbZone);
        JButton btnExport = new JButton("Export video...");
        btnExport.setToolTipText("Save the film as an MP4 video");
        btnExport.setFocusable(false);
        btnExport.addActionListener(e -> exportVideo());
        right.add(new JLabel("  "));
        right.add(btnExport);
        controls.add(right, BorderLayout.EAST);
        return controls;
    }

    private void installKeys(JComponent root) {
        bind(root, KeyEvent.VK_SPACE, "toggle", this::togglePlay);
        bind(root, KeyEvent.VK_HOME, "start", () -> seek(0));
        bind(root, KeyEvent.VK_LEFT, "back", () -> seek(videoTime - 5));
        bind(root, KeyEvent.VK_RIGHT, "forward", () -> seek(videoTime + 5));
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

    private void exportVideo() {
        if (null == timeline || timeline.isEmpty()) {
            return;
        }
        if (timer.isRunning()) {
            togglePlay();
        }
        TravelTimeline film = timeline;
        List<List<GeoPosition>> filmRoute = route;
        ClockZone filmClock = clock;
        double inset = view.getInsetFraction();
        boolean follow = chkFollow.isSelected();
        int insetZoom = view.getInsetZoom();
        boolean transfers = chkTransfers.isSelected();
        new VideoExportDialog(this, settings, defaultVideoName(photos, "travel"), null, film::getLength,
                f -> new TravelFrames(film, filmRoute, filmClock, tileFactory, f.width(), f.height(), f.fps(), inset,
                        follow, insetZoom, transfers))
                .showDialog();
    }

    /** "Holiday 2026 - travel": the photos' folder name and what the video is. */
    static String defaultVideoName(List<ImageFile> photos, String what) {
        File dir = photos.isEmpty() ? null : photos.get(0).getFile().getAbsoluteFile().getParentFile();
        return null == dir || null == dir.getName() || dir.getName().isEmpty() ? what : dir.getName() + " - " + what;
    }

    private void loadGpx() {
        List<File> files = GpxFileChooser.choose(this, photos, settings);
        if (files.isEmpty()) {
            return;
        }
        List<Track> loaded = new ArrayList<>();
        for (File f : files) {
            try {
                loaded.add(TrackReader.read(f.toPath(), TrackReader.around(
                        photos.stream().map(ImageFile::getTaken).toList(), Duration.ofDays(3))));
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), getTitle(), JOptionPane.WARNING_MESSAGE);
            }
        }
        if (!loaded.isEmpty()) {
            tracks.clear();
            tracks.addAll(loaded);
            rebuild();
        }
    }

    /** Recomputes the timeline after a setting changed, keeping the relative position. */
    public void rebuild() {
        double fraction = null == timeline || timeline.getLength() <= 0 ? 0 : videoTime / timeline.getLength();
        TravelTimeline.Settings s = new TravelTimeline.Settings(
                Duration.ofSeconds((Integer) spLength.getValue()),
                Duration.ofMillis(Math.round((Double) spMinPhoto.getValue() * 1000)),
                Duration.ofMillis(Math.round((Double) spFade.getValue() * 1000)),
                chkSqueeze.isSelected(),
                Duration.ofMinutes((Integer) spStayMinutes.getValue()),
                (Double) spStayKm.getValue() * 1000,
                Duration.ofSeconds(2),
                chkJourney.isSelected() ? Duration.ofSeconds((Integer) spJourney.getValue()) : null,
                chkFullMap.isSelected());
        timeline = new TravelTimeline(photos, clock::instant, tracks, s);
        spStayMinutes.setEnabled(chkSqueeze.isSelected());
        spStayKm.setEnabled(chkSqueeze.isSelected());
        spJourney.setEnabled(chkJourney.isSelected());
        lblRoute.setText(timeline.usesTrack() ? "Route: recorded track (" + tracks.size()
                + (tracks.size() == 1 ? " file)" : " files)") : "Route: straight lines between the photos");
        lblSummary.setText(timeline.summary());

        List<List<GeoPosition>> route = new ArrayList<>();
        if (timeline.usesTrack()) {
            for (Track t : tracks) {
                for (List<TrackPoint> segment : t.segments()) {
                    route.add(segment.stream().map(TrackPoint::position).toList());
                }
            }
        } else {
            route.add(timeline.getStraightRoute());
        }
        this.route = route;
        view.setTimeline(timeline, route);
        videoTime = fraction * timeline.getLength();
        render();
    }

    public void togglePlay() {
        if (null == timeline || timeline.isEmpty()) {
            return;
        }
        if (timer.isRunning()) {
            timer.stop();
            btnPlay.setText("▶");
        } else {
            if (videoTime >= timeline.getLength()) {
                videoTime = 0;
            }
            lastTick = System.nanoTime();
            timer.start();
            btnPlay.setText("⏸");
        }
    }

    public boolean isPlaying() {
        return timer.isRunning();
    }

    /** Current video time in seconds. */
    public double getVideoTime() {
        return videoTime;
    }

    private void seek(double v) {
        if (null != timeline) {
            videoTime = Math.max(0, Math.min(timeline.getLength(), v));
            render();
        }
    }

    private void tick() {
        long now = System.nanoTime();
        videoTime += (now - lastTick) / 1e9;
        lastTick = now;
        if (videoTime >= timeline.getLength()) {
            videoTime = timeline.getLength();
            timer.stop();
            btnPlay.setText("▶");
        }
        render();
    }

    /** Shows the frame at the current video time. */
    private void render() {
        if (null == timeline) {
            return;
        }
        TravelTimeline.Frame frame = timeline.frameAt(videoTime);
        updatingSlider = true;
        slider.setValue(timeline.getLength() <= 0 ? 0 : (int) Math.round(1000 * videoTime / timeline.getLength()));
        updatingSlider = false;
        lblTime.setText(clock(videoTime) + " / " + clock(timeline.getLength()));

        view.show(frame);
        for (ImageFile p : new ImageFile[]{frame.from(), frame.to()}) {
            request(p);
        }
        // Load ahead: the next two shown photos
        List<TravelTimeline.Slot> slots = timeline.getSlots();
        for (int i = frame.slot() + 1; i < Math.min(slots.size(), frame.slot() + 3); i++) {
            request(slots.get(i).stop().photo());
        }
    }

    private void request(ImageFile photo) {
        if (null == photo || cache.containsKey(photo) || loading.contains(photo)) {
            return;
        }
        loading.add(photo);
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return PhotoLoader.load(photo, PHOTO_SIZE);
            }

            @Override
            protected void done() {
                loading.remove(photo);
                try {
                    cache.put(photo, get());
                } catch (InterruptedException | ExecutionException e) {
                    cache.put(photo, null);
                }
                view.repaint();
            }
        }.execute();
    }

    private static String clock(double seconds) {
        long s = Math.round(Math.floor(seconds));
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public void dispose() {
        timer.stop();
        tileFactory.dispose();
        super.dispose();
    }

}
