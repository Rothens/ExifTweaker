package me.rothens.gpsexif.map;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.viewer.Tile;
import org.jxmapviewer.viewer.TileFactory;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Helps render maps off screen (e.g. into a video): requests the tiles a map shows and waits for them. */
public final class MapTiles {

    private MapTiles() {
    }

    /**
     * The tiles {@code map} draws at its current size, center and zoom (the same ones its paint code asks for),
     * which also starts loading them. Call on the Swing thread.
     */
    public static List<Tile> request(JXMapViewer map) {
        List<Tile> tiles = new ArrayList<>();
        if (!map.isVisible() || map.getWidth() <= 0 || map.getHeight() <= 0) {
            return tiles;
        }
        TileFactory factory = map.getTileFactory();
        int zoom = map.getZoom();
        Rectangle viewport = map.getViewportBounds();
        int size = factory.getTileSize(zoom);
        Dimension mapSize = factory.getMapSize(zoom);
        int firstX = (int) Math.floor(viewport.getX() / size);
        int firstY = (int) Math.floor(viewport.getY() / size);
        int wide = viewport.width / size + 2;
        int high = viewport.height / size + 2;
        for (int x = firstX; x <= firstX + wide; x++) {
            for (int y = firstY; y <= firstY + high; y++) {
                if (y < 0 || y >= mapSize.getHeight()) {
                    continue;
                }
                int wrapped = Math.floorMod(x, (int) mapSize.getWidth());
                tiles.add(factory.getTile(wrapped, y, zoom));
            }
        }
        return tiles;
    }

    /** Waits for tiles, but only once per tile, so a tile that can't be loaded doesn't hold up every frame. */
    public static final class Waiter {
        private final java.util.Set<String> settled = new java.util.HashSet<>();
        private final java.util.Set<String> missing = new java.util.HashSet<>();
        private final long timeoutMillis;

        public Waiter(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        /** Waits (off the Swing thread) until the new tiles among {@code tiles} have loaded, failed or timed out. */
        public void await(List<Tile> tiles, BooleanSupplier cancelled) throws InterruptedException {
            List<Tile> fresh = tiles.stream().filter(t -> !settled.contains(key(t))).toList();
            if (fresh.isEmpty()) {
                return;
            }
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline && !cancelled.getAsBoolean()
                    && fresh.stream().anyMatch(t -> !t.isLoaded() && !t.loadingFailed())) {
                Thread.sleep(40);
            }
            for (Tile t : fresh) {
                settled.add(key(t));
                if (!t.isLoaded()) {
                    missing.add(key(t));
                }
            }
        }

        /** How many tiles couldn't be loaded in time. */
        public int getMissing() {
            return missing.size();
        }

        private static String key(Tile t) {
            return t.getZoom() + "/" + t.getX() + "/" + t.getY();
        }
    }
}
