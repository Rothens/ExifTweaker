package me.rothens.gpsexif.ui;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.video.Ffmpeg;

import java.awt.*;

/** Sets up FFmpeg, which makes the video export much faster and the files smaller. */
public class FfmpegDialog extends ToolDialog {

    static final Tool FFMPEG = new Tool("FFmpeg", "ffmpeg", Ffmpeg.DOWNLOAD_URL,
            tr("Videos can be exported without anything else installed, but the built-in encoder is slow and makes large files. With the free <b>FFmpeg</b> the export is many times faster and the videos are smaller and look better."),
            installHint(), Ffmpeg::locate, Ffmpeg::version);

    /**
     * @param configured the currently configured path (may be empty)
     * @param active     the executable in use, or {@code null} if FFmpeg wasn't found
     */
    public FfmpegDialog(Window owner, String configured, String active) {
        this(owner, configured, active, new me.rothens.gpsexif.tools.ToolInstaller(
                me.rothens.gpsexif.tools.ToolInstaller.USER_AGENT));
    }

    private FfmpegDialog(Window owner, String configured, String active,
                         me.rothens.gpsexif.tools.ToolInstaller installer) {
        // Downloadable on Windows; elsewhere the install hint says which package to install
        super(owner, FFMPEG, configured, active, !installer.canInstallFfmpeg() ? null : () -> new ToolInstallDialog(
                owner, installer, null, tr("not installed yet"), false, () -> { }).showDialog().ffmpeg());
    }

    private static String installHint() {
        if (isWindows()) {
            return tr("Windows: download a Windows build (e.g. from gyan.dev, linked on the download page), unzip it and pick <i>bin\\ffmpeg.exe</i> below, or run <i>winget install ffmpeg</i>.");
        }
        if (isMac()) {
            return tr("macOS: run <i>brew install ffmpeg</i>, or download a build from the download page and pick it below.");
        }
        return tr("Linux: install your distribution's <i>ffmpeg</i> package. It's usually found automatically.");
    }
}
