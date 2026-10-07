package me.rothens.gpsexif.ui;

import me.rothens.gpsexif.metadata.ExifTool;

import java.awt.*;

/** Sets up ExifTool, which HEIC, PNG, TIFF, WebP and RAW files need. */
public class ExifToolDialog extends ToolDialog {

    public static final String DOWNLOAD_URL = "https://exiftool.org/";

    static final Tool EXIFTOOL = new Tool("ExifTool", "exiftool", DOWNLOAD_URL,
            "JPEG photos work out of the box. <b>HEIC, PNG, TIFF, WebP and RAW</b> files (CR2, CR3, NEF, ARW, "
                    + "DNG, ...) need the free <b>ExifTool</b> by Phil Harvey. RAW files are never modified: their "
                    + "metadata goes into an .xmp sidecar file next to them.",
            installHint(), ExifTool::locate, ExifTool::version);

    /**
     * @param configured the currently configured path (may be empty)
     * @param active     the executable in use, or {@code null} if ExifTool wasn't found
     */
    public ExifToolDialog(Window owner, String configured, String active) {
        super(owner, EXIFTOOL, configured, active);
    }

    private static String installHint() {
        if (isWindows()) {
            return "Windows: download the Windows executable, unzip it, and rename <i>exiftool(-k).exe</i> to "
                    + "<i>exiftool.exe</i>. Keep the <i>exiftool_files</i> folder next to it. Then pick the .exe below.";
        }
        if (isMac()) {
            return "macOS: install the MacOS package from the download page, or run <i>brew install exiftool</i>. "
                    + "It's usually found automatically; otherwise pick /usr/local/bin/exiftool below.";
        }
        return "Linux: install your distribution's package (e.g. <i>libimage-exiftool-perl</i> or "
                + "<i>perl-Image-ExifTool</i>). It's usually found automatically.";
    }
}
