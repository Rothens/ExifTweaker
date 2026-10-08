package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.GeoPosition;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.File;
import java.util.List;
import java.util.TooManyListenersException;
import java.util.function.BiConsumer;

/**
 * Dragging photos from the photo list or grid onto the map sets their location there, like a right-click on the
 * map. Dragged elsewhere (a file manager, an e-mail), they're the photo files.
 */
public final class PhotoDrag {

    /** The dragged photos, within ExifTweaker. */
    public static final DataFlavor PHOTOS = new DataFlavor(PhotoList.class, "ExifTweaker photos");

    /** Wrapper so the flavor has a concrete class. */
    public record PhotoList(List<ImageFile> photos) {
    }

    private PhotoDrag() {
    }

    /** Lets the selected photos of {@code list} be dragged. */
    public static void enableDrag(JList<ImageFile> list) {
        list.setDragEnabled(true);
        list.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                List<ImageFile> photos = list.getSelectedValuesList();
                return photos.isEmpty() ? null : new PhotoTransferable(photos);
            }
        });
    }

    /**
     * Accepts photos dropped on the map; shows a pin with the number of photos under the cursor while dragging.
     *
     * @param onDrop called with the photos and where they were dropped
     * @return the painter that draws the pin; add it to the map's overlay painters
     */
    public static Painter<JXMapViewer> enableDrop(JXMapViewer map, BiConsumer<List<ImageFile>, GeoPosition> onDrop) {
        DropPreview preview = new DropPreview();
        map.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (!support.isDrop() || !support.isDataFlavorSupported(PHOTOS)) {
                    return false;
                }
                support.setDropAction(COPY);
                support.setShowDropLocation(false);
                return true;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    PhotoList dropped = (PhotoList) support.getTransferable().getTransferData(PHOTOS);
                    Point p = support.getDropLocation().getDropPoint();
                    onDrop.accept(dropped.photos(), map.convertPointToGeoPosition(p));
                    return true;
                } catch (UnsupportedFlavorException | java.io.IOException e) {
                    return false;
                } finally {
                    preview.hide(map);
                }
            }
        });
        try {
            map.getDropTarget().addDropTargetListener(new DropTargetAdapter() {
                @Override
                public void dragOver(DropTargetDragEvent e) {
                    if (e.isDataFlavorSupported(PHOTOS)) {
                        int count = 1;
                        try {
                            count = ((PhotoList) e.getTransferable().getTransferData(PHOTOS)).photos().size();
                        } catch (Exception ignored) {
                            // keep 1
                        }
                        preview.show(map, map.convertPointToGeoPosition(e.getLocation()), count);
                    }
                }

                @Override
                public void dragExit(DropTargetEvent e) {
                    preview.hide(map);
                }

                @Override
                public void drop(DropTargetDropEvent e) {
                    preview.hide(map);
                }
            });
        } catch (TooManyListenersException e) {
            // no preview pin then; dropping still works
        }
        return preview;
    }

    /** The photos as themselves (in ExifTweaker), as files and as their names (elsewhere). */
    private record PhotoTransferable(List<ImageFile> photos) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{PHOTOS, DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            for (DataFlavor f : getTransferDataFlavors()) {
                if (f.equals(flavor)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (PHOTOS.equals(flavor)) {
                return new PhotoList(photos);
            }
            if (DataFlavor.javaFileListFlavor.equals(flavor)) {
                return photos.stream().map(ImageFile::getFile).map(File::getAbsoluteFile).toList();
            }
            if (DataFlavor.stringFlavor.equals(flavor)) {
                return String.join("\n", photos.stream().map(p -> p.getFile().getAbsolutePath()).toList());
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }

    /** A pin with the number of dragged photos, where they would land. */
    private static final class DropPreview implements Painter<JXMapViewer> {
        private volatile GeoPosition position;
        private volatile int count;

        void show(JXMapViewer map, GeoPosition position, int count) {
            this.position = position;
            this.count = count;
            map.repaint();
        }

        void hide(JXMapViewer map) {
            if (null != position) {
                position = null;
                map.repaint();
            }
        }

        @Override
        public void paint(Graphics2D g, JXMapViewer map, int width, int height) {
            GeoPosition at = position;
            if (null == at) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle viewport = map.getViewportBounds();
                Point2D p = map.getTileFactory().geoToPixel(at, map.getZoom());
                double x = p.getX() - viewport.getX();
                double y = p.getY() - viewport.getY();
                // A pin whose tip is the drop point
                Path2D pin = new Path2D.Double();
                pin.moveTo(x, y);
                pin.curveTo(x - 4, y - 12, x - 13, y - 18, x - 13, y - 28);
                pin.curveTo(x - 13, y - 36, x - 7, y - 42, x, y - 42);
                pin.curveTo(x + 7, y - 42, x + 13, y - 36, x + 13, y - 28);
                pin.curveTo(x + 13, y - 18, x + 4, y - 12, x, y);
                pin.closePath();
                g2.setColor(new Color(0, 0, 0, 70));
                g2.translate(2, 2);
                g2.fill(pin);
                g2.translate(-2, -2);
                g2.setColor(new Color(230, 70, 50, 230));
                g2.fill(pin);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(pin);
                g2.fill(new Ellipse2D.Double(x - 4.5, y - 32.5, 9, 9));
                if (count > 1) {
                    String text = Integer.toString(count);
                    Font font = g2.getFont().deriveFont(Font.BOLD, 11f);
                    FontMetrics fm = g2.getFontMetrics(font);
                    int w = Math.max(18, fm.stringWidth(text) + 8);
                    double bx = x + 8;
                    double by = y - 50;
                    g2.setColor(new Color(40, 110, 230));
                    g2.fill(new java.awt.geom.RoundRectangle2D.Double(bx, by, w, 18, 18, 18));
                    g2.setColor(Color.WHITE);
                    g2.setFont(font);
                    g2.drawString(text, (float) (bx + (w - fm.stringWidth(text)) / 2.0), (float) (by + 13));
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
