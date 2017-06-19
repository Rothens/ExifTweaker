package me.rothens.gpsexif.model;

/**
 * Created by Rothens on 2017. 05. 20..
 */
public class ExifData implements Comparable<ExifData> {
    String key;
    String value;

    public ExifData(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public int compareTo(ExifData o) {
        return key.compareTo(o.getKey());
    }
}
