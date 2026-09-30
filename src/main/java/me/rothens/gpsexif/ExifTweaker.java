package me.rothens.gpsexif;

import me.rothens.gpsexif.history.EditHistory;
import me.rothens.gpsexif.history.PhotoWriter;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.map.PlaceSearch;
import me.rothens.gpsexif.map.TileDiskCache;
import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataBackend;
import me.rothens.gpsexif.model.ExifTableModel;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.model.ImageListModel;
import me.rothens.gpsexif.model.ImageListRenderer;
import me.rothens.gpsexif.ui.SettingsDialog;
import me.rothens.gpsexif.ui.Theme;
import me.rothens.gpsexif.util.ExitWatchdog;
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

import com.formdev.flatlaf.FlatLaf;

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
    /** Required by the OSM tile and Nominatim usage policies. */
    private static final String USER_AGENT = APP_NAME + " (https://github.com/rothens/ExifTweaker)";

    private final JTextField tfFolder = new JTextField();
    private final JButton btnBrowse = new JButton("...");
    private final JButton btnOpen = new JButton("Open");
    private final ImageListModel listModel = new ImageListModel();
    private final JList<ImageFile> lFiles = new JList<>(listModel);
    private final JCheckBox chkOnlyWithoutLocation = new JCheckBox("Only without location");
    private final JLabel lblStatus = new JLabel(" ");
    private final JButton btnSave = new JButton("Save");
    private final JButton btnUndo = new JButton("Undo");
    private final JButton btnCancel = new JButton("Cancel");
    private final JProgressBar progress = new JProgressBar();
    private final JPanel mainPanel = new JPanel(new BorderLayout(4, 4));
    private final JXMapViewer mapViewer = new JXMapViewer();
    private final ImagePanel pnThumbnail = new ImagePanel();
    private final ExifTableModel exifTableModel = new ExifTableModel();
    private final JTable jtExif = new JTable(exifTableModel);
    private final JComboBox<MapLayer> cbMapType = new JComboBox<>(MapLayer.values());
    private final JTextField tfCoordinate = new JTextField();
    private final JTextField tfSearch = new JTextField();
    private final PlaceSearch placeSearch = new PlaceSearch(USER_AGENT);
    private final JButton btnCoordinate = new JButton("Go!");
    private final JFrame frame;
    private final Settings settings = new Settings(Preferences.userNodeForPackage(ExifTweaker.class));
    private final MetadataBackend backend = new CommonsImagingBackend();
    private final EditHistory history = new EditHistory();
    private final PhotoWriter writer = new PhotoWriter(history, settings::isBackupsEnabled);
    private final TileDiskCache tileCache = new TileDiskCache(Path.of(System.getProperty("user.home"), ".jxmapviewer2"),
            () -> settings.getTileCacheMaxMb() * 1024L * 1024L);

    private final JMenuItem miUndo = new JMenuItem("Undo");
    private final JMenuItem miSave = new JMenuItem("Save location");
    private final JMenuItem miRemove = new JMenuItem("Remove location...");
    private final JMenuItem miCopy = new JMenuItem("Copy location");
    private final JMenuItem miPaste = new JMenuItem("Paste location");
    private final Map<Theme, JRadioButtonMenuItem> themeItems = new EnumMap<>(Theme.class);
    private final Map<MapLayer, JRadioButtonMenuItem> mapLayerItems = new EnumMap<>(MapLayer.class);
    private boolean updatingMapLayer;

    private WaypointPainter<Waypoint> waypointPainter;
    private final Set<Waypoint> waypoints = new HashSet<>();
    /** The photo shown in the details panel: the lead of the selection. */
    private ImageFile selected;
    private List<ImageFile> selection = List.of();
    private boolean busy;
    private SwingWorker<PhotoWriter.Result, Integer> batchWorker;
    /** Set by Cancel; the batch stops between two photos. (SwingWorker.cancel would unlock the UI mid-write.) */
    private final java.util.concurrent.atomic.AtomicBoolean cancelBatch = new java.util.concurrent.atomic.AtomicBoolean();
    private SwingWorker<BufferedImage, Void> thumbnailWorker;
    private final Map<MapLayer, DefaultTileFactory> factories = new EnumMap<>(MapLayer.class);

    public ExifTweaker(JFrame frame) {
        this.frame = frame;
        layoutComponents();
        frame.setJMenuBar(createMenuBar());
        initMap();
        installDesktopHandlers();

        lFiles.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        lFiles.setCellRenderer(new ImageListRenderer());
        // Ctrl+C / Ctrl+V on the list copy and paste locations instead of file names
        lFiles.getActionMap().put(TransferHandler.getCopyAction().getValue(Action.NAME), action(this::copyLocation));
        lFiles.getActionMap().put(TransferHandler.getPasteAction().getValue(Action.NAME), action(this::pasteLocation));
        lFiles.setComponentPopupMenu(createListPopup());
        chkOnlyWithoutLocation.addActionListener(e -> {
            List<ImageFile> keep = selection;
            listModel.setOnlyWithoutLocation(chkOnlyWithoutLocation.isSelected());
            reselect(keep);
        });
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
        tfSearch.addActionListener(e -> searchPlace());
        tfSearch.putClientProperty("JTextField.placeholderText", "Search for a place and press Enter");
        tfSearch.putClientProperty("JTextField.showClearButton", true);
        cbMapType.addActionListener(e -> setMapLayer((MapLayer) cbMapType.getSelectedItem()));

        btnUndo.addActionListener(e -> undo());
        btnCancel.addActionListener(e -> {
            cancelBatch.set(true);
            btnCancel.setEnabled(false);
        });
        btnCancel.setVisible(false);
        // History changes may come from background threads; Swing must only be touched on the event thread
        history.addChangeListener(() -> SwingUtilities.invokeLater(this::updateUndo));
        updateUndo();

        tfFolder.setText(settings.getLastDirectory());
        tfCoordinate.setToolTipText("Latitude;Longitude in decimal degrees, or e.g. 47°29'52\"N 19°2'24\"E");
        updateActions();
    }

    private static Action action(Runnable runnable) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                runnable.run();
            }
        };
    }

    private JPopupMenu createListPopup() {
        JPopupMenu popup = new JPopupMenu();
        popup.add(menuItem("Copy location", null, e -> copyLocation()));
        popup.add(menuItem("Paste location", null, e -> pasteLocation()));
        popup.addSeparator();
        popup.add(menuItem("Remove location...", null, e -> removeLocation()));
        return popup;
    }

    private JMenuBar createMenuBar() {
        int menuKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(menuItem("Open folder...", KeyStroke.getKeyStroke('O', menuKey), e -> browse()));
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            file.addSeparator();
            file.add(menuItem("Exit", KeyStroke.getKeyStroke('Q', menuKey), e -> exit()));
        }

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        miUndo.setAccelerator(KeyStroke.getKeyStroke('Z', menuKey));
        miUndo.addActionListener(e -> undo());
        edit.add(miUndo);
        edit.addSeparator();
        int shift = java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        miCopy.setAccelerator(KeyStroke.getKeyStroke('C', menuKey | shift));
        miCopy.addActionListener(e -> copyLocation());
        edit.add(miCopy);
        miPaste.setAccelerator(KeyStroke.getKeyStroke('V', menuKey | shift));
        miPaste.addActionListener(e -> pasteLocation());
        edit.add(miPaste);
        edit.addSeparator();
        miSave.setAccelerator(KeyStroke.getKeyStroke('S', menuKey));
        miSave.addActionListener(e -> saveSelected());
        edit.add(miSave);
        miRemove.addActionListener(e -> removeLocation());
        edit.add(miRemove);
        edit.add(menuItem("Select all photos", KeyStroke.getKeyStroke('A', menuKey | shift), e -> {
            if (listModel.getSize() > 0) {
                lFiles.setSelectionInterval(0, listModel.getSize() - 1);
            }
        }));
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES)) {
            edit.addSeparator();
            edit.add(menuItem("Settings...", KeyStroke.getKeyStroke(',', menuKey), e -> showSettings()));
        }

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();
        for (Theme theme : Theme.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(theme.toString());
            item.addActionListener(e -> setTheme(theme));
            themeGroup.add(item);
            themeMenu.add(item);
            themeItems.put(theme, item);
        }
        themeItems.get(settings.getTheme()).setSelected(true);
        view.add(themeMenu);
        JMenu layerMenu = new JMenu("Map layer");
        ButtonGroup layerGroup = new ButtonGroup();
        for (MapLayer layer : MapLayer.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(layer.toString());
            item.addActionListener(e -> setMapLayer(layer));
            layerGroup.add(item);
            layerMenu.add(item);
            mapLayerItems.put(layer, item);
        }
        view.add(layerMenu);

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(edit);
        bar.add(view);
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            JMenu help = new JMenu("Help");
            help.setMnemonic('H');
            help.add(menuItem("About " + APP_NAME, null, e -> showAbout()));
            bar.add(help);
        }
        return bar;
    }

    private static JMenuItem menuItem(String text, KeyStroke accelerator, java.awt.event.ActionListener action) {
        JMenuItem item = new JMenuItem(text);
        item.setAccelerator(accelerator);
        item.addActionListener(action);
        return item;
    }

    /** On macOS, About/Settings/Quit live in the application menu instead of our menus. */
    private void installDesktopHandlers() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler(e -> showAbout());
        }
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
            desktop.setPreferencesHandler(e -> showSettings());
        }
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler((e, response) -> {
                ExitWatchdog.arm();
                shutdown();
                response.performQuit();
            });
        }
    }

    /** Releases background work and temp files. Called on the event thread before the JVM exits. */
    private void shutdown() {
        cancelBatch.set(true);
        if (null != thumbnailWorker) {
            thumbnailWorker.cancel(true);
        }
        factories.values().forEach(DefaultTileFactory::dispose);
        history.close();
        frame.dispose();
    }

    private void exit() {
        frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING));
    }

    private void showSettings() {
        if (new SettingsDialog(frame, settings, tileCache).showDialog()) {
            setTheme(settings.getTheme());
            setMapLayer(settings.getMapLayer());
        }
    }

    private void showAbout() {
        String version = Optional.ofNullable(ExifTweaker.class.getPackage().getImplementationVersion())
                .orElse("development build");
        JOptionPane.showMessageDialog(frame,
                "<html><b>" + APP_NAME + "</b> " + version + "<br><br>"
                        + "Sets the GPS position in the EXIF data of photos.<br>"
                        + "https://github.com/rothens/ExifTweaker<br><br>"
                        + "Map data &copy; OpenStreetMap contributors (ODbL).<br>"
                        + "Satellite imagery: Esri, Maxar, Earthstar Geographics, and the GIS User Community.</html>",
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    private void setTheme(Theme theme) {
        settings.setTheme(theme);
        themeItems.get(theme).setSelected(true);
        theme.install();
        FlatLaf.updateUI();
    }

    /** Enables the actions that make sense for the current selection, and updates the status line. */
    private void updateActions() {
        boolean canWrite = !busy && !selection.isEmpty();
        btnSave.setEnabled(canWrite);
        miSave.setEnabled(canWrite);
        miRemove.setEnabled(canWrite && selection.stream().anyMatch(ImageFile::hasExifGPS));
        miPaste.setEnabled(!busy);
        btnOpen.setEnabled(!busy);
        btnBrowse.setEnabled(!busy);
        chkOnlyWithoutLocation.setEnabled(!busy);
        btnSave.setToolTipText(selection.size() > 1
                ? "Write the location to the " + selection.size() + " selected photos" : null);
        updateUndo();

        int total = listModel.getAll().size();
        int shown = listModel.getSize();
        StringBuilder status = new StringBuilder();
        status.append(shown).append(shown == 1 ? " photo" : " photos");
        if (shown != total) {
            status.append(" (").append(total - shown).append(" hidden)");
        }
        if (selection.size() > 1) {
            status.append(", ").append(selection.size()).append(" selected");
        }
        lblStatus.setText(total == 0 ? " " : status.toString());
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        btnCancel.setVisible(busy);
        btnCancel.setEnabled(busy);
        updateActions();
    }

    private void layoutComponents() {
        JPanel top = new JPanel(new BorderLayout(4, 0));
        JPanel topButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        topButtons.add(btnBrowse);
        topButtons.add(btnOpen);
        top.add(tfFolder, BorderLayout.CENTER);
        top.add(topButtons, BorderLayout.EAST);

        JPanel filePanel = new JPanel(new BorderLayout(0, 2));
        filePanel.add(chkOnlyWithoutLocation, BorderLayout.NORTH);
        filePanel.add(new JScrollPane(lFiles), BorderLayout.CENTER);
        filePanel.add(lblStatus, BorderLayout.SOUTH);
        filePanel.setPreferredSize(new Dimension(220, 0));

        pnThumbnail.setPreferredSize(new Dimension(300, 300));
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pnThumbnail, new JScrollPane(jtExif));
        rightSplit.setResizeWeight(0.6);
        rightSplit.setPreferredSize(new Dimension(300, 0));

        JPanel mapPanel = new JPanel(new BorderLayout(0, 4));
        mapPanel.add(tfSearch, BorderLayout.NORTH);
        mapPanel.add(mapViewer, BorderLayout.CENTER);
        JSplitPane mapSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mapPanel, rightSplit);
        mapSplit.setResizeWeight(1.0);
        mapSplit.setDividerSize(5);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePanel, mapSplit);
        centerSplit.setDividerSize(5);

        JPanel coordinatePanel = new JPanel(new BorderLayout(4, 0));
        coordinatePanel.add(cbMapType, BorderLayout.WEST);
        coordinatePanel.add(tfCoordinate, BorderLayout.CENTER);
        coordinatePanel.add(btnCoordinate, BorderLayout.EAST);

        JPanel progressPanel = new JPanel(new BorderLayout(4, 0));
        progressPanel.add(progress, BorderLayout.CENTER);
        JPanel saveButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        saveButtons.add(btnCancel);
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

        setBusy(true);
        btnCancel.setVisible(false);
        progress.setValue(0);
        progress.setMaximum(files.length);
        new SwingWorker<List<ImageFile>, Integer>() {
            @Override
            protected List<ImageFile> doInBackground() {
                List<ImageFile> loaded = new ArrayList<>();
                for (File f : files) {
                    loaded.add(new ImageFile(f, backend));
                    publish(loaded.size());
                }
                return loaded;
            }

            @Override
            protected void process(List<Integer> chunks) {
                progress.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                try {
                    listModel.setAll(get());
                } catch (InterruptedException | ExecutionException e) {
                    showError("Error while opening folder:\n" + e.getMessage());
                }
                setBusy(false);
            }
        }.execute();
    }

    /** The location picked on the map / typed in, which Save writes; {@code null} if none. */
    private GeoPosition pendingPosition() {
        for (Waypoint w : waypoints) {
            if (w instanceof SelectionWaypoint) {
                return w.getPosition();
            }
        }
        return null;
    }

    private void saveSelected() {
        List<ImageFile> targets = selection;
        if (busy || targets.isEmpty()) {
            return;
        }
        GeoPosition position = pendingPosition();
        if (null == position) {
            showError("Right-click on the map, enter a coordinate or paste a location first.");
            return;
        }
        long withLocation = targets.stream().filter(ImageFile::hasExifGPS).count();
        if (targets.size() > 1 && withLocation > 0 && !confirm(withLocation + " of the " + targets.size()
                + " selected photos already " + (withLocation == 1 ? "has" : "have") + " a location.\nReplace "
                + (withLocation == 1 ? "it" : "them") + "?", "Replace locations")) {
            return;
        }
        String description = targets.size() == 1
                ? "Set location of " + targets.get(0).getFile().getName()
                : "Set location of " + targets.size() + " photos";
        runBatch(description, targets, image -> image.savePosition(position));
    }

    private void removeLocation() {
        List<ImageFile> targets = selection.stream().filter(ImageFile::hasExifGPS).toList();
        if (busy || targets.isEmpty()) {
            return;
        }
        String what = targets.size() == 1 ? targets.get(0).getFile().getName() : targets.size() + " photos";
        if (!confirm("Remove the location from " + what + "?", "Remove location")) {
            return;
        }
        runBatch("Remove location from " + what, targets, ImageFile::removePosition);
    }

    private boolean confirm(String message, String title) {
        return JOptionPane.showConfirmDialog(frame, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Applies an edit to photos in the background, with progress, Cancel, a failure summary and undo. */
    private void runBatch(String description, List<ImageFile> targets, PhotoWriter.Edit edit) {
        boolean undoable = writer.canUndo(targets);
        if (!undoable) {
            String backups = settings.isBackupsEnabled()
                    ? "Backups (.bak) of the originals are still kept."
                    : "Backups are turned off in Settings, so the originals won't be kept!";
            if (!confirm("These " + targets.size() + " photos are too large to be undone (undo keeps up to "
                    + EditHistory.DEFAULT_MAX_BYTES / (1024 * 1024 * 1024) + " GB of copies).\n"
                    + backups + "\n\nContinue without undo?", description)) {
                return;
            }
        }
        setBusy(true);
        cancelBatch.set(false);
        progress.setValue(0);
        progress.setMaximum(targets.size());
        batchWorker = new SwingWorker<>() {
            @Override
            protected PhotoWriter.Result doInBackground() {
                return writer.apply(description, targets, edit, undoable, new PhotoWriter.Progress() {
                    @Override
                    public void update(int done, int total) {
                        publish(done);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelBatch.get();
                    }
                });
            }

            @Override
            protected void process(List<Integer> chunks) {
                progress.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                batchWorker = null;
                setBusy(false);
                refreshList();
                try {
                    PhotoWriter.Result result = get();
                    reportFailures(result);
                    if (result.skipped() > 0) {
                        lblStatus.setText("Cancelled after " + (targets.size() - result.skipped()) + " of "
                                + targets.size() + " photos");
                    }
                } catch (InterruptedException | ExecutionException e) {
                    showError(description + " failed:\n" + e.getMessage());
                }
            }
        };
        batchWorker.execute();
    }

    private void reportFailures(PhotoWriter.Result result) {
        if (result.failures().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(result.failures().size()).append(" of ")
                .append(result.changed().size() + result.failures().size()).append(" photos couldn't be written:\n\n");
        int shown = 0;
        for (Map.Entry<ImageFile, String> failure : result.failures().entrySet()) {
            if (++shown > 10) {
                sb.append("... and ").append(result.failures().size() - 10).append(" more");
                break;
            }
            sb.append(failure.getKey().getFile().getName()).append(": ").append(failure.getValue()).append('\n');
        }
        showError(sb.toString());
    }

    private void copyLocation() {
        GeoPosition position = pendingPosition();
        if (null == position && null != selected) {
            position = selected.getGp();
        }
        if (null == position) {
            lblStatus.setText("No location to copy");
            return;
        }
        String text = PositionUtil.getPositionString(position);
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
        lblStatus.setText("Copied " + text);
    }

    private void pasteLocation() {
        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException | IllegalStateException e) {
            lblStatus.setText("The clipboard doesn't contain a location");
            return;
        }
        try {
            GeoPosition position = PositionUtil.parse(text);
            selectPosition(position);
            mapViewer.setAddressLocation(position);
            lblStatus.setText(selection.isEmpty() ? "Location pasted"
                    : "Location pasted - Save writes it to " + (selection.size() == 1 ? "the photo"
                    : "the " + selection.size() + " photos"));
        } catch (IllegalArgumentException e) {
            lblStatus.setText("The clipboard doesn't contain a location");
        }
    }

    private void undo() {
        if (busy) {
            return;
        }
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
        for (ImageFile image : listModel.getAll()) {
            if (paths.contains(image.getPath().toAbsolutePath().normalize())) {
                image.reload();
            }
        }
        refreshList();
    }

    /** Re-applies the list filter after photos changed, keeping the selection where possible. */
    private void refreshList() {
        List<ImageFile> keep = selection;
        listModel.refresh();
        reselect(keep);
        lFiles.repaint();
        elementSelected();
    }

    private void reselect(List<ImageFile> images) {
        lFiles.clearSelection();
        for (ImageFile image : images) {
            int index = listModel.indexOf(image);
            if (index >= 0) {
                lFiles.addSelectionInterval(index, index);
            }
        }
    }

    private void updateUndo() {
        boolean canUndo = !busy && history.canUndo();
        btnUndo.setEnabled(canUndo);
        btnUndo.setToolTipText(canUndo ? "Undo: " + history.getUndoDescription() : null);
        miUndo.setEnabled(canUndo);
        miUndo.setText(canUndo ? "Undo " + history.getUndoDescription() : "Undo");
    }

    /** Searches in the background; one result is shown right away, several are offered in a menu. */
    private void searchPlace() {
        String query = tfSearch.getText();
        if (query.isBlank()) {
            return;
        }
        tfSearch.setEnabled(false);
        lblStatus.setText("Searching...");
        new SwingWorker<List<PlaceSearch.Place>, Void>() {
            @Override
            protected List<PlaceSearch.Place> doInBackground() throws Exception {
                return placeSearch.search(query);
            }

            @Override
            protected void done() {
                tfSearch.setEnabled(true);
                updateActions();
                List<PlaceSearch.Place> places;
                try {
                    places = get();
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = null != e.getCause() ? e.getCause() : e;
                    showError("Couldn't search for places (nominatim.openstreetmap.org).\n"
                            + "Check your internet connection.\n\nDetails: " + cause.getMessage());
                    return;
                }
                if (places.isEmpty()) {
                    lblStatus.setText("No place found for \"" + query.trim() + "\"");
                } else if (places.size() == 1) {
                    showPlace(places.get(0));
                } else {
                    JPopupMenu menu = new JPopupMenu();
                    for (PlaceSearch.Place place : places) {
                        String name = place.name().length() > 90 ? place.name().substring(0, 87) + "..." : place.name();
                        JMenuItem item = new JMenuItem(name);
                        item.setToolTipText(place.name());
                        item.addActionListener(e -> showPlace(place));
                        menu.add(item);
                    }
                    menu.show(tfSearch, 0, tfSearch.getHeight());
                }
            }
        }.execute();
    }

    /** Moves the map to a place. This only navigates; right-click to pick the exact location. */
    private void showPlace(PlaceSearch.Place place) {
        if (place.hasBounds()) {
            mapViewer.zoomToBestFit(Set.of(new GeoPosition(place.south(), place.west()),
                    new GeoPosition(place.north(), place.east())), 0.8);
        } else {
            mapViewer.setAddressLocation(place.position());
        }
        lblStatus.setText(place.name().split(",")[0] + " - right-click to pick the exact spot");
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
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", APP_NAME);
        Theme theme = new Settings(Preferences.userNodeForPackage(ExifTweaker.class)).getTheme();
        SwingUtilities.invokeLater(() -> {
            theme.install();
            JFrame frame = new JFrame(APP_NAME);
            ExifTweaker app = new ExifTweaker(frame);
            frame.setContentPane(app.mainPanel);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    ExitWatchdog.arm();
                    app.shutdown();
                    System.exit(0);
                }
            });
            // Only deletes temp files (no Swing access), for when the JVM is stopped some other way (e.g. Ctrl+C)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                ExitWatchdog.arm();
                app.history.close();
            }, "exiftweaker-cleanup"));
            frame.setSize(1200, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private void elementSelected() {
        selection = lFiles.getSelectedValuesList();
        int lead = lFiles.getLeadSelectionIndex();
        selected = lead >= 0 && lFiles.isSelectedIndex(lead) ? listModel.getElementAt(lead) : lFiles.getSelectedValue();
        GeoPosition pending = pendingPosition();
        updateActions();
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
            // When adding photos to a multi-selection, keep the location picked for them
            if (selection.size() > 1 && null != pending) {
                waypoints.add(new SelectionWaypoint(pending));
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
        if (updatingMapLayer) {
            return;
        }
        updatingMapLayer = true;
        try {
            cbMapType.setSelectedItem(layer);
            mapLayerItems.get(layer).setSelected(true);
        } finally {
            updatingMapLayer = false;
        }
        MapLayer.switchTileFactory(mapViewer, factories.get(layer));
        settings.setMapLayer(layer);
    }

    private void initMap() {
        for (MapLayer layer : MapLayer.values()) {
            DefaultTileFactory tf = new DefaultTileFactory(layer.createInfo());
            tf.setThreadPoolSize(8);
            // The OSM tile usage policy requires an identifying User-Agent
            tf.setUserAgent(USER_AGENT);
            tf.setLocalCache(tileCache);
            factories.put(layer, tf);
        }
        tileCache.scheduleMaintenance();
        setMapLayer(settings.getMapLayer());

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
