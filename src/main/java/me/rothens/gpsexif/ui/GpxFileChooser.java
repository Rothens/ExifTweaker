package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Locale;

/** Picks GPX files, starting where the track most likely is. */
public final class GpxFileChooser {

    private GpxFileChooser() {
    }

    /**
     * Asks for one or more GPX files; returns them, or an empty list if cancelled. Starts in the photos' folder when
     * it has a GPX file (e.g. the tutorial's sample trip), else where the last GPX file was picked, else in the
     * photos' folder.
     */
    public static List<File> choose(Component parent, List<ImageFile> photos, Settings settings) {
        JFileChooser chooser = new JFileChooser(startFolder(photos, settings));
        chooser.setDialogTitle("Add GPX files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("GPX tracks (*.gpx)", "gpx"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        File[] files = chooser.getSelectedFiles();
        if (files.length > 0 && null != files[0].getParentFile()) {
            settings.setGpxDirectory(files[0].getParentFile().getPath());
        }
        return List.of(files);
    }

    static File startFolder(List<ImageFile> photos, Settings settings) {
        File photoFolder = photos.isEmpty() ? null : photos.get(0).getFile().getAbsoluteFile().getParentFile();
        if (null != photoFolder && hasGpx(photoFolder)) {
            return photoFolder;
        }
        String last = settings.getGpxDirectory();
        if (!last.isEmpty() && new File(last).isDirectory()) {
            return new File(last);
        }
        return photoFolder;
    }

    private static boolean hasGpx(File folder) {
        File[] gpx = folder.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".gpx"));
        return null != gpx && gpx.length > 0;
    }
}
