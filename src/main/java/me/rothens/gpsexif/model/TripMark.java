package me.rothens.gpsexif.model;

/** How a photo takes part in Play photos and Travel mode (set with a right-click on the photo list). */
public enum TripMark {
    /** Shown as usual. */
    NORMAL,
    /** Never shown in a trip; its location still shapes the route. */
    SKIP,
    /** Chosen first when travel mode can't show every photo of a stretch. */
    PREFER
}
