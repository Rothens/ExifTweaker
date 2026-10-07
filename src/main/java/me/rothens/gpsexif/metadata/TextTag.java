package me.rothens.gpsexif.metadata;

/** Free-text metadata fields that can be edited. */
public enum TextTag {
    MAKE("Camera make"),
    MODEL("Camera model"),
    ARTIST("Artist"),
    COPYRIGHT("Copyright"),
    DESCRIPTION("Description");

    private final String label;

    TextTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
