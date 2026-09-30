package me.rothens.gpsexif.map;

import org.junit.jupiter.api.Test;
import org.jxmapviewer.viewer.TileFactoryInfo;

import static org.junit.jupiter.api.Assertions.*;

class MapLayerTest {

    @Test
    void esriUrlUsesZoomYxOrder() {
        TileFactoryInfo info = new EsriWorldImageryTileFactoryInfo();
        // JXMapViewer zoom 16 == standard zoom level 3
        assertEquals("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/3/2/4",
                info.getTileUrl(4, 2, 16));
    }

    @Test
    void everyLayerHasAttribution() {
        for (MapLayer layer : MapLayer.values()) {
            String attribution = layer.createInfo().getAttribution();
            assertNotNull(attribution, layer.name());
            assertFalse(attribution.isBlank(), layer.name());
        }
    }

    @Test
    void unknownNamesFallBackToOpenStreetMap() {
        assertEquals(MapLayer.ESRI_WORLD_IMAGERY, MapLayer.fromName("ESRI_WORLD_IMAGERY"));
        assertEquals(MapLayer.OPENSTREETMAP, MapLayer.fromName("VIRTUAL_EARTH"));
        assertEquals(MapLayer.OPENSTREETMAP, MapLayer.fromName(null));
    }
}
