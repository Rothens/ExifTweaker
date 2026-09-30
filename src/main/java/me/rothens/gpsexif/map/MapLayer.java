package me.rothens.gpsexif.map;

import org.jxmapviewer.OSMTileFactoryInfo;
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
