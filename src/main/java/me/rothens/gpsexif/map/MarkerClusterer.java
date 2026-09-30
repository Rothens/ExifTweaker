package me.rothens.gpsexif.map;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups map markers that would overlap into clusters, for the current zoom level and visible area.
 * <ol>
 *     <li>Only items inside the viewport (plus half a cell) are considered.</li>
 *     <li>Items are bucketed into square grid cells of {@code cellSize} pixels.</li>
 *     <li>Clusters whose centres ended up closer than {@code cellSize} (neighbouring cells) are merged, largest
 *     first.</li>
 *     <li>At most {@code cap} clusters are returned - the largest ones, so the most photos stay represented.</li>
 * </ol>
 * Pixel coordinates are JXMapViewer "world" pixels at one zoom level; the result is deterministic.
 */
public final class MarkerClusterer {

    public record Input<T>(T item, Point2D pixel) {
    }

    public record Cluster<T>(List<T> items, Point2D center) {
        public int size() {
            return items.size();
        }
    }

    /**
     * @param clusters    the clusters to draw, largest first
     * @param totalInView how many clusters there were in view before applying the cap
     */
    public record Result<T>(List<Cluster<T>> clusters, int totalInView) {
        public boolean isCapped() {
            return totalInView > clusters.size();
        }
    }

    private MarkerClusterer() {
    }

    public static <T> Result<T> cluster(List<Input<T>> inputs, Rectangle2D viewport, double cellSize, int cap) {
        Rectangle2D area = new Rectangle2D.Double(viewport.getX() - cellSize / 2, viewport.getY() - cellSize / 2,
                viewport.getWidth() + cellSize, viewport.getHeight() + cellSize);

        Map<Long, List<Input<T>>> cells = new LinkedHashMap<>();
        for (Input<T> input : inputs) {
            if (area.contains(input.pixel())) {
                long cx = (long) Math.floor(input.pixel().getX() / cellSize);
                long cy = (long) Math.floor(input.pixel().getY() / cellSize);
                cells.computeIfAbsent((cx << 32) ^ (cy & 0xffffffffL), k -> new ArrayList<>()).add(input);
            }
        }

        List<Group<T>> groups = new ArrayList<>();
        for (List<Input<T>> cell : cells.values()) {
            groups.add(new Group<>(cell));
        }
        groups.sort(Group.ORDER);

        // Merge neighbours: a big cluster absorbs smaller ones that are too close to be told apart
        List<Group<T>> merged = new ArrayList<>();
        for (Group<T> group : groups) {
            Group<T> target = null;
            for (Group<T> existing : merged) {
                if (existing.center().distance(group.center()) < cellSize) {
                    target = existing;
                    break;
                }
            }
            if (null == target) {
                merged.add(group);
            } else {
                target.absorb(group);
            }
        }
        merged.sort(Group.ORDER);

        List<Cluster<T>> clusters = new ArrayList<>();
        for (Group<T> group : merged.subList(0, Math.min(Math.max(cap, 0), merged.size()))) {
            clusters.add(new Cluster<>(group.items(), group.center()));
        }
        return new Result<>(List.copyOf(clusters), merged.size());
    }

    private static final class Group<T> {
        static final Comparator<Group<?>> ORDER = Comparator.<Group<?>>comparingInt(g -> -g.members.size())
                .thenComparingDouble(g -> g.center().getY())
                .thenComparingDouble(g -> g.center().getX());

        private final List<Input<T>> members;

        Group(List<Input<T>> members) {
            this.members = new ArrayList<>(members);
        }

        void absorb(Group<T> other) {
            members.addAll(other.members);
        }

        Point2D center() {
            double x = 0;
            double y = 0;
            for (Input<T> m : members) {
                x += m.pixel().getX();
                y += m.pixel().getY();
            }
            return new Point2D.Double(x / members.size(), y / members.size());
        }

        List<T> items() {
            return members.stream().map(Input::item).toList();
        }
    }
}
