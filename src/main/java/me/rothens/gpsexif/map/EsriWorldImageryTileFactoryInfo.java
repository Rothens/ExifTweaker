package me.rothens.gpsexif.map;

import org.jxmapviewer.viewer.TileFactoryInfo;

/** Esri World Imagery satellite tiles. Replaces the retired Bing/VirtualEarth layer. */
public class EsriWorldImageryTileFactoryInfo extends TileFactoryInfo {

    private static final int MAX_ZOOM = 19;

    public EsriWorldImageryTileFactoryInfo() {
        super("Esri World Imagery", 0, MAX_ZOOM, MAX_ZOOM, 256, true, true,
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile", "x", "y", "z");
    }

    @Override
    public String getTileUrl(int x, int y, int zoom) {
        // JXMapViewer counts zoom levels the other way round (0 = most detailed); Esri uses z/y/x order.
        int z = MAX_ZOOM - zoom;
        return baseURL + "/" + z + "/" + y + "/" + x;
    }

    @Override
    public String getAttribution() {
        return "Powered by Esri | Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community";
    }

    @Override
    public String getLicense() {
        return "Esri Master License Agreement";
    }
}
