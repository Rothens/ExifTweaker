package me.rothens.gpsexif.map;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactory;
import org.jxmapviewer.viewer.TileFactoryInfo;

import java.util.function.Supplier;

/**
 * The available map layers. Adding a layer is a single entry here. The enum names are persisted in the
 * preferences, so don't rename them.
 */
public enum MapLayer {
    OPENSTREETMAP("OpenStreetMap", OSMTileFactoryInfo::new),
    ESRI_WORLD_IMAGERY("Satellite (Esri)", EsriWorldImageryTileFactoryInfo::new);

    private final String displayName;
    private final Supplier<TileFactoryInfo> info;

    MapLayer(String displayName, Supplier<TileFactoryInfo> info) {
        this.displayName = displayName;
        this.info = info;
    }

    public TileFactoryInfo createInfo() {
        return info.get();
    }

    /**
     * Switches the map to another tile provider, keeping the zoom level and the visible area.
     * {@link JXMapViewer#setTileFactory} on its own jumps to the provider's default zoom, which is 0 (the most
     * detailed level) for the providers we use - so the map zoomed all the way in on every switch.
     */
    public static void switchTileFactory(JXMapViewer map, TileFactory factory) {
        if (map.getTileFactory() == factory) {
            return;
        }
        int zoom = map.getZoom();
        GeoPosition center = map.getCenterPosition();
        map.setTileFactory(factory);
        TileFactoryInfo info = factory.getInfo();
        map.setZoom(Math.max(info.getMinimumZoomLevel(), Math.min(info.getMaximumZoomLevel(), zoom)));
        map.setCenterPosition(center);
    }

    /** Parses a persisted layer name, falling back to {@link #OPENSTREETMAP}. */
    public static MapLayer fromName(String name) {
        for (MapLayer layer : values()) {
            if (layer.name().equals(name)) {
                return layer;
            }
        }
        return OPENSTREETMAP;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
