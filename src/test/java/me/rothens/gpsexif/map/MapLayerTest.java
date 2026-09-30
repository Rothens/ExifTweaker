package me.rothens.gpsexif.map;

import org.junit.jupiter.api.Test;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
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

    @Test
    void switchingLayersKeepsZoomAndPosition() {
        DefaultTileFactory osm = new DefaultTileFactory(MapLayer.OPENSTREETMAP.createInfo());
        DefaultTileFactory esri = new DefaultTileFactory(MapLayer.ESRI_WORLD_IMAGERY.createInfo());
        JXMapViewer map = new JXMapViewer();
        map.setSize(800, 600);
        map.setTileFactory(osm);
        map.setZoom(5);
        map.setCenterPosition(new GeoPosition(47.4979, 19.0402));

        MapLayer.switchTileFactory(map, esri);
        assertSame(esri, map.getTileFactory());
        assertEquals(5, map.getZoom());
        assertEquals(47.4979, map.getCenterPosition().getLatitude(), 1e-3);
        assertEquals(19.0402, map.getCenterPosition().getLongitude(), 1e-3);

        MapLayer.switchTileFactory(map, osm);
        assertEquals(5, map.getZoom());
        assertEquals(47.4979, map.getCenterPosition().getLatitude(), 1e-3);
        assertEquals(19.0402, map.getCenterPosition().getLongitude(), 1e-3);
    }
}
