package me.rothens.gpsexif.model;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/** List model of the opened photos, with an optional "only photos without a location" filter. */
public class ImageListModel extends AbstractListModel<ImageFile> {

    private List<ImageFile> all = List.of();
    private List<ImageFile> visible = List.of();
    private boolean onlyWithoutLocation;

    public void setAll(List<ImageFile> images) {
        all = List.copyOf(images);
        refresh();
    }

    /** All opened photos, including the ones hidden by the filter. */
    public List<ImageFile> getAll() {
        return all;
    }

    public boolean isOnlyWithoutLocation() {
        return onlyWithoutLocation;
    }

    public void setOnlyWithoutLocation(boolean onlyWithoutLocation) {
        this.onlyWithoutLocation = onlyWithoutLocation;
        refresh();
    }

    /** Re-applies the filter, e.g. after photos were written. */
    public void refresh() {
        int oldSize = visible.size();
        List<ImageFile> filtered = new ArrayList<>();
        for (ImageFile image : all) {
            if (!onlyWithoutLocation || !image.hasExifGPS()) {
                filtered.add(image);
            }
        }
        visible = filtered;
        if (oldSize > 0) {
            fireIntervalRemoved(this, 0, oldSize - 1);
        }
        if (!visible.isEmpty()) {
            fireIntervalAdded(this, 0, visible.size() - 1);
        }
    }

    public int indexOf(ImageFile image) {
        return visible.indexOf(image);
    }

    @Override
    public int getSize() {
        return visible.size();
    }

    @Override
    public ImageFile getElementAt(int index) {
        return visible.get(index);
    }
}
