package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackPoint;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.map.TrackPainter;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.PhotoLoader;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.cache.LocalCache;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;

import javax.swing.*;
import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Plays the photos back in the order they were taken: the photo fills the window, the time it was taken is shown
 * top left, and a small map bottom right follows the route.
 * <p>
 * Everything that makes up a frame is painted by {@link #getFrameView()}, so frames can also be rendered off
 * screen (e.g. for a later video export) with {@code view.paint(graphics)}.
 */
public class PlaybackWindow extends JFrame {

    private static final int PHOTO_SIZE = 2400;
    private static final int CACHE_SIZE = 6;
    private static final int[] SECONDS = {1, 2, 3, 5, 8, 15};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final PlaybackSequence sequence;
    private final JXMapViewer map = new JXMapViewer();
    /** Created in the constructor, after {@link #map}, which it contains. */
    private final FrameView view;
    private final DefaultTileFactory tileFactory;
    private final TrackPainter routePainter = new TrackPainter();
    private final List<Track> routeTrack;
    private final Map<ImageFile, BufferedImage> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ImageFile, BufferedImage> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private final JButton btnPlay = new JButton("▶");
    private final JSlider slider;
    private final JComboBox<String> cbSpeed = new JComboBox<>();
    private final JCheckBox chkLoop = new JCheckBox("Loop");
    private final Timer timer;
    private boolean updatingSlider;
    private int shownIndex = -1;

    /**
     * @param tileInfo  map layer to use for the inset
     * @param tileCache disk cache shared with the main window
     */
    public PlaybackWindow(Window owner, PlaybackSequence sequence, TileFactoryInfo tileInfo, LocalCache tileCache,
                          String userAgent) {
        super("Playback");
        this.sequence = sequence;
        this.view = new FrameView();
        setIconImages(owner.getIconImages());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        tileFactory = new DefaultTileFactory(tileInfo);
        tileFactory.setUserAgent(userAgent);
        tileFactory.setLocalCache(tileCache);
        tileFactory.setThreadPoolSize(4);
        map.setTileFactory(tileFactory);
        map.setOverlayPainter(new CompoundPainter<>(routePainter, new AttributionPainter()));
        MouseInputListener pan = new PanMouseInputListener(map);
        map.addMouseListener(pan);
        map.addMouseMotionListener(pan);
        map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));
        map.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 160), 2));
        List<TrackPoint> points = sequence.getRoute().stream().map(p -> new TrackPoint(null, p, null)).toList();
        routeTrack = points.isEmpty() ? List.of() : List.of(new Track("route", List.of(points)));

        slider = new JSlider(0, Math.max(0, sequence.size() - 1), 0);
        slider.addChangeListener(e -> {
            if (!updatingSlider) {
                sequence.seek(slider.getValue());
                showCurrent();
            }
        });
        for (int s : SECONDS) {
            cbSpeed.addItem(s + (s == 1 ? " second" : " seconds"));
        }
        cbSpeed.setSelectedIndex(2);
        timer = new Timer(delay(), e -> advance());
        cbSpeed.addActionListener(e -> {
            timer.setDelay(delay());
            timer.setInitialDelay(delay());
        });

        JButton btnPrev = new JButton("⏮");
        JButton btnNext = new JButton("⏭");
        btnPrev.setToolTipText("Previous photo (Left)");
        btnNext.setToolTipText("Next photo (Right)");
        btnPlay.setToolTipText("Play / pause (Space)");
        btnPrev.addActionListener(e -> step(-1));
        btnNext.addActionListener(e -> step(1));
        btnPlay.addActionListener(e -> togglePlay());
        for (JComponent c : new JComponent[]{btnPrev, btnPlay, btnNext, slider, cbSpeed, chkLoop}) {
            c.setFocusable(false); // keep the keyboard shortcuts working
        }

        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(btnPrev);
        left.add(btnPlay);
        left.add(btnNext);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(new JLabel("Each photo:"));
        right.add(cbSpeed);
        right.add(chkLoop);
        controls.add(left, BorderLayout.WEST);
        controls.add(slider, BorderLayout.CENTER);
        controls.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(view, BorderLayout.CENTER);
        content.add(controls, BorderLayout.SOUTH);
        setContentPane(content);
        installKeys(content);

        setSize(1280, 820);
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                fitRoute();
                showCurrent();
            }
        });
    }

    /** The component that paints a whole frame: photo, timestamp and map inset. */
    public JComponent getFrameView() {
        return view;
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
        if (null != position) {
            map.setAddressLocation(position);
        }
        routePainter.set(routeTrack, List.of(), sequence.hasOwnLocation() ? position : null);
        map.setVisible(!routeTrack.isEmpty());
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

    private void fitRoute() {
        List<GeoPosition> route = sequence.getRoute();
        if (route.size() > 1) {
            map.zoomToBestFit(new HashSet<>(route), 0.8);
        } else {
            map.setZoom(4);
        }
    }

    @Override
    public void dispose() {
        timer.stop();
        tileFactory.dispose();
        super.dispose();
    }

    /** Photo, timestamp overlay and map inset, layered. */
    private final class FrameView extends JLayeredPane {
        private final PhotoPanel photoPanel = new PhotoPanel();

        FrameView() {
            setOpaque(true);
            setBackground(new Color(18, 18, 20));
            add(photoPanel, JLayeredPane.DEFAULT_LAYER);
            add(map, JLayeredPane.PALETTE_LAYER);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    layoutLayers();
                }
            });
        }

        void setPhoto(ImageFile photo, BufferedImage image, int index, int total) {
            photoPanel.set(photo, image, index, total);
        }

        private void layoutLayers() {
            photoPanel.setBounds(0, 0, getWidth(), getHeight());
            int w = Math.max(240, getWidth() / 4);
            int h = Math.max(180, w * 3 / 4);
            map.setBounds(getWidth() - w - 16, getHeight() - h - 16, w, h);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    private static final class PhotoPanel extends JComponent {
        private ImageFile photo;
        private BufferedImage image;
        private int index;
        private int total;

        void set(ImageFile photo, BufferedImage image, int index, int total) {
            this.photo = photo;
            this.image = image;
            this.index = index;
            this.total = total;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (null == photo) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (null != image) {
                    double scale = Math.min((double) getWidth() / image.getWidth(),
                            (double) getHeight() / image.getHeight());
                    int w = (int) (image.getWidth() * scale);
                    int h = (int) (image.getHeight() * scale);
                    g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
                } else {
                    g2.setColor(new Color(150, 150, 150));
                    String text = "Loading " + photo.getFile().getName() + "...";
                    g2.drawString(text, (getWidth() - g2.getFontMetrics().stringWidth(text)) / 2, getHeight() / 2);
                }
                paintTimestamp(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintTimestamp(Graphics2D g2) {
            Font base = getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            Font big = base.deriveFont(Font.BOLD, 30f);
            Font small = base.deriveFont(Font.PLAIN, 14f);
            String time = TIME.format(photo.getTaken());
            String date = DATE.format(photo.getTaken());
            String info = (index + 1) + " / " + total + "   " + photo.getFile().getName();
            FontMetrics fb = g2.getFontMetrics(big);
            FontMetrics fs = g2.getFontMetrics(small);
            int w = Math.max(fb.stringWidth(time), Math.max(fs.stringWidth(date), fs.stringWidth(info))) + 28;
            int h = fb.getHeight() + 2 * fs.getHeight() + 18;
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(16, 16, w, h, 14, 14);
            g2.setColor(Color.WHITE);
            g2.setFont(big);
            int y = 16 + 8 + fb.getAscent();
            g2.drawString(time, 30, y);
            g2.setFont(small);
            y += fs.getHeight() + 2;
            g2.drawString(date, 30, y);
            g2.setColor(new Color(200, 200, 200));
            y += fs.getHeight();
            g2.drawString(info, 30, y);
        }
    }
}
