package me.rothens.gpsexif.metadata;

import static me.rothens.gpsexif.i18n.I18n.tr;

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

    /** The field's name in the language in use. */
    public String label() {
        return tr(label);
    }
}
