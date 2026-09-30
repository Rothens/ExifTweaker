package me.rothens.gpsexif;

import me.rothens.gpsexif.history.EditHistory;
import me.rothens.gpsexif.history.PhotoWriter;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataBackend;
import me.rothens.gpsexif.model.ExifTableModel;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.model.ImageListRenderer;
import me.rothens.gpsexif.ui.Theme;
import me.rothens.gpsexif.util.ImageOrientation;
import me.rothens.gpsexif.util.PositionUtil;
import me.rothens.gpsexif.util.Settings;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.input.CenterMapListener;
import org.jxmapviewer.input.PanKeyListener;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.viewer.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ExifTweaker {
    private static final String APP_NAME = "ExifTweaker";
    private static final int THUMBNAIL_MAX_SIZE = 800;
    private static final File TILE_CACHE_DIR = new File(System.getProperty("user.home"), ".jxmapviewer2");

    private final JTextField tfFolder = new JTextField();
    private final JButton btnBrowse = new JButton("...");
    private final JButton btnOpen = new JButton("Open");
    private final JList<ImageFile> lFiles = new JList<>(new DefaultListModel<>());
    private final JButton btnSave = new JButton("Save");
    private final JButton btnUndo = new JButton("Undo");
    private final JProgressBar progress = new JProgressBar();
    private final JPanel mainPanel = new JPanel(new BorderLayout(4, 4));
    private final JXMapViewer mapViewer = new JXMapViewer();
    private final ImagePanel pnThumbnail = new ImagePanel();
    private final ExifTableModel exifTableModel = new ExifTableModel();
    private final JTable jtExif = new JTable(exifTableModel);
    private final JComboBox<MapLayer> cbMapType = new JComboBox<>(MapLayer.values());
    private final JTextField tfCoordinate = new JTextField();
    private final JButton btnCoordinate = new JButton("Go!");
    private final JFrame frame;
    private final Settings settings = new Settings(Preferences.userNodeForPackage(ExifTweaker.class));
    private final MetadataBackend backend = new CommonsImagingBackend();
    private final EditHistory history = new EditHistory();
    private final PhotoWriter writer = new PhotoWriter(history, settings::isBackupsEnabled);

    private WaypointPainter<Waypoint> waypointPainter;
    private final Set<Waypoint> waypoints = new HashSet<>();
    private ImageFile selected;
    private SwingWorker<BufferedImage, Void> thumbnailWorker;
    private final Map<MapLayer, DefaultTileFactory> factories = new EnumMap<>(MapLayer.class);

    public ExifTweaker(JFrame frame) {
        this.frame = frame;
        layoutComponents();
        initMap();

        lFiles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lFiles.setCellRenderer(new ImageListRenderer());
        lFiles.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                elementSelected();
            }
        });

        btnOpen.addActionListener(e -> openFolder());
        tfFolder.addActionListener(e -> openFolder());
        btnSave.addActionListener(e -> saveSelected());
        btnBrowse.addActionListener(e -> browse());
        btnCoordinate.addActionListener(e -> goToCoordinate());
        tfCoordinate.addActionListener(e -> goToCoordinate());
        cbMapType.addActionListener(e -> setMapLayer((MapLayer) cbMapType.getSelectedItem()));

        btnUndo.addActionListener(e -> undo());
        history.addChangeListener(this::updateUndo);
        updateUndo();

        tfFolder.setText(settings.getLastDirectory());
        tfCoordinate.setToolTipText("Latitude;Longitude in decimal degrees, or e.g. 47°29'52\"N 19°2'24\"E");
        btnSave.setEnabled(false);
    }

    private void layoutComponents() {
        JPanel top = new JPanel(new BorderLayout(4, 0));
        JPanel topButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        topButtons.add(btnBrowse);
        topButtons.add(btnOpen);
        top.add(tfFolder, BorderLayout.CENTER);
        top.add(topButtons, BorderLayout.EAST);

        JScrollPane fileScroll = new JScrollPane(lFiles);
        fileScroll.setPreferredSize(new Dimension(220, 0));

        pnThumbnail.setPreferredSize(new Dimension(300, 300));
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pnThumbnail, new JScrollPane(jtExif));
        rightSplit.setResizeWeight(0.6);
        rightSplit.setPreferredSize(new Dimension(300, 0));

        JSplitPane mapSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mapViewer, rightSplit);
        mapSplit.setResizeWeight(1.0);
        mapSplit.setDividerSize(5);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileScroll, mapSplit);
        centerSplit.setDividerSize(5);

        JPanel coordinatePanel = new JPanel(new BorderLayout(4, 0));
        coordinatePanel.add(cbMapType, BorderLayout.WEST);
        coordinatePanel.add(tfCoordinate, BorderLayout.CENTER);
        coordinatePanel.add(btnCoordinate, BorderLayout.EAST);

        JPanel progressPanel = new JPanel(new BorderLayout(4, 0));
        progressPanel.add(progress, BorderLayout.CENTER);
        JPanel saveButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        saveButtons.add(btnUndo);
        saveButtons.add(btnSave);
        progressPanel.add(saveButtons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 4));
        bottom.add(progressPanel);
        bottom.add(coordinatePanel);

        mainPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        mainPanel.add(top, BorderLayout.NORTH);
        mainPanel.add(centerSplit, BorderLayout.CENTER);
        mainPanel.add(bottom, BorderLayout.SOUTH);
    }

    private void setButtons(boolean enabled) {
        btnOpen.setEnabled(enabled);
        btnBrowse.setEnabled(enabled);
        btnSave.setEnabled(enabled && selected != null);
    }

    private void browse() {
        JFileChooser jfc = new JFileChooser(tfFolder.getText());
        jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (jfc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            tfFolder.setText(jfc.getSelectedFile().toString());
            openFolder();
        }
    }

    private void openFolder() {
        String dir = tfFolder.getText();
        File[] files = new File(dir).listFiles(f -> f.isFile() && backend.canRead(f.toPath()));
        if (files == null) {
            showError("Couldn't open folder:\n" + dir);
            return;
        }
        Arrays.sort(files);
        settings.setLastDirectory(dir);

        setButtons(false);
        progress.setValue(0);
        progress.setMaximum(files.length);
        new SwingWorker<DefaultListModel<ImageFile>, Integer>() {
            @Override
            protected DefaultListModel<ImageFile> doInBackground() {
                List<ImageFile> loaded = new ArrayList<>();
                for (File f : files) {
                    loaded.add(new ImageFile(f, backend));
                    publish(loaded.size());
                }
                DefaultListModel<ImageFile> model = new DefaultListModel<>();
                model.addAll(loaded);
                return model;
            }

            @Override
            protected void process(List<Integer> chunks) {
                progress.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                try {
                    lFiles.setModel(get());
                } catch (InterruptedException | ExecutionException e) {
                    showError("Error while opening folder:\n" + e.getMessage());
                }
                setButtons(true);
            }
        }.execute();
    }

    private void saveSelected() {
        if (null == selected) {
            return;
        }
        GeoPosition position = null;
        for (Waypoint w : waypoints) {
            if (w instanceof SelectionWaypoint) {
                position = w.getPosition();
            }
        }
        if (null == position) {
            showError("Right-click on the map or enter a coordinate first.");
            return;
        }
        try {
            writer.savePosition(selected, position);
            exifTableModel.setData(selected.getExifData());
            lFiles.repaint();
        } catch (IOException | RuntimeException e) {
            showError("Couldn't save " + selected.getFile().getName() + ":\n" + e.getMessage());
        }
    }

    private void undo() {
        List<Path> restored = List.of();
        try {
            restored = history.undo();
        } catch (IOException e) {
            showError(e.getMessage());
        }
        reloadFiles(restored);
    }

    /** Re-reads the metadata of the given files if they're in the current list, and refreshes the views. */
    private void reloadFiles(Collection<Path> paths) {
        ListModel<ImageFile> model = lFiles.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            ImageFile image = model.getElementAt(i);
            if (paths.contains(image.getPath().toAbsolutePath().normalize())) {
                image.reload();
            }
        }
        lFiles.repaint();
        elementSelected();
    }

    private void updateUndo() {
        btnUndo.setEnabled(history.canUndo());
        btnUndo.setToolTipText(history.canUndo() ? "Undo: " + history.getUndoDescription() : null);
    }

    private void goToCoordinate() {
        try {
            GeoPosition position = PositionUtil.parse(tfCoordinate.getText());
            selectPosition(position);
            mapViewer.setAddressLocation(position);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, APP_NAME, JOptionPane.WARNING_MESSAGE);
    }

    public static void main(String[] args) {
        // macOS: native window title bar colour that follows the system appearance
        System.setProperty("apple.awt.application.appearance", "system");
        Theme theme = new Settings(Preferences.userNodeForPackage(ExifTweaker.class)).getTheme();
        SwingUtilities.invokeLater(() -> {
            theme.install();
            JFrame frame = new JFrame(APP_NAME);
            ExifTweaker app = new ExifTweaker(frame);
            frame.setContentPane(app.mainPanel);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Runtime.getRuntime().addShutdownHook(new Thread(app.history::close));
            frame.setSize(1200, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private void elementSelected() {
        selected = lFiles.getSelectedValue();
        btnSave.setEnabled(null != selected && btnOpen.isEnabled());
        waypoints.clear();
        pnThumbnail.setImage(null);
        if (null == selected) {
            exifTableModel.clear();
            tfCoordinate.setText("");
        } else {
            loadThumbnail(selected);
            exifTableModel.setData(selected.getExifData());
            if (selected.hasExifGPS()) {
                mapViewer.setAddressLocation(selected.getGp());
                waypoints.add(new DefaultWaypoint(selected.getGp()));
                tfCoordinate.setText(PositionUtil.getPositionString(selected.getGp()));
            } else {
                tfCoordinate.setText("");
            }
        }
        waypointPainter.setWaypoints(waypoints);
        mapViewer.repaint();
    }

    private void loadThumbnail(ImageFile image) {
        if (null != thumbnailWorker) {
            thumbnailWorker.cancel(true);
        }
        thumbnailWorker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() throws IOException {
                BufferedImage thumbnail = readSubsampled(image.getFile(), THUMBNAIL_MAX_SIZE);
                return ImageOrientation.apply(thumbnail, image.getOrientation());
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    pnThumbnail.setImage(get());
                } catch (InterruptedException | ExecutionException e) {
                    pnThumbnail.setImage(null);
                }
            }
        };
        thumbnailWorker.execute();
    }

    /** Decodes only every n-th pixel so large photos don't have to be fully loaded into memory. */
    private static BufferedImage readSubsampled(File file, int maxSize) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, longest / maxSize);
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private void selectPosition(GeoPosition position) {
        waypoints.removeIf(w -> w instanceof SelectionWaypoint);
        waypoints.add(new SelectionWaypoint(position));
        waypointPainter.setWaypoints(waypoints);
        tfCoordinate.setText(PositionUtil.getPositionString(position));
        mapViewer.repaint();
    }

    private void setMapLayer(MapLayer layer) {
        DefaultTileFactory factory = factories.get(layer);
        // LocalResponseCache is JVM-global and only caches URLs under one base URL, so point it at the active layer
        LocalResponseCache.installResponseCache(factory.getInfo().getBaseURL(), TILE_CACHE_DIR, false);
        mapViewer.setTileFactory(factory);
        settings.setMapLayer(layer);
    }

    private void initMap() {
        for (MapLayer layer : MapLayer.values()) {
            DefaultTileFactory tf = new DefaultTileFactory(layer.createInfo());
            tf.setThreadPoolSize(8);
            // The OSM tile usage policy requires an identifying User-Agent
            tf.setUserAgent(APP_NAME + " (https://github.com/rothens/ExifTweaker)");
            factories.put(layer, tf);
        }
        MapLayer layer = settings.getMapLayer();
        cbMapType.setSelectedItem(layer);
        setMapLayer(layer);

        GeoPosition tokyo = new GeoPosition(35.68, 139.71);
        mapViewer.setZoom(5);
        mapViewer.setAddressLocation(tokyo);

        waypointPainter = new WaypointPainter<>();
        waypointPainter.setRenderer(new SelectionWaypointRenderer());
        waypointPainter.setWaypoints(waypoints);
        mapViewer.setOverlayPainter(new CompoundPainter<>(waypointPainter, new AttributionPainter()));

        MouseInputListener mia = new PanMouseInputListener(mapViewer);
        mapViewer.addMouseListener(mia);
        mapViewer.addMouseMotionListener(mia);
        mapViewer.addMouseListener(new CenterMapListener(mapViewer));
        mapViewer.addMouseWheelListener(new ZoomMouseWheelListenerCursor(mapViewer));
        mapViewer.addKeyListener(new PanKeyListener(mapViewer));
        mapViewer.addMouseListener(new SelectionAdapter(mapViewer, this::selectPosition));
    }
}
