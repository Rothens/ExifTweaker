package me.rothens.gpsexif;

import me.rothens.gpsexif.model.ImageFile;

import javax.swing.*;
import java.io.File;

/**
 * Created by Rothens on 2017. 06. 19..
 */
public class FileOpenThread extends Thread {
    File[] files;
    FileOpenListener fol;
    DefaultListModel<ImageFile> model;

    public FileOpenThread(File[] files, FileOpenListener fol){
        this.files = files;
        this.fol = fol;
        model = new DefaultListModel<>();
    }

    @Override
    public void run() {
        for(File f : files){
            model.addElement(new ImageFile(f));
            fol.fileOpened();
        }
        fol.finished(model);
    }
}
