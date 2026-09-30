package me.rothens.gpsexif.metadata;

/** Free-text metadata fields that can be edited. */
public enum TextField {
    MAKE("Camera make"),
    MODEL("Camera model"),
    ARTIST("Artist"),
    COPYRIGHT("Copyright"),
    DESCRIPTION("Description");

    private final String label;

    TextField(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
