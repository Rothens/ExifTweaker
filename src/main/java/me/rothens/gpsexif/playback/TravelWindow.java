package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.gpx.GpxParser;
import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackPoint;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.PhotoLoader;
import me.rothens.gpsexif.util.Settings;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.cache.LocalCache;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;

import javax.swing.*;
import javax.swing.event.MouseInputListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final List<ImageFile> photos;
    private final JComboBox<String> cbZone;
    private ClockZone clock;
    private final List<Track> tracks = new ArrayList<>();
    private TravelTimeline timeline;

    private final DefaultTileFactory tileFactory;
    private final JXMapViewer fullMap = new JXMapViewer();
    private final JXMapViewer insetMap = new JXMapViewer();
    private final RoutePainter routePainter = new RoutePainter();
    private final PhotoLayer photoLayer = new PhotoLayer();
    private final JLayeredPane view = new JLayeredPane();
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
    private final JLabel lblRoute = new JLabel();
    private final JLabel lblSummary = new JLabel(" ");
    private final JButton btnPlay = new JButton("▶");
    private final JSlider slider = new JSlider(0, 1000, 0);
    private final JLabel lblTime = new JLabel("0:00 / 0:00");
    private final Timer timer;

    private double videoTime;
    private long lastTick;
    private boolean updatingSlider;
    private int mapSlot = -1;

    /**
     * @param tracks GPX tracks the marker should follow; empty for straight lines between the photos
     */
    public TravelWindow(Window owner, List<ImageFile> photos, Settings settings, List<Track> tracks,
                        TileFactoryInfo tileInfo, LocalCache tileCache, String userAgent) {
        super("Travel mode");
        this.photos = List.copyOf(photos);
        this.clock = new ClockZone(settings.getCameraZone(), settings.getDisplayZone());
        cbZone = ClockZone.createChooser(clock.getCameraZone(), clock.getDisplayZone());
        cbZone.addActionListener(e -> {
            clock = new ClockZone(clock.getCameraZone(), ClockZone.selected(cbZone));
            settings.setDisplayZone(clock.getDisplayZone());
            view.repaint();
        });
        this.tracks.addAll(tracks);
        setIconImages(owner.getIconImages());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        tileFactory = new DefaultTileFactory(tileInfo);
        tileFactory.setUserAgent(userAgent);
        tileFactory.setLocalCache(tileCache);
        tileFactory.setThreadPoolSize(4);
        for (JXMapViewer map : new JXMapViewer[]{fullMap, insetMap}) {
            map.setTileFactory(tileFactory);
            map.setOverlayPainter(new CompoundPainter<>(routePainter, new AttributionPainter()));
            MouseInputListener pan = new PanMouseInputListener(map);
            map.addMouseListener(pan);
            map.addMouseMotionListener(pan);
            map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));
        }
        insetMap.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 160), 2));

        view.setOpaque(true);
        view.setBackground(new Color(18, 18, 20));
        view.add(fullMap, JLayeredPane.DEFAULT_LAYER);
        view.add(photoLayer, JLayeredPane.PALETTE_LAYER);
        view.add(insetMap, JLayeredPane.MODAL_LAYER);
        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutView();
            }
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
            }
        });
    }

    private JPanel createSettingsPanel() {
        JButton btnLoad = new JButton("Load GPX...");
        btnLoad.setToolTipText("Let the marker follow the track you recorded instead of straight lines");
        btnLoad.addActionListener(e -> loadGpx());
        JButton btnStraight = new JButton("Straight lines");
        btnStraight.setToolTipText("Forget the GPX track");
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

    private void loadGpx() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("GPX tracks (*.gpx)", "gpx"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<Track> loaded = new ArrayList<>();
        for (File f : chooser.getSelectedFiles()) {
            try {
                loaded.add(GpxParser.parse(f.toPath()));
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
                chkJourney.isSelected() ? Duration.ofSeconds((Integer) spJourney.getValue()) : null);
        timeline = new TravelTimeline(photos, clock::instant, tracks, s);
        spStayMinutes.setEnabled(chkSqueeze.isSelected());
        spStayKm.setEnabled(chkSqueeze.isSelected());
        spJourney.setEnabled(chkJourney.isSelected());
        lblRoute.setText(timeline.usesTrack() ? "Route: GPX track (" + tracks.size()
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
        routePainter.route = route;
        List<GeoPosition> all = route.stream().flatMap(List::stream).toList();
        if (all.size() > 1) {
            insetMap.zoomToBestFit(new HashSet<>(all), 0.85);
        } else if (all.size() == 1) {
            insetMap.setZoom(5);
            insetMap.setAddressLocation(all.get(0));
        }
        mapSlot = -1;
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

        routePainter.marker = frame.marker();
        routePainter.photoAt = frame.photoAt();
        if (frame.slot() >= 0 && frame.slot() != mapSlot && (null == frame.from() || null == frame.to())) {
            // A travel stretch starts: frame it on the full-screen map
            fitFullMap(frame.slot());
            mapSlot = frame.slot();
        }
        insetMap.setVisible(null != frame.visible() && !timeline.isEmpty());
        photoLayer.frame = frame;
        for (ImageFile p : new ImageFile[]{frame.from(), frame.to()}) {
            request(p);
        }
        // Load ahead: the next two shown photos
        List<TravelTimeline.Slot> slots = timeline.getSlots();
        for (int i = frame.slot() + 1; i < Math.min(slots.size(), frame.slot() + 3); i++) {
            request(slots.get(i).stop().photo());
        }
        view.repaint();
    }

    private void fitFullMap(int slot) {
        List<TravelTimeline.Slot> slots = timeline.getSlots();
        Instant from = slots.get(slot).stop().time();
        Instant to = slot + 1 < slots.size() ? slots.get(slot + 1).stop().time() : from;
        Set<GeoPosition> points = new HashSet<>();
        for (int i = 0; i <= 20; i++) {
            GeoPosition p = timeline.markerAt(from.plusMillis(Duration.between(from, to).toMillis() * i / 20));
            if (null != p) {
                points.add(p);
            }
        }
        if (points.size() > 1) {
            fullMap.zoomToBestFit(points, 0.7);
        } else if (points.size() == 1) {
            fullMap.setZoom(Math.min(fullMap.getZoom(), 6));
            fullMap.setAddressLocation(points.iterator().next());
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

    private void layoutView() {
        fullMap.setBounds(0, 0, view.getWidth(), view.getHeight());
        photoLayer.setBounds(0, 0, view.getWidth(), view.getHeight());
        int w = Math.max(240, view.getWidth() / 4);
        int h = Math.max(180, w * 3 / 4);
        insetMap.setBounds(view.getWidth() - w - 16, view.getHeight() - h - 16, w, h);
    }

    private static String clock(double seconds) {
        long s = Math.round(Math.floor(seconds));
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** The current frame as an image of the given size: for a later video export. */
    public BufferedImage renderFrame(int width, int height) {
        Dimension old = view.getSize();
        view.setSize(width, height);
        layoutView();
        view.doLayout();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            view.paint(g);
        } finally {
            g.dispose();
            view.setSize(old);
            layoutView();
        }
        return image;
    }

    @Override
    public void dispose() {
        timer.stop();
        tileFactory.dispose();
        super.dispose();
    }

    /** Route line, the moving marker and the small dot at the shown photo, on both maps. */
    private static final class RoutePainter implements Painter<JXMapViewer> {
        volatile List<List<GeoPosition>> route = List.of();
        volatile GeoPosition marker;
        volatile GeoPosition photoAt;

        @Override
        public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Rectangle viewport = map.getViewportBounds();
                g2.translate(-viewport.x, -viewport.y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int zoom = map.getZoom();
                for (List<GeoPosition> segment : route) {
                    Path2D path = new Path2D.Double();
                    boolean first = true;
                    for (GeoPosition p : segment) {
                        Point2D px = map.getTileFactory().geoToPixel(p, zoom);
                        if (first) {
                            path.moveTo(px.getX(), px.getY());
                            first = false;
                        } else {
                            path.lineTo(px.getX(), px.getY());
                        }
                    }
                    g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(0, 0, 0, 90));
                    g2.draw(path);
                    g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(230, 90, 20, 200));
                    g2.draw(path);
                }
                if (null != photoAt) {
                    dot(g2, map.getTileFactory().geoToPixel(photoAt, zoom), 4, new Color(40, 110, 230));
                }
                if (null != marker) {
                    dot(g2, map.getTileFactory().geoToPixel(marker, zoom), 8, new Color(230, 60, 40));
                }
            } finally {
                g2.dispose();
            }
        }

        private static void dot(Graphics2D g, Point2D p, double r, Color color) {
            Ellipse2D circle = new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r);
            g.setColor(color);
            g.fill(circle);
            g.setStroke(new BasicStroke(2));
            g.setColor(Color.WHITE);
            g.draw(circle);
        }
    }

    /** Photos cross-fading over the map, the travel clock and the stay caption. */
    private final class PhotoLayer extends JComponent {
        TravelTimeline.Frame frame;

        @Override
        protected void paintComponent(Graphics g) {
            if (null == frame || null == frame.time()) {
                g.setColor(new Color(18, 18, 20));
                g.fillRect(0, 0, getWidth(), getHeight());
                if (null != timeline && timeline.isEmpty()) {
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString("None of these photos has a date.", 20, 30);
                }
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (null != frame.from()) {
                    drawPhoto(g2, frame.from(), null == frame.to() ? 1 - frame.alpha() : 1);
                }
                if (null != frame.to() && frame.to() != frame.from()) {
                    drawPhoto(g2, frame.to(), frame.alpha());
                }
                paintClock(g2);
            } finally {
                g2.dispose();
            }
        }

        private void drawPhoto(Graphics2D g2, ImageFile photo, double opacity) {
            if (opacity <= 0) {
                return;
            }
            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, opacity)));
            g2.setColor(new Color(18, 18, 20));
            g2.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage image = cache.get(photo);
            if (null != image) {
                double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale);
                int h = (int) (image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            g2.setComposite(old);
        }

        private void paintClock(Graphics2D g2) {
            Font base = getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            Font big = base.deriveFont(Font.BOLD, 30f);
            Font small = base.deriveFont(Font.PLAIN, 14f);
            var local = clock.local(frame.time());
            String time = TIME.format(local);
            String offset = clock.offsetLabel(frame.time());
            String date = DATE.format(local) + (null == offset ? "" : "  ·  " + offset);
            FontMetrics fb = g2.getFontMetrics(big);
            FontMetrics fs = g2.getFontMetrics(small);
            String caption = frame.caption();
            int captionWidth = null == caption ? 0 : g2.getFontMetrics(big).stringWidth(caption) + 24;
            int w = Math.max(fb.stringWidth(time) + captionWidth, fs.stringWidth(date)) + 28;
            int h = fb.getHeight() + fs.getHeight() + 14;
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(16, 16, w, h, 14, 14);
            g2.setColor(Color.WHITE);
            g2.setFont(big);
            int y = 16 + 6 + fb.getAscent();
            g2.drawString(time, 30, y);
            if (null != caption) {
                g2.setColor(new Color(255, 200, 90));
                g2.drawString(caption, 30 + fb.stringWidth(time) + 24, y);
                g2.setColor(Color.WHITE);
            }
            g2.setFont(small);
            g2.drawString(date, 30, y + fs.getHeight() + 2);
        }
    }
}
