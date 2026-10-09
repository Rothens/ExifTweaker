package me.rothens.gpsexif.ui;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.gpx.TrackReader;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.Settings;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Locale;

/** Picks track files (GPX and the others {@link TrackReader} reads), starting where the track most likely is. */
public final class GpxFileChooser {

    private GpxFileChooser() {
    }

    /**
     * Asks for one or more track files; returns them, or an empty list if cancelled. Starts in the photos' folder when
     * it has a GPX file (e.g. the tutorial's sample trip), else where the last GPX file was picked, else in the
     * photos' folder.
     */
    public static List<File> choose(Component parent, List<ImageFile> photos, Settings settings) {
        JFileChooser chooser = new JFileChooser(startFolder(photos, settings));
        chooser.setDialogTitle(tr("Add track files"));
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(tr("Tracks: GPX, KML, KMZ, TCX, FIT, Google location history (.json)"),
                TrackReader.EXTENSIONS.toArray(new String[0])));
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

    /** Whether the folder has a track file (not counting .json, which could be anything). */
    private static boolean hasGpx(File folder) {
        File[] gpx = folder.listFiles(f -> f.isFile() && TrackReader.EXTENSIONS.stream().filter(e -> !e.equals("json"))
                .anyMatch(e -> f.getName().toLowerCase(Locale.ROOT).endsWith("." + e)));
        return null != gpx && gpx.length > 0;
    }
}
