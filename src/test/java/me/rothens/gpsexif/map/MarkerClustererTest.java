package me.rothens.gpsexif.map;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MarkerClustererTest {

    private static final Rectangle2D VIEW = new Rectangle2D.Double(1000, 1000, 800, 600);

    private static MarkerClusterer.Input<Integer> at(int id, double x, double y) {
        return new MarkerClusterer.Input<>(id, new Point2D.Double(x, y));
    }

    @Test
    void thousandsOfPhotosAtOneSpotBecomeOneMarker() {
        List<MarkerClusterer.Input<Integer>> inputs = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            inputs.add(at(i, 1400 + (i % 7) * 0.1, 1300));
        }
        MarkerClusterer.Result<Integer> result = MarkerClusterer.cluster(inputs, VIEW, 60, 200);
        assertEquals(1, result.clusters().size());
        assertEquals(2000, result.clusters().get(0).size());
        assertFalse(result.isCapped());
    }

    @Test
    void distantPhotosStaySeparateAndCloseOnesMerge() {
        MarkerClusterer.Result<Integer> result = MarkerClusterer.cluster(List.of(
                at(1, 1100, 1100), at(2, 1500, 1400),
                // straddles a cell border (1259 | 1261) but only 2 px apart -> one cluster
                at(3, 1259, 1500), at(4, 1261, 1500)), VIEW, 60, 200);
        assertEquals(3, result.clusters().size());
        MarkerClusterer.Cluster<Integer> pair = result.clusters().get(0);
        assertEquals(List.of(3, 4), pair.items().stream().sorted().toList());
        assertEquals(1260, pair.center().getX(), 1e-9);
    }

    @Test
    void onlyCountsMarkersInView() {
        MarkerClusterer.Result<Integer> result = MarkerClusterer.cluster(List.of(
                at(1, 1100, 1100), at(2, 5000, 5000), at(3, 1020 - 70, 1100)), VIEW, 60, 200);
        assertEquals(List.of(1), result.clusters().get(0).items());
        assertEquals(1, result.totalInView());
    }

    @Test
    void capKeepsLargestClusters() {
        List<MarkerClusterer.Input<Integer>> inputs = new ArrayList<>();
        int id = 0;
        // 8 x 6 grid of single photos 100 px apart, plus a group of 5 in one spot
        for (int gx = 0; gx < 8; gx++) {
            for (int gy = 0; gy < 6; gy++) {
                inputs.add(at(id++, 1030 + gx * 100, 1030 + gy * 100));
            }
        }
        for (int i = 0; i < 5; i++) {
            inputs.add(at(id++, 1780, 1580));
        }
        MarkerClusterer.Result<Integer> result = MarkerClusterer.cluster(inputs, VIEW, 60, 10);
        assertEquals(10, result.clusters().size());
        assertEquals(49, result.totalInView());
        assertTrue(result.isCapped());
        assertEquals(5, result.clusters().get(0).size(), "the group is kept first");
    }

    @Test
    void deterministic() {
        Random random = new Random(42);
        List<MarkerClusterer.Input<Integer>> inputs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            inputs.add(at(i, 1000 + random.nextDouble() * 800, 1000 + random.nextDouble() * 600));
        }
        var a = MarkerClusterer.cluster(inputs, VIEW, 60, 50);
        var b = MarkerClusterer.cluster(new ArrayList<>(inputs), VIEW, 60, 50);
        assertEquals(a.clusters().stream().map(c -> c.items()).toList(), b.clusters().stream().map(c -> c.items()).toList());
        int total = a.clusters().stream().mapToInt(MarkerClusterer.Cluster::size).sum();
        assertTrue(total <= 500);
        // No two drawn clusters are closer than a cell
        for (var c1 : a.clusters()) {
            for (var c2 : a.clusters()) {
                if (c1 != c2) {
                    assertTrue(c1.center().distance(c2.center()) >= 60 * 0.5,
                            c1.center() + " vs " + c2.center());
                }
            }
        }
    }
}
