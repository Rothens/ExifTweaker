package me.rothens.gpsexif;

import me.rothens.gpsexif.model.ExifTableModel;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.model.ImageListRenderer;
import me.rothens.gpsexif.util.PositionUtil;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.VirtualEarthTileFactoryInfo;
import org.jxmapviewer.input.CenterMapListener;
import org.jxmapviewer.input.PanKeyListener;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.viewer.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.MouseInputListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.util.*;
import java.util.List;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ExifTweaker {
    private JTextField tfFolder;
    private JButton btnBrowse;
    private JButton btnOpen;
    private JList lFiles;
    private JButton btnSave;
    private JProgressBar progress;
    private JPanel mainPanel;
    private JPanel pnMap;
    private JPanel pnThumbnail;
    private JTable jtExif;
    private JComboBox cbMapType;
    private JTextField tfCoordinate;
    private JButton btnCoordinate;
    private WaypointPainter<Waypoint> waypointPainter;
    private Set<Waypoint> waypoints;
    private ImageFile selected;
    private BufferedImage selectedImg;
    private ExifTableModel exifTableModel;
    private List<DefaultTileFactory> factories;
    private JXMapViewer mapViewer;
    private FileOpenListener fol;
    private static JFrame frame;

    public ExifTweaker() {
        fol = new FileOpenListener() {

            @Override
            public void fileOpened() {
                progress.setValue(progress.getValue()+1);
            }

            @Override
            public void finished(DefaultListModel<ImageFile> model) {
                lFiles.setModel(model);
                setButtons(true);
            }
        };
        btnOpen.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setButtons(false);
                try {
                    File[] f = new File(tfFolder.getText()).listFiles(new FilenameFilter() {

                        @Override
                        public boolean accept(File dir, String name) {
                            return name.toLowerCase().endsWith(".jpg");
                        }
                    });
                    progress.setValue(0);
                    progress.setMaximum(f.length);
                    new FileOpenThread(f, fol).start();
                } catch (Exception ex){
                    JOptionPane.showConfirmDialog(frame, "Error while opening folder!", "ERROR!", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE);
                    setButtons(true);
                }


            }
        });

        btnSave.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (null != selected) {
                    for (Waypoint w : waypoints) {
                        if (w instanceof SelectionWaypoint) {
                            selected.setGp(w.getPosition());
                            selected.save();
                            lFiles.repaint();
                            return;
                        }
                    }
                }
            }
        });

        btnBrowse.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final JFileChooser jfc = new JFileChooser();
                jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int i = jfc.showOpenDialog(frame);
                if(i == JFileChooser.APPROVE_OPTION){
                    tfFolder.setText(jfc.getSelectedFile().toString());
                }
            }
        });


        cbMapType.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mapViewer.setTileFactory(factories.get(cbMapType.getSelectedIndex()));
            }
        });

        //TODO: create position parsing methods for this
        btnCoordinate.setEnabled(false);

    }

    private void setButtons(boolean enabled){
        btnOpen.setEnabled(enabled);
        btnBrowse.setEnabled(enabled);
        btnSave.setEnabled(enabled);
    }

    public static void main(String[] args) {
        frame = new JFrame("ExifTweaker");

        frame.setContentPane(new ExifTweaker().mainPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setSize(1200, 700);
        frame.setResizable(false);
        frame.setVisible(true);

    }

    private void createUIComponents() {
        pnMap = new JXMapViewer();
        lFiles = new JList<>();
        pnThumbnail = new ImagePanel();
        exifTableModel = new ExifTableModel();
        jtExif = new JTable(exifTableModel);
        lFiles.setCellRenderer(new ImageListRenderer());
        lFiles.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    elementSelected();
                }
            }
        });
        initMap();
    }

    private void elementSelected() {

        selected = (ImageFile) lFiles.getSelectedValue();
        try {
            selectedImg = ImageIO.read(selected.getFile());
            ((ImagePanel) pnThumbnail).setImage(selectedImg);

        } catch (Exception e) {

        }
        exifTableModel.setData(selected.getExifData());
        waypoints.clear();
        if (selected.hasExifGPS()) {
            ((JXMapViewer) pnMap).setAddressLocation(selected.getGp());
            waypoints.add(new DefaultWaypoint(selected.getGp()));
            tfCoordinate.setText(PositionUtil.getPositionString(selected.getGp()));
        }
        waypointPainter.setWaypoints(waypoints);
        pnMap.repaint();
    }

    private void initMap() {
        mapViewer = (JXMapViewer) pnMap;
        File cacheDir = new File(System.getProperty("user.home") + File.separator + ".jxmapviewer2");
        factories = new ArrayList<>();
        factories.add(new DefaultTileFactory(new OSMTileFactoryInfo()));
        factories.add(new DefaultTileFactory(new VirtualEarthTileFactoryInfo(VirtualEarthTileFactoryInfo.HYBRID)));
        for (DefaultTileFactory tf : factories) {
            tf.setThreadPoolSize(8);
            LocalResponseCache.installResponseCache(tf.getInfo().getBaseURL(), cacheDir, false);

        }
        mapViewer.setTileFactory(factories.get(0));


        GeoPosition frankfurt = new GeoPosition(35.68, 139.71);
        mapViewer.setZoom(5);
        mapViewer.setAddressLocation(frankfurt);

        waypointPainter = new WaypointPainter<>();
        waypointPainter.setRenderer(new SelectionWaypointRenderer());
        mapViewer.setOverlayPainter(waypointPainter);

        waypoints = new HashSet<>();
        waypointPainter.setWaypoints(waypoints);

        MouseInputListener mia = new PanMouseInputListener(mapViewer);
        mapViewer.addMouseListener(mia);
        mapViewer.addMouseMotionListener(mia);

        mapViewer.addMouseListener(new CenterMapListener(mapViewer));

        mapViewer.addMouseWheelListener(new ZoomMouseWheelListenerCursor(mapViewer));

        mapViewer.addKeyListener(new PanKeyListener(mapViewer));

        final PointSelect ps = new PointSelect() {
            @Override
            public void onSelect(GeoPosition position) {
                Iterator<Waypoint> it = waypoints.iterator();
                while (it.hasNext()) {
                    if (it.next() instanceof SelectionWaypoint) {
                        it.remove();
                    }
                }
                waypoints.add(new SelectionWaypoint(position));
                waypointPainter.setWaypoints(waypoints);
                mapViewer.repaint();
            }
        };
        mapViewer.addMouseListener(new SelectionAdapter(mapViewer, ps));
    }
}
