package me.rothens.gpsexif.model;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.*;
import java.util.*;

/**
 * Created by Rothens on 2017. 04. 15..
 */
public class ImageFile {

    private static final List<String> exifFields = Arrays.asList("Make", "Model", "Orientation", "XResolution", "YResolution", "ExposureTime", "FNumber", "DateTimeDigitized", "ExifImageWidth", "ExifImageLength");
    File file;
    GeoPosition gp;
    List<ExifData> exifData;

    public ImageFile(File file) {
        this.file = file;
        gp = getLocation(file);
        exifData = fillExif(file);
    }

    public boolean hasExifGPS() {
        return null != gp;
    }

    private static GeoPosition getLocation(File image) {
        try {
            ImageMetadata metadata = Imaging.getMetadata(image);
            if (metadata instanceof JpegImageMetadata) {
                final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
                final TiffImageMetadata exifMetadata = jpegMetadata.getExif();
                if (null != exifMetadata) {
                    final TiffImageMetadata.GPSInfo gpsInfo = exifMetadata.getGPS();
                    if (null != gpsInfo) {
                        final double longitude = gpsInfo.getLongitudeAsDegreesEast();
                        final double latitude = gpsInfo.getLatitudeAsDegreesNorth();

                        return new GeoPosition(latitude, longitude);
                    }
                }

            }
        } catch (Exception e) {

        }
        return null;
    }

    private static List<ExifData> fillExif(File image) {
        Map<String, String> map = new HashMap<>();
        try {

            ImageMetadata metadata = Imaging.getMetadata(image);
            if (metadata instanceof JpegImageMetadata) {
                final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
                final TiffImageMetadata exifMetadata = jpegMetadata.getExif();
                if (null != exifMetadata) {
                    List<TiffField> allFields = exifMetadata.getAllFields();
                    for (TiffField tf : allFields) {
                        if (exifFields.contains(tf.getTagName()))
                            map.put(tf.getTagName(), tf.getValue().toString());
                    }
                }

            }
        } catch (Exception e) {
            System.out.println(e);
        }
        List<ExifData> ret = new ArrayList<>();
        for (String s : map.keySet()) {
            ret.add(new ExifData(s, map.get(s)));
        }
        Collections.sort(ret);
        return ret;
    }

    public List<ExifData> getExifData() {
        return exifData;
    }


    public File getFile() {
        return file;
    }

    public GeoPosition getGp() {
        return gp;
    }

    public void setGp(GeoPosition gp) {
        this.gp = gp;
    }

    public void save() {
        final File dst = new File(file.getParent() + "\\image.tmp");
        TiffOutputSet outputSet = null;
        boolean success = true;
        try (FileOutputStream fos = new FileOutputStream(dst);
             OutputStream os = new BufferedOutputStream(fos)) {
            final ImageMetadata metadata = Imaging.getMetadata(file);
            final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
            if (null != jpegMetadata) {
                final TiffImageMetadata exif = jpegMetadata.getExif();

                if (null != exif) {
                    outputSet = exif.getOutputSet();
                }
            }


            if (null == outputSet) {
                outputSet = new TiffOutputSet();
            }
            final double longitude = gp.getLongitude();
            final double latitude = gp.getLatitude();

            outputSet.setGPSInDegrees(longitude, latitude);

            try {
                new ExifRewriter().updateExifMetadataLossless(file, os,
                        outputSet);
            } catch (ExifRewriter.ExifOverflowException ex) {
                System.out.println("Couldn't save location lossless for: " + file.getName() + " [ lon:" + longitude + ", lat:" + latitude + "]");
                success = false;
            }
        } catch (Exception e) {
            System.out.println(e);
            return;
        }

        if (!success) {
            success = true;
            try (FileOutputStream fos = new FileOutputStream(dst);
                 OutputStream os = new BufferedOutputStream(fos)) {
                new ExifRewriter().updateExifMetadataLossy(file, os, outputSet);
            } catch (Exception e) {
                System.out.println("Couldn't save location lossy for: " + file.getName());
                success = false;
            }
        }
        if (success) {
            try {
                File oldFile = new File(file.getParent() + "\\image.tmp");
                File newFile = new File(file.getPath());
                file.delete();
                oldFile.renameTo(newFile);
            } catch (Exception e) {
                System.out.println(e);
            }
        } else {
            File tmp = new File(file.getParent() + "\\image.tmp");
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }
}
