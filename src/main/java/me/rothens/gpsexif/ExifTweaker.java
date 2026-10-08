package me.rothens.gpsexif;

import me.rothens.gpsexif.history.EditHistory;
import me.rothens.gpsexif.history.PhotoWriter;
import me.rothens.gpsexif.gpx.GpxWriter;
import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackMatcher;
import me.rothens.gpsexif.map.AttributionPainter;
import me.rothens.gpsexif.map.DirectionOverlay;
import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.map.PhotoMarkerLayer;
import me.rothens.gpsexif.map.PlaceSearch;
import me.rothens.gpsexif.map.TileDiskCache;
import me.rothens.gpsexif.map.TrackPainter;
import me.rothens.gpsexif.metadata.ExifTool;
import me.rothens.gpsexif.metadata.ExifToolBackend;
import me.rothens.gpsexif.metadata.RoutingBackend;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.model.ImageListModel;
import me.rothens.gpsexif.model.ImageListRenderer;
import me.rothens.gpsexif.model.MetadataTableModel;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.TextTag;
import me.rothens.gpsexif.playback.PlaybackSequence;
import me.rothens.gpsexif.playback.PlaybackWindow;
import me.rothens.gpsexif.playback.TravelWindow;
import me.rothens.gpsexif.ui.ExifToolDialog;
import me.rothens.gpsexif.ui.GeotagDialog;
import me.rothens.gpsexif.ui.PhotoDrag;
import me.rothens.gpsexif.ui.SettingsDialog;
import me.rothens.gpsexif.ui.ShiftTimeDialog;
import me.rothens.gpsexif.gpx.PhotoTime;
import me.rothens.gpsexif.ui.Theme;
import me.rothens.gpsexif.util.ExitWatchdog;
import me.rothens.gpsexif.util.PhotoLoader;
import me.rothens.gpsexif.util.PositionUtil;
import me.rothens.gpsexif.util.Settings;
import me.rothens.gpsexif.util.ThumbnailCache;
import me.rothens.gpsexif.util.TripMarkStore;
import me.rothens.gpsexif.model.TripMark;
import me.rothens.gpsexif.model.TripMarkIcon;
import me.rothens.gpsexif.model.ThumbnailRenderer;
import me.rothens.gpsexif.tutorial.SampleTrip;
import me.rothens.gpsexif.tutorial.Tutorial;
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
    /** The same photos as a grid of thumbnails; shares the list's model and selection. */
    private final JList<ImageFile> lThumbs = new JList<>(listModel);
    private final ThumbnailCache thumbnails = new ThumbnailCache(ThumbnailRenderer.THUMBNAIL_SIZE, 1000,
            lThumbs::repaint);
    private final JTabbedPane photoTabs = new JTabbedPane();
    private final TripMarkStore tripMarks = TripMarkStore.openDefault();
    /** Photos on the left, the rest on the right. */
    private JSplitPane centerSplit;
    private final JCheckBox chkOnlyWithoutLocation = new JCheckBox("Only without location");
    private final JLabel lblStatus = new JLabel(" ");
    private final JButton btnSave = new JButton("Save");
    private final JButton btnUndo = new JButton("Undo");
    private final JButton btnCancel = new JButton("Cancel");
    private final JProgressBar progress = new JProgressBar();
    private final JPanel mainPanel = new JPanel(new BorderLayout(4, 4));
    private final JXMapViewer mapViewer = new JXMapViewer();
    private final ImagePanel pnThumbnail = new ImagePanel();
    private final MetadataTableModel metadataModel = new MetadataTableModel();
    private final JTable jtExif = new JTable(metadataModel);
    private final JComboBox<MapLayer> cbMapType = new JComboBox<>(MapLayer.values());
    private final JTextField tfCoordinate = new JTextField();
    private final JTextField tfAltitude = new JTextField(6);
    private final JTextField tfDirection = new JTextField(5);
    /** Set when the user changed altitude/direction, so Save writes them; reset when a photo is selected. */
    private boolean altitudeEdited;
    private boolean directionEdited;
    private boolean updatingFields;
    private DirectionOverlay directionOverlay;
    private final JTextField tfSearch = new JTextField();
    private final PlaceSearch placeSearch = new PlaceSearch(USER_AGENT);
    private final JButton btnCoordinate = new JButton("Go!");
    private final JFrame frame;
    private final Settings settings = new Settings(Preferences.userNodeForPackage(ExifTweaker.class));
    private final RoutingBackend backend = new RoutingBackend();
    private final JPanel exifToolBanner = new JPanel(new BorderLayout(8, 0));
    private boolean bannerDismissed;
    private final EditHistory history = new EditHistory();
    private final PhotoWriter writer = new PhotoWriter(history, settings::isBackupsEnabled);
    private final TileDiskCache tileCache = new TileDiskCache(Path.of(System.getProperty("user.home"), ".jxmapviewer2"),
            () -> settings.getTileCacheMaxMb() * 1024L * 1024L);

    private final JMenuItem miUndo = new JMenuItem("Undo");
    private final JMenuItem miSave = new JMenuItem("Save location");
    private final JMenuItem miRemove = new JMenuItem("Remove location...");
    private final JMenuItem miShiftTime = new JMenuItem("Shift date/time...");
    private final JMenuItem miCopy = new JMenuItem("Copy location");
    private final JMenuItem miPaste = new JMenuItem("Paste location");
    private final Map<Theme, JRadioButtonMenuItem> themeItems = new EnumMap<>(Theme.class);
    private final Map<MapLayer, JRadioButtonMenuItem> mapLayerItems = new EnumMap<>(MapLayer.class);
    private boolean updatingMapLayer;

    private final JMenuItem miGeotag = new JMenuItem("Geotag from GPX...");
    private final JMenuItem miExportGpx = new JMenuItem("Export photos as GPX...");
    private final JMenuItem miPlayback = new JMenuItem("Play photos...");
    private final JMenuItem miTravel = new JMenuItem("Travel mode...");
    /** GPX tracks last loaded in the Geotag dialog; travel mode follows them. */
    private List<Track> lastTracks = List.of();
    private JMenu file;
    private JMenu view;
    private Tutorial tutorial;
    /** Counts saves and undos, so a tutorial step can tell that the user saved. */
    private final java.util.concurrent.atomic.AtomicInteger historyChanges = new java.util.concurrent.atomic.AtomicInteger();
    private final TrackPainter trackPainter = new TrackPainter();
    private final JCheckBoxMenuItem miShowMarkers = new JCheckBoxMenuItem("Show photos on map");
    private PhotoMarkerLayer markerLayer;
    private GeotagDialog geotagDialog;

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
        setUpThumbnailGrid();
        PhotoDrag.enableDrag(lFiles);
        PhotoDrag.enableDrag(lThumbs);
        setUpMetadataTable();
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
        history.addChangeListener(historyChanges::incrementAndGet);
        updateUndo();

        detectExifTool();

        tfFolder.setText(settings.getLastDirectory());
        tfCoordinate.setToolTipText("Latitude;Longitude in decimal degrees, or e.g. 47°29'52\"N 19°2'24\"E");
        tfAltitude.setToolTipText("Metres above sea level (negative below); written with Save. Empty removes it.");
        tfDirection.setToolTipText("Degrees clockwise from north the camera pointed; written with Save. "
                + "You can also drag the handle on the map. Empty removes it.");
        tfAltitude.getDocument().addDocumentListener(onEdit(() -> altitudeEdited = true));
        tfDirection.getDocument().addDocumentListener(onEdit(() -> {
            directionEdited = true;
            updateDirectionOverlay();
        }));
        updateActions();
    }

    /** Document listener for user edits; programmatic updates (while {@code updatingFields}) are ignored. */
    private javax.swing.event.DocumentListener onEdit(Runnable onUserEdit) {
        return new javax.swing.event.DocumentListener() {
            private void changed() {
                if (!updatingFields) {
                    onUserEdit.run();
                    updateActions();
                }
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }
        };
    }

    private void setField(JTextField field, String text) {
        updatingFields = true;
        try {
            field.setText(text);
        } finally {
            updatingFields = false;
        }
    }

    /** The direction shown on the map: the edited one, else the selected photo's. */
    private void updateDirectionOverlay() {
        if (null == directionOverlay) {
            return;
        }
        GeoPosition anchor = pendingPosition();
        if (null == anchor && null != selected) {
            anchor = selected.getGp();
        }
        Double direction = null != selected ? selected.getDirection() : null;
        if (directionEdited) {
            try {
                direction = MetadataTableModel.parseDirection(tfDirection.getText());
            } catch (IllegalArgumentException e) {
                // keep showing the last valid one
            }
        }
        directionOverlay.set(selection.isEmpty() ? null : anchor, direction);
    }

    private static Action action(Runnable runnable) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                runnable.run();
            }
        };
    }

    /** Thin banner shown while ExifTool isn't available; clicking it opens the ExifTool dialog. */
    private JPanel createExifToolBanner() {
        JLabel text = new JLabel("HEIC, PNG, TIFF, WebP and RAW files need ExifTool, which wasn't found. "
                + "Click here to set it up...");
        text.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JButton close = new JButton("\u00d7");
        close.setToolTipText("Hide until the next start");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.addActionListener(e -> {
            bannerDismissed = true;
            exifToolBanner.setVisible(false);
        });
        exifToolBanner.add(text, BorderLayout.CENTER);
        exifToolBanner.add(close, BorderLayout.EAST);
        exifToolBanner.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 3));
        exifToolBanner.setOpaque(true);
        exifToolBanner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter open = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showExifToolDialog(frame);
            }
        };
        exifToolBanner.addMouseListener(open);
        text.addMouseListener(open);
        styleBanner();
        exifToolBanner.setVisible(false);
        return exifToolBanner;
    }

    /** Warning colours that work in the light and the dark theme. */
    private void styleBanner() {
        boolean dark = FlatLaf.isLafDark();
        exifToolBanner.setBackground(dark ? new Color(84, 68, 20) : new Color(255, 244, 206));
        for (Component c : exifToolBanner.getComponents()) {
            c.setForeground(dark ? new Color(245, 225, 160) : new Color(90, 70, 0));
        }
    }

    /** Looks for ExifTool in the background (starting it takes a moment) and switches it on if found. */
    private void detectExifTool() {
        String configured = settings.getExifToolPath();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return ExifTool.locate(configured);
            }

            @Override
            protected void done() {
                try {
                    activateExifTool(get());
                } catch (InterruptedException | ExecutionException e) {
                    activateExifTool(null);
                }
            }
        }.execute();
    }

    private String activeExifTool;
    private String activeExifToolVersion;

    private void activateExifTool(String executable) {
        if (java.util.Objects.equals(executable, activeExifTool) && backend.hasExifTool() == (null != executable)) {
            exifToolBanner.setVisible(null == executable && !bannerDismissed);
            return;
        }
        ExifToolBackend old = backend.getExifTool();
        activeExifTool = executable;
        activeExifToolVersion = null == executable ? null : ExifTool.version(executable);
        backend.setExifTool(null == executable ? null : new ExifToolBackend(new ExifTool(executable)));
        if (null != old) {
            old.getExifTool().close();
        }
        exifToolBanner.setVisible(null == executable && !bannerDismissed);
        reloadAll();
    }

    /** Re-reads all opened photos, e.g. after ExifTool became available. */
    private void reloadAll() {
        List<ImageFile> all = listModel.getAll();
        if (all.isEmpty()) {
            return;
        }
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                all.forEach(ImageFile::reload);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false);
                refreshList();
            }
        }.execute();
    }

    private String exifToolStatus() {
        return null == activeExifTool ? "Not found - HEIC, PNG, TIFF, WebP and RAW files are read-only"
                : "ExifTool " + activeExifToolVersion + " (" + activeExifTool + ")";
    }

    /** Opens the ExifTool dialog and applies the chosen executable; returns the new status text. */
    private String showExifToolDialog(Window owner) {
        String path = new ExifToolDialog(owner, settings.getExifToolPath(), activeExifTool).showDialog();
        if (null != path) {
            settings.setExifToolPath(path);
            String executable = ExifTool.locate(path);
            activateExifTool(executable);
            if (null == executable) {
                showError("ExifTool still wasn't found. HEIC, PNG, TIFF, WebP and RAW files stay read-only.");
            }
        }
        return exifToolStatus();
    }

    private void setUpMetadataTable() {
        jtExif.putClientProperty("terminateEditOnFocusLost", true);
        jtExif.getColumnModel().getColumn(0).setPreferredWidth(120);
        jtExif.getColumnModel().getColumn(0).setMaxWidth(150);
        jtExif.getColumnModel().getColumn(1).setPreferredWidth(200);
        jtExif.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                boolean editableRow = metadataModel.isEditableRow(row);
                setFont(getFont().deriveFont(metadataModel.isMultiple(row) && column == 1 ? Font.ITALIC : Font.PLAIN));
                if (!isSelected) {
                    // Read-only info and mixed values are greyed out; field names of editable rows are not
                    boolean dim = !editableRow || (column == 1 && metadataModel.isMultiple(row));
                    setForeground(dim ? UIManager.getColor("Label.disabledForeground") : table.getForeground());
                }
                setToolTipText(editableRow && column == 1 ? "Double-click to edit" : null);
                return this;
            }
        });
        metadataModel.setEditHandler(new MetadataTableModel.EditHandler() {
            @Override
            public void edit(String field, String value, List<ImageFile> photos, MetadataChanges changes) {
                if (busy) {
                    return;
                }
                String what = photos.size() == 1 ? photos.get(0).getFile().getName() : photos.size() + " photos";
                if (photos.size() > 1 && !confirm((value.isEmpty() ? "Remove " + field + " from "
                        : "Set " + field + " to \"" + value + "\" for ") + photos.size() + " photos?", field)) {
                    return;
                }
                runBatch((value.isEmpty() ? "Remove " + field + " from " : "Set " + field + " of ") + what,
                        List.copyOf(photos), image -> image.apply(changes));
            }

            @Override
            public void invalid(String message) {
                showError(message);
            }
        });
    }

    private JPopupMenu createListPopup() {
        JPopupMenu popup = new JPopupMenu();
        popup.add(menuItem("Copy location", null, e -> copyLocation()));
        popup.add(menuItem("Paste location", null, e -> pasteLocation()));
        popup.addSeparator();
        popup.add(menuItem("Remove location...", null, e -> removeLocation()));
        popup.addSeparator();
        JCheckBoxMenuItem prefer = new JCheckBoxMenuItem("Prefer in trips");
        prefer.setIcon(new TripMarkIcon(TripMark.PREFER, 14));
        prefer.setToolTipText("Travel mode shows these first when it can't show every photo of a stretch");
        prefer.addActionListener(e -> setTripMark(prefer.isSelected() ? TripMark.PREFER : TripMark.NORMAL));
        JCheckBoxMenuItem skip = new JCheckBoxMenuItem("Skip in trips");
        skip.setIcon(new TripMarkIcon(TripMark.SKIP, 14));
        skip.setToolTipText("Leave these out of Play photos, Travel mode and the videos");
        skip.addActionListener(e -> setTripMark(skip.isSelected() ? TripMark.SKIP : TripMark.NORMAL));
        popup.add(prefer);
        popup.add(skip);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean any = !selection.isEmpty();
                prefer.setEnabled(any);
                skip.setEnabled(any);
                prefer.setSelected(any && selection.stream().allMatch(p -> TripMark.PREFER == p.getTripMark()));
                skip.setSelected(any && selection.stream().allMatch(p -> TripMark.SKIP == p.getTripMark()));
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        return popup;
    }

    /** Marks the selected photos as preferred, skipped or normal in Play photos and Travel mode. */
    private void setTripMark(TripMark mark) {
        if (selection.isEmpty()) {
            return;
        }
        String error = null;
        for (ImageFile photo : selection) {
            photo.setTripMark(mark);
            try {
                tripMarks.set(photo.getPath(), mark);
            } catch (IOException e) {
                error = e.getMessage();
            }
        }
        lFiles.repaint();
        lThumbs.repaint();
        String photos = selection.size() == 1 ? "1 photo" : selection.size() + " photos";
        lblStatus.setText(switch (mark) {
            case PREFER -> photos + " preferred in trips";
            case SKIP -> photos + " skipped in trips";
            case NORMAL -> photos + " shown as usual in trips";
        });
        if (null != error) {
            showError("Couldn't remember this for the next time:\n" + error);
        }
    }

    private JMenuBar createMenuBar() {
        int menuKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        file = new JMenu("File");
        file.setMnemonic('F');
        file.add(menuItem("Open folder...", KeyStroke.getKeyStroke('O', menuKey), e -> browse()));
        JMenu recent = new JMenu("Recent folders");
        recent.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                fillRecentFolders(recent);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        fillRecentFolders(recent);
        file.add(recent);
        miGeotag.setAccelerator(KeyStroke.getKeyStroke('G', menuKey));
        miGeotag.addActionListener(e -> openGeotag());
        file.add(miGeotag);
        miExportGpx.setAccelerator(KeyStroke.getKeyStroke('E', menuKey));
        miExportGpx.addActionListener(e -> exportGpx());
        file.add(miExportGpx);
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
        miShiftTime.setAccelerator(KeyStroke.getKeyStroke('T', menuKey));
        miShiftTime.addActionListener(e -> shiftTime());
        edit.add(miShiftTime);
        edit.add(menuItem("Select all photos", KeyStroke.getKeyStroke('A', menuKey | shift), e -> {
            if (listModel.getSize() > 0) {
                lFiles.setSelectionInterval(0, listModel.getSize() - 1);
            }
        }));
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES)) {
            edit.addSeparator();
            edit.add(menuItem("Settings...", KeyStroke.getKeyStroke(',', menuKey), e -> showSettings()));
        }

        view = new JMenu("View");
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
        view.addSeparator();
        miShowMarkers.setSelected(settings.isShowPhotoMarkers());
        miShowMarkers.setToolTipText("Show the opened photos that have a location on the map (nearby photos are grouped)");
        miShowMarkers.addActionListener(e -> {
            settings.setShowPhotoMarkers(miShowMarkers.isSelected());
            markerLayer.setEnabled(miShowMarkers.isSelected());
        });
        view.add(miShowMarkers);
        view.addSeparator();
        miPlayback.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F5, 0));
        miPlayback.setToolTipText("Play the selected photos (or all) in the order they were taken, with a map");
        miPlayback.addActionListener(e -> openPlayback());
        view.add(miPlayback);
        miTravel.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F5,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        miTravel.setToolTipText("Play the trip as a short film: a marker travels the route, photos fade in on arrival");
        miTravel.addActionListener(e -> openTravel());
        view.add(miTravel);

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(edit);
        bar.add(view);
        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        help.add(menuItem("Show tutorial", null, e -> startTutorial()));
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            help.add(menuItem("About " + APP_NAME, null, e -> showAbout()));
        }
        bar.add(help);
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
        if (null != backend.getExifTool()) {
            backend.getExifTool().getExifTool().close();
        }
        history.close();
        SampleTrip.deleteAll();
        frame.dispose();
    }

    private void exit() {
        frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING));
    }

    private void showSettings() {
        if (new SettingsDialog(frame, settings, tileCache, exifToolStatus(), this::showExifToolDialog).showDialog()) {
            setTheme(settings.getTheme());
            setMapLayer(settings.getMapLayer());
            markerLayer.recompute();
        }
    }

    /** The photo grid: same model, selection, shortcuts and context menu as the list. */
    private void setUpThumbnailGrid() {
        lThumbs.setSelectionModel(lFiles.getSelectionModel());
        lThumbs.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        lThumbs.setVisibleRowCount(-1);
        lThumbs.setCellRenderer(new ThumbnailRenderer(thumbnails));
        lThumbs.setFixedCellWidth(ThumbnailRenderer.CELL_WIDTH);
        lThumbs.setFixedCellHeight(ThumbnailRenderer.cellHeight(lThumbs.getFont(), lThumbs));
        lThumbs.getActionMap().put(TransferHandler.getCopyAction().getValue(Action.NAME), action(this::copyLocation));
        lThumbs.getActionMap().put(TransferHandler.getPasteAction().getValue(Action.NAME), action(this::pasteLocation));
        lThumbs.setComponentPopupMenu(createListPopup());
        photoTabs.setSelectedIndex(settings.isThumbnailView() ? 1 : 0);
        photoTabs.setToolTipTextAt(1, "The photos as small pictures");
        photoTabs.addChangeListener(e -> {
            settings.setThumbnailView(photoTabs.getSelectedIndex() == 1);
            if (photoTabs.getSelectedIndex() == 1) {
                SwingUtilities.invokeLater(this::fitTwoThumbnailColumns);
            }
            int lead = lFiles.getLeadSelectionIndex();
            if (lead >= 0 && lead < listModel.getSize()) {
                photoView().ensureIndexIsVisible(lead);
            }
        });
    }

    /** Widens the photo panel if the grid would only fit one column. */
    private void fitTwoThumbnailColumns() {
        JViewport viewport = (JViewport) lThumbs.getParent();
        JScrollPane scroll = (JScrollPane) viewport.getParent();
        int scrollbar = scroll.getVerticalScrollBar().getPreferredSize().width;
        int needed = 2 * ThumbnailRenderer.CELL_WIDTH + scrollbar + 4;
        int missing = needed - viewport.getWidth();
        if (viewport.getWidth() > 0 && missing > 0) {
            centerSplit.setDividerLocation(centerSplit.getDividerLocation() + missing);
        }
    }

    /** The photo list or the thumbnail grid, whichever tab is showing. */
    private JList<ImageFile> photoView() {
        return photoTabs.getSelectedIndex() == 1 ? lThumbs : lFiles;
    }

    /** Rebuilds the File → Recent folders menu; folders that no longer exist are shown disabled. */
    private void fillRecentFolders(JMenu menu) {
        menu.removeAll();
        List<String> folders = settings.getRecentFolders();
        if (folders.isEmpty()) {
            JMenuItem none = new JMenuItem("No recent folders");
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        int n = 0;
        for (String folder : folders) {
            n++;
            JMenuItem item = new JMenuItem((n < 10 ? n + "  " : "    ") + shortenPath(folder, 70));
            if (n < 10) {
                item.setMnemonic(Character.forDigit(n, 10));
            }
            item.setToolTipText(folder);
            item.setEnabled(new File(folder).isDirectory());
            item.addActionListener(e -> {
                tfFolder.setText(folder);
                openFolder();
            });
            menu.add(item);
        }
        menu.addSeparator();
        menu.add(menuItem("Clear recent folders", null, e -> settings.clearRecentFolders()));
    }

    /** "/home/me/…/Photos/Balaton 2026": keeps the start and the end of a long path. */
    static String shortenPath(String path, int max) {
        if (path.length() <= max) {
            return path;
        }
        int keepEnd = max * 2 / 3;
        int keepStart = max - keepEnd - 1;
        return path.substring(0, keepStart) + "…" + path.substring(path.length() - keepEnd);
    }

    /** The guided tour over the main window (first start, and Help → Show tutorial). */
    private void startTutorial() {
        if (null != tutorial && tutorial.isRunning()) {
            return;
        }
        settings.setTutorialShown(true);
        boolean[] samples = {false};
        GeoPosition[] mapAt = new GeoPosition[1];
        int[] zoomAt = new int[1];
        String[] coordinateAt = new String[1];
        int[] changesAt = new int[1];
        String ctrl = isMac() ? "Cmd" : "Ctrl";
        List<Tutorial.Step> steps = new ArrayList<>();
        steps.add(new Tutorial.Step(null, "Welcome to " + APP_NAME + "!",
                "This short tour shows how to put your photos on the map. It takes about two minutes, and you can "
                        + "leave it any time with <b>Skip tour</b> or Esc.<br><br>"
                        + "Try it on a few <b>sample photos</b> (a weekend at Lake Balaton) - your own photos stay "
                        + "untouched - or follow along with your own.",
                null, null, List.of(
                new Tutorial.Choice("Use sample photos", () -> {
                    if (openSamplePhotos()) {
                        samples[0] = true;
                        tutorial.next();
                    }
                }),
                new Tutorial.Choice("Use my own photos", () -> tutorial.next()),
                new Tutorial.Choice("Skip tour", () -> tutorial.close()))));
        steps.add(Tutorial.Step.action(() -> tfFolder.getParent(), "Open a folder",
                "Type the path of a folder of photos here and press <b>Open</b>, or click <b>...</b> to pick it "
                        + "(File → Open folder, " + ctrl + "+O).<br><br>With the sample photos, it's already open.",
                null, () -> !samples[0] && listModel.getSize() > 0 && !busy));
        steps.add(Tutorial.Step.explain(this::photoView, "Your photos",
                "These are the photos in the folder. <font color='#2a9d3a'><b>Green</b></font> ones already have a "
                        + "location, <font color='#d33'><b>red</b></font> ones don't yet.<br><br>"
                        + "Tick <b>Only without location</b> above to see just the red ones."));
        steps.add(Tutorial.Step.action(this::photoView, "Select a photo",
                "Click a red photo to select it. With the sample photos, try <b>IMG_4515.jpg</b>: the abbey in "
                        + "Tihany.",
                null, () -> !selection.isEmpty()));
        steps.add(Tutorial.Step.explain(() -> pnThumbnail.getParent(), "The photo and its details",
                "Here's the photo and its metadata. Double-click a value to change it: the date taken, camera, "
                        + "artist, description, altitude and more - for one photo or for all selected ones."));
        steps.add(Tutorial.Step.action(() -> tfSearch, "Find the place",
                "Type a place here and press Enter, e.g. <b>Tihany Abbey</b>. You can also drag the map and zoom "
                        + "with the mouse wheel.",
                () -> {
                    mapAt[0] = mapViewer.getCenterPosition();
                    zoomAt[0] = mapViewer.getZoom();
                },
                () -> mapViewer.getZoom() != zoomAt[0] || TrackMatcher.distanceMetres(mapAt[0],
                        mapViewer.getCenterPosition()) > 50));
        steps.add(Tutorial.Step.action(() -> mapViewer, "Mark the spot",
                "<b>Right-click</b> the exact spot where the photo was taken. A pin shows the new location.<br><br>"
                        + "You can also type a coordinate in the field below the map and press <b>Go!</b>",
                () -> coordinateAt[0] = tfCoordinate.getText(),
                () -> !tfCoordinate.getText().isBlank() && !tfCoordinate.getText().equals(coordinateAt[0])));
        steps.add(Tutorial.Step.action(() -> btnSave, "Save it",
                "Press <b>Save</b> (" + ctrl + "+S) to write the location into the photo.",
                () -> changesAt[0] = historyChanges.get(),
                () -> historyChanges.get() != changesAt[0] && !busy));
        steps.add(Tutorial.Step.explain(() -> btnUndo, "Changed your mind?",
                "The photo is green now. <b>Undo</b> (" + ctrl + "+Z) puts photos back exactly as they were - a "
                        + "whole batch at once.<br><br>Before a photo is changed for the first time, a copy of the "
                        + "original is also kept next to it as <i>name.bak</i>."));
        steps.add(Tutorial.Step.explain(this::photoView, "Many photos at once",
                "Select several photos with Shift- or " + ctrl + "-click: the location you set goes to all of "
                        + "them when you save.<br><br>"
                        + ctrl + "+C and " + ctrl + "+V on this list copy one photo's location to others."));
        steps.add(Tutorial.Step.explain(() -> file, "Tag a whole trip from a GPS track",
                "Recorded where you went with a phone, watch or GPS logger? <b>File → Geotag from GPX</b> ("
                        + ctrl + "+G) places all photos at once from the track's times."
                        + "<br><br>The sample folder has one to try: <b>balaton.gpx</b> - <i>Add GPX files</i> opens "
                        + "right in the photos' folder."));
        steps.add(Tutorial.Step.explain(() -> view, "Relive the trip",
                "<b>View → Play photos</b> (F5) shows the photos in order with a map, and <b>Travel mode</b> "
                        + "(Shift+F5) plays the trip as a short film. Both can be saved as a video.<br><br>"
                        + "<b>Show photos on map</b> puts all photos of the folder on the map."));
        steps.add(Tutorial.Step.explain(null, "That's it!",
                "You can see this tour again any time under <b>Help → Show tutorial</b>."
                        + (isMac() ? "" : " Settings (" + ctrl + "+,) has the theme, map layer and backups.")
                        + "<br><br>Have fun putting your photos on the map!"));
        tutorial = new Tutorial(frame, steps, () -> tutorial = null);
        tutorial.start();
    }

    /** Opens a fresh copy of the sample photos; the folder last opened by the user stays the remembered one. */
    private boolean openSamplePhotos() {
        try {
            Path dir = SampleTrip.copy();
            tfFolder.setText(dir.toString());
            openFolder(false);
            return true;
        } catch (IOException e) {
            showError("Couldn't copy the sample photos:\n" + e.getMessage());
            return false;
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
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
        styleBanner();
        lThumbs.setFixedCellHeight(ThumbnailRenderer.cellHeight(lThumbs.getFont(), lThumbs));
    }

    /** Enables the actions that make sense for the current selection, and updates the status line. */
    private void updateActions() {
        boolean canWrite = !busy && selection.stream().anyMatch(ImageFile::isWritable);
        btnSave.setEnabled(canWrite);
        miSave.setEnabled(canWrite);
        miRemove.setEnabled(canWrite && selection.stream().anyMatch(ImageFile::hasExifGPS));
        miShiftTime.setEnabled(canWrite && selection.stream().anyMatch(p -> null != p.getTaken()));
        miPaste.setEnabled(!busy);
        miGeotag.setEnabled(!busy && !listModel.getAll().isEmpty());
        miExportGpx.setEnabled(!busy && listModel.getAll().stream().anyMatch(ImageFile::hasExifGPS));
        miPlayback.setEnabled(listModel.getAll().stream().anyMatch(p -> null != p.getTaken()));
        miTravel.setEnabled(miPlayback.isEnabled());
        btnOpen.setEnabled(!busy);
        btnBrowse.setEnabled(!busy);
        chkOnlyWithoutLocation.setEnabled(!busy);
        jtExif.setEnabled(!busy);
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
        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.add(createExifToolBanner(), BorderLayout.NORTH);
        north.add(top, BorderLayout.CENTER);

        JPanel filePanel = new JPanel(new BorderLayout(0, 2));
        filePanel.add(chkOnlyWithoutLocation, BorderLayout.NORTH);
        photoTabs.addTab("List", new JScrollPane(lFiles));
        photoTabs.addTab("Thumbnails", new JScrollPane(lThumbs));
        filePanel.add(photoTabs, BorderLayout.CENTER);
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

        centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePanel, mapSplit);
        centerSplit.setDividerSize(5);

        JPanel coordinatePanel = new JPanel(new BorderLayout(4, 0));
        coordinatePanel.add(cbMapType, BorderLayout.WEST);
        coordinatePanel.add(tfCoordinate, BorderLayout.CENTER);
        JPanel coordinateExtras = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        coordinateExtras.add(btnCoordinate);
        coordinateExtras.add(new JLabel("  Altitude:"));
        coordinateExtras.add(tfAltitude);
        coordinateExtras.add(new JLabel("m   Direction:"));
        coordinateExtras.add(tfDirection);
        coordinateExtras.add(new JLabel("°"));
        coordinatePanel.add(coordinateExtras, BorderLayout.EAST);

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
        mainPanel.add(north, BorderLayout.NORTH);
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
        openFolder(true);
    }

    /** @param remember whether this becomes the last and a recent folder (not for the tutorial's sample photos) */
    private void openFolder(boolean remember) {
        if (null != geotagDialog) {
            geotagDialog.dispose();
        }
        String dir = tfFolder.getText();
        File[] files = new File(dir).listFiles(f -> f.isFile() && backend.canRead(f.toPath()));
        if (files == null) {
            showError("Couldn't open folder:\n" + dir);
            return;
        }
        Arrays.sort(files);
        if (remember) {
            settings.setLastDirectory(dir);
            settings.addRecentFolder(new File(dir).getAbsolutePath());
        }

        setBusy(true);
        btnCancel.setVisible(false);
        progress.setValue(0);
        progress.setMaximum(files.length);
        new SwingWorker<List<ImageFile>, Integer>() {
            @Override
            protected List<ImageFile> doInBackground() {
                List<ImageFile> loaded = new ArrayList<>();
                for (File f : files) {
                    ImageFile image = new ImageFile(f, backend);
                    image.setTripMark(tripMarks.get(f.toPath()));
                    loaded.add(image);
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
                    thumbnails.clear();
                    listModel.setAll(get());
                    if (photoTabs.getSelectedIndex() == 1) {
                        fitTwoThumbnailColumns();
                    }
                    markerLayer.setPhotos(listModel.getAll());
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

    /**
     * What Save would write: the picked location, and altitude/direction if they were edited. Returns
     * {@code null} (after telling the user) if an edited value is invalid.
     */
    private MetadataChanges pendingChanges() {
        MetadataChanges changes = new MetadataChanges();
        GeoPosition position = pendingPosition();
        if (null != position) {
            changes.position(position);
        }
        try {
            if (altitudeEdited) {
                changes.altitude(MetadataTableModel.parseAltitude(tfAltitude.getText()));
            }
            if (directionEdited) {
                changes.direction(MetadataTableModel.parseDirection(tfDirection.getText()));
            }
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return null;
        }
        return changes;
    }

    private void saveSelected() {
        List<ImageFile> targets = selection;
        if (busy || targets.isEmpty()) {
            return;
        }
        MetadataChanges changes = pendingChanges();
        if (null == changes) {
            return;
        }
        GeoPosition position = changes.getPosition();
        if (null == position && !altitudeEdited && !directionEdited) {
            showError("Nothing to save yet: right-click on the map, enter a coordinate or paste a location, "
                    + "or change the altitude or direction.");
            return;
        }
        long withLocation = targets.stream().filter(ImageFile::hasExifGPS).count();
        if (null != position && targets.size() > 1 && withLocation > 0 && !confirm(withLocation + " of the "
                + targets.size() + " selected photos already " + (withLocation == 1 ? "has" : "have")
                + " a location.\nReplace " + (withLocation == 1 ? "it" : "them") + "?", "Replace locations")) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (null != position) {
            parts.add("location");
        }
        if (altitudeEdited) {
            parts.add("altitude");
        }
        if (directionEdited) {
            parts.add("direction");
        }
        String what = targets.size() == 1 ? targets.get(0).getFile().getName() : targets.size() + " photos";
        runBatch("Set " + String.join(", ", parts) + " of " + what, targets, image -> image.apply(changes));
        altitudeEdited = false;
        directionEdited = false;
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

    private void shiftTime() {
        if (busy || selection.isEmpty()) {
            return;
        }
        ShiftTimeDialog dialog = new ShiftTimeDialog(frame, selection);
        java.time.Duration shift = dialog.showDialog();
        List<ImageFile> targets = dialog.getPhotos();
        if (null == shift || targets.isEmpty()) {
            return;
        }
        String what = targets.size() == 1 ? targets.get(0).getFile().getName() : targets.size() + " photos";
        runBatch("Shift date/time of " + what + " by " + PhotoTime.formatOffset(shift), targets,
                image -> image.apply(new MetadataChanges().shiftTime(shift)));
    }

    private boolean confirm(String message, String title) {
        return JOptionPane.showConfirmDialog(frame, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Applies an edit to photos in the background, with progress, Cancel, a failure summary and undo. */
    private void runBatch(String description, List<ImageFile> requested, PhotoWriter.Edit edit) {
        List<ImageFile> targets = requested.stream().filter(ImageFile::isWritable).toList();
        int readOnly = requested.size() - targets.size();
        if (targets.isEmpty()) {
            showError("The selected files are read-only: their file type needs ExifTool (see the banner at the top).");
            return;
        }
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
                    if (readOnly > 0) {
                        lblStatus.setText(readOnly + (readOnly == 1 ? " file was" : " files were")
                                + " skipped: needs ExifTool");
                    }
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
        // Positions may have changed
        markerLayer.recompute();
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

    /** A photo marker was clicked: select that photo in the list, un-hiding it if the filter hides it. */
    private void selectFromMap(ImageFile photo) {
        if (listModel.indexOf(photo) < 0 && chkOnlyWithoutLocation.isSelected()) {
            chkOnlyWithoutLocation.setSelected(false);
            listModel.setOnlyWithoutLocation(false);
        }
        int index = listModel.indexOf(photo);
        if (index >= 0) {
            lFiles.setSelectedIndex(index);
            photoView().ensureIndexIsVisible(index);
        }
    }

    /**
     * Writes every opened photo that has both a location and a date as a GPX waypoint. GPX times are UTC: the
     * photo's own recorded offset is used, else the camera time zone set in the Geotag dialog.
     */
    private void exportGpx() {
        List<ImageFile> all = listModel.getAll();
        java.time.ZoneId zone = settings.getCameraZone();
        List<GpxWriter.Waypoint> waypoints = new ArrayList<>();
        int noLocation = 0;
        int noDate = 0;
        for (ImageFile photo : all) {
            if (!photo.hasExifGPS()) {
                noLocation++;
            } else if (null == photo.getTaken()) {
                noDate++;
            } else {
                waypoints.add(new GpxWriter.Waypoint(photo.getFile().getName(), photo.getGp(),
                        PhotoTime.toInstant(photo.getTaken(), photo.getTakenOffset(), zone, java.time.Duration.ZERO),
                        photo.getAltitude(), photo.getText(TextTag.DESCRIPTION)));
            }
        }
        if (waypoints.isEmpty()) {
            showError("None of the " + all.size() + " photos has both a location and a date.");
            return;
        }
        File folder = new File(tfFolder.getText());
        JFileChooser chooser = new JFileChooser(folder);
        chooser.setDialogTitle("Export photos as GPX");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("GPX files (*.gpx)", "gpx"));
        chooser.setSelectedFile(new File(folder, folder.getName() + ".gpx"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")) {
            target = new File(target.getParentFile(), target.getName() + ".gpx");
        }
        if (target.exists() && !confirm(target.getName() + " already exists. Replace it?", "Export photos as GPX")) {
            return;
        }
        try {
            GpxWriter.write(target.toPath(), waypoints, USER_AGENT);
        } catch (IOException e) {
            showError("Couldn't write " + target.getName() + ":\n" + e.getMessage());
            return;
        }
        StringBuilder msg = new StringBuilder("Exported " + waypoints.size()
                + (waypoints.size() == 1 ? " photo" : " photos") + " to " + target.getName() + ".");
        if (noLocation + noDate > 0) {
            msg.append("\nSkipped: ");
            List<String> skipped = new ArrayList<>();
            if (noLocation > 0) {
                skipped.add(noLocation + " without location");
            }
            if (noDate > 0) {
                skipped.add(noDate + " without date");
            }
            msg.append(String.join(", ", skipped)).append('.');
        }
        msg.append("\n\nCamera times were converted to UTC from ").append(zone.getId())
                .append(" (the camera time zone of the Geotag dialog), unless a photo recorded its own.");
        JOptionPane.showMessageDialog(frame, msg.toString(), "Export photos as GPX", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Plays the selected photos (or all opened ones if at most one is selected) in a separate window. */
    private void openPlayback() {
        List<ImageFile> photos = selection.size() > 1 ? selection : listModel.getAll();
        PlaybackSequence sequence = new PlaybackSequence(photos);
        if (sequence.isEmpty()) {
            showError(sequence.getSkipped() > 0
                    ? "Nothing to play: these photos are skipped in trips or have no date.\n"
                    + "Right-click a photo and untick Skip in trips to show it again."
                    : "None of these photos has a date, so they can't be played back in order.");
            return;
        }
        PlaybackWindow window = new PlaybackWindow(frame, sequence, settings.getMapLayer().createInfo(), tileCache,
                USER_AGENT, settings);
        List<String> leftOut = new ArrayList<>();
        if (sequence.getWithoutDate() > 0) {
            leftOut.add(sequence.getWithoutDate() + " without a date");
        }
        if (sequence.getSkipped() > 0) {
            leftOut.add(sequence.getSkipped() + " skipped");
        }
        if (!leftOut.isEmpty()) {
            lblStatus.setText("Left out: " + String.join(", ", leftOut));
        }
        window.setVisible(true);
    }

    /** Travel mode for the selected photos (or all opened ones), following the last loaded GPX track if any. */
    private void openTravel() {
        List<ImageFile> photos = selection.size() > 1 ? selection : listModel.getAll();
        if (photos.stream().noneMatch(p -> null != p.getTaken())) {
            showError("None of these photos has a date, so the trip can't be played back.");
            return;
        }
        if (photos.stream().noneMatch(p -> null != p.getTaken() && TripMark.SKIP != p.getTripMark())) {
            showError("All of these photos are skipped in trips.\n"
                    + "Right-click a photo and untick Skip in trips to show it again.");
            return;
        }
        new TravelWindow(frame, photos, settings, lastTracks, settings.getMapLayer().createInfo(),
                tileCache, USER_AGENT).setVisible(true);
    }

    /** Opens the GPX geotagging dialog for the selected photos, or all opened photos if at most one is selected. */
    private void openGeotag() {
        if (busy || listModel.getAll().isEmpty()) {
            return;
        }
        if (null != geotagDialog && geotagDialog.isDisplayable()) {
            geotagDialog.toFront();
            return;
        }
        List<ImageFile> photos = selection.size() > 1 ? selection : listModel.getAll();
        geotagDialog = new GeotagDialog(frame, photos, settings, new GeotagDialog.Host() {
            @Override
            public void showPreview(List<Track> tracks, List<GeoPosition> proposed, GeoPosition highlight) {
                if (!tracks.isEmpty()) {
                    lastTracks = List.copyOf(tracks);
                }
                trackPainter.set(tracks, proposed, highlight);
                mapViewer.repaint();
            }

            @Override
            public void clearPreview() {
                trackPainter.clear();
                mapViewer.repaint();
            }

            @Override
            public void zoomTo(List<GeoPosition> positions) {
                if (!positions.isEmpty()) {
                    mapViewer.zoomToBestFit(new HashSet<>(positions), 0.8);
                }
            }

            @Override
            public GeoPosition pickedPosition() {
                return pendingPosition();
            }

            @Override
            public void apply(Map<ImageFile, TrackMatcher.Match> matches, boolean writeAltitude) {
                List<ImageFile> targets = List.copyOf(matches.keySet());
                runBatch("Geotag " + targets.size() + (targets.size() == 1 ? " photo" : " photos") + " from GPX",
                        targets, image -> {
                            TrackMatcher.Match match = matches.get(image);
                            image.savePosition(match.position(), writeAltitude ? match.elevation() : null);
                        });
            }
        });
        geotagDialog.setVisible(true);
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

    /** The application icon in several sizes, for the title bar, task bar and Alt+Tab. */
    static List<Image> appIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            try (var in = ExifTweaker.class.getResourceAsStream("icons/icon-" + size + ".png")) {
                if (null != in) {
                    icons.add(ImageIO.read(in));
                }
            } catch (IOException ignored) {
                // no icon of that size
            }
        }
        // macOS shows the Dock icon from the app bundle; when started from the jar, set it here
        if (!icons.isEmpty() && Taskbar.isTaskbarSupported()
                && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            try {
                Taskbar.getTaskbar().setIconImage(icons.get(icons.size() - 1));
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // not available on this platform
            }
        }
        return icons;
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
            frame.setIconImages(appIcons());
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
                SampleTrip.deleteAll();
            }, "exiftweaker-cleanup"));
            frame.setSize(1200, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            if (app.settings.isThumbnailView()) {
                SwingUtilities.invokeLater(app::fitTwoThumbnailColumns);
            }
            if (!app.settings.isTutorialShown()) {
                SwingUtilities.invokeLater(app::startTutorial);
            }
        });
    }

    private void elementSelected() {
        selection = lFiles.getSelectedValuesList();
        int lead = lFiles.getLeadSelectionIndex();
        selected = lead >= 0 && lFiles.isSelectedIndex(lead) ? listModel.getElementAt(lead) : lFiles.getSelectedValue();
        GeoPosition pending = pendingPosition();
        markerLayer.setHighlighted(selection);
        updateActions();
        waypoints.clear();
        pnThumbnail.setImage(null);
        boolean keepEdits = selection.size() > 1;
        if (!keepEdits) {
            altitudeEdited = false;
            directionEdited = false;
        }
        if (!altitudeEdited) {
            setField(tfAltitude, null == selected || null == selected.getAltitude() ? ""
                    : MetadataTableModel.number(selected.getAltitude()));
        }
        if (!directionEdited) {
            setField(tfDirection, null == selected || null == selected.getDirection() ? ""
                    : MetadataTableModel.number(selected.getDirection()));
        }
        if (null == selected) {
            metadataModel.clear();
            tfCoordinate.setText("");
        } else {
            loadThumbnail(selected);
            metadataModel.setPhotos(selection, selected);
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
        updateDirectionOverlay();
        mapViewer.repaint();
    }

    private void loadThumbnail(ImageFile image) {
        if (null != thumbnailWorker) {
            thumbnailWorker.cancel(true);
        }
        thumbnailWorker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() throws IOException {
                return PhotoLoader.load(image, THUMBNAIL_MAX_SIZE);
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    BufferedImage thumbnail = get();
                    if (null != thumbnail) {
                        pnThumbnail.setImage(thumbnail);
                    } else {
                        pnThumbnail.setMessage(backend.needsExifTool(image.getPath())
                                ? "No preview - needs ExifTool" : "No preview for this file type");
                    }
                } catch (InterruptedException | ExecutionException e) {
                    pnThumbnail.setMessage("No preview");
                }
            }
        };
        thumbnailWorker.execute();
    }

    /** Photos dragged onto the map: like right-clicking there with those photos selected. */
    private void photosDropped(List<ImageFile> photos, GeoPosition position) {
        if (!new HashSet<>(photos).equals(new HashSet<>(selection))) {
            reselect(photos);
        }
        selectPosition(position);
        lblStatus.setText((photos.size() == 1 ? "1 photo" : photos.size() + " photos") + " placed - Save to keep");
        lblStatus.setToolTipText("The location is written to the photos when you press Save");
    }

    private void selectPosition(GeoPosition position) {
        waypoints.removeIf(w -> w instanceof SelectionWaypoint);
        waypoints.add(new SelectionWaypoint(position));
        waypointPainter.setWaypoints(waypoints);
        tfCoordinate.setText(PositionUtil.getPositionString(position));
        updateDirectionOverlay();
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
        directionOverlay = new DirectionOverlay(mapViewer);
        directionOverlay.setOnDrag(degrees -> tfDirection.setText(MetadataTableModel.number(degrees)));
        markerLayer = new PhotoMarkerLayer(mapViewer, settings::getMaxPhotoMarkers, this::selectFromMap);
        org.jxmapviewer.painter.Painter<JXMapViewer> dropPin = PhotoDrag.enableDrop(mapViewer, this::photosDropped);
        mapViewer.setOverlayPainter(new CompoundPainter<>(trackPainter, markerLayer, directionOverlay, waypointPainter, dropPin,
                new AttributionPainter()));
        markerLayer.setEnabled(settings.isShowPhotoMarkers());

        MouseInputListener mia = directionOverlay.wrap(new PanMouseInputListener(mapViewer));
        mapViewer.addMouseListener(mia);
        mapViewer.addMouseMotionListener(mia);
        mapViewer.addMouseListener(new CenterMapListener(mapViewer));
        mapViewer.addMouseWheelListener(new ZoomMouseWheelListenerCursor(mapViewer));
        mapViewer.addKeyListener(new PanKeyListener(mapViewer));
        mapViewer.addMouseListener(new SelectionAdapter(mapViewer, this::selectPosition));
    }
}
