package me.rothens.gpsexif;

import me.rothens.gpsexif.model.ImageFile;

import javax.swing.*;

/**
 * Created by Rothens on 2017. 06. 19..
 */
public interface FileOpenListener {
    void fileOpened();
    void finished(DefaultListModel<ImageFile> model);
}
