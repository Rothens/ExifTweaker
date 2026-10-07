package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.model.ImageFile;
import org.jxmapviewer.viewer.GeoPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The photos of a playback in the order they were taken, and where the map should be for each of them. Photos
 * without a date can't be placed in time and are left out.
 */
public class PlaybackSequence {

    private final List<ImageFile> photos;
    private final List<GeoPosition> mapPositions = new ArrayList<>();
    private final List<GeoPosition> route = new ArrayList<>();
    private final int withoutDate;
    private int index;

    public PlaybackSequence(List<ImageFile> candidates) {
        this.photos = candidates.stream().filter(p -> null != p.getTaken())
                .sorted(Comparator.comparing(ImageFile::getTaken).thenComparing(p -> p.getFile().getName()))
                .toList();
        this.withoutDate = candidates.size() - photos.size();
        GeoPosition last = null;
        for (ImageFile photo : photos) {
            if (null != photo.getGp()) {
                last = photo.getGp();
                route.add(last);
            }
            // A photo without a location keeps the map where the previous one was
            mapPositions.add(last);
        }
    }

    public int size() {
        return photos.size();
    }

    public boolean isEmpty() {
        return photos.isEmpty();
    }

    /** How many of the given photos were left out because they have no date. */
    public int getWithoutDate() {
        return withoutDate;
    }

    public int getIndex() {
        return index;
    }

    public ImageFile current() {
        return photos.get(index);
    }

    /** The photos in the order they're played. */
    public List<ImageFile> getPhotos() {
        return java.util.Collections.unmodifiableList(photos);
    }

    public ImageFile get(int i) {
        return photos.get(i);
    }

    /** Where the map shows the current photo: its own location, or the last one before it; may be {@code null}. */
    public GeoPosition mapPosition() {
        return mapPositions.get(index);
    }

    /** Where the map shows photo {@code i}; see {@link #mapPosition()}. */
    public GeoPosition mapPosition(int i) {
        return mapPositions.get(i);
    }

    /** Whether the current photo has its own location (rather than an earlier one's). */
    public boolean hasOwnLocation() {
        return null != current().getGp();
    }

    /** Locations of the located photos, in the order they were taken. */
    public List<GeoPosition> getRoute() {
        return route;
    }

    public void seek(int i) {
        index = Math.max(0, Math.min(photos.size() - 1, i));
    }

    public boolean hasNext() {
        return index < photos.size() - 1;
    }

    /** Moves to the next photo; with {@code loop}, the last one is followed by the first. Returns false at the end. */
    public boolean next(boolean loop) {
        if (hasNext()) {
            index++;
            return true;
        }
        if (loop && !photos.isEmpty()) {
            index = 0;
            return true;
        }
        return false;
    }

    public void previous() {
        seek(index - 1);
    }
}
