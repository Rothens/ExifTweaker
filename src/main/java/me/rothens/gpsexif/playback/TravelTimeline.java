package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackMatcher;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.model.TripMark;
import org.jxmapviewer.viewer.GeoPosition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * "Travel mode": the time from the first to the last photo is played back as a video of a given length. A marker
 * moves along the route (a GPX track, or straight lines between the photos); when it reaches a photo, that photo
 * fades in and stays for at least the minimum photo time. Photos the marker reaches within that time are
 * skipped - of such a burst, the middle photo is shown.
 * <p>
 * Long pauses are classified by distance: a <b>stay</b> (long gap, little distance - typically a night) is
 * squeezed to a short moment with a caption, a <b>travel</b> gap keeps its share of the video and is shown on a
 * full-screen map.
 * <p>
 * Everything is a pure function of the video time ({@link #frameAt}), so the live player and a video export
 * produce identical frames.
 */
public class TravelTimeline {

    /**
     * @param videoLength   total length of the video
     * @param minPhoto      a photo is shown at least this long
     * @param fade          length of a cross-fade (part of the photo time)
     * @param squeezeStays  whether stays are squeezed
     * @param stayGap       a gap at least this long ...
     * @param stayDistanceM ... with less movement than this (metres) is a stay
     * @param stayVideo     video time a squeezed stay takes
     * @param maxJourney    a journey (a long gap with real distance covered) takes at most this much video time;
     *                      {@code null} for no limit
     * @param mapBetweenPhotos whether journeys switch to the full-screen map; otherwise the last photo stays up
     *                      while the marker travels on the small map
     */
    public record Settings(Duration videoLength, Duration minPhoto, Duration fade, boolean squeezeStays,
                           Duration stayGap, double stayDistanceM, Duration stayVideo, Duration maxJourney,
                           boolean mapBetweenPhotos) {

        /** With the full-screen map between photos. */
        public Settings(Duration videoLength, Duration minPhoto, Duration fade, boolean squeezeStays,
                        Duration stayGap, double stayDistanceM, Duration stayVideo, Duration maxJourney) {
            this(videoLength, minPhoto, fade, squeezeStays, stayGap, stayDistanceM, stayVideo, maxJourney, true);
        }

        /** Without a journey limit. */
        public Settings(Duration videoLength, Duration minPhoto, Duration fade, boolean squeezeStays,
                        Duration stayGap, double stayDistanceM, Duration stayVideo) {
            this(videoLength, minPhoto, fade, squeezeStays, stayGap, stayDistanceM, stayVideo, null);
        }

        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(3), Duration.ofMillis(2500), Duration.ofMillis(600), true,
                    Duration.ofHours(1), 2000, Duration.ofMillis(2000), Duration.ofSeconds(8));
        }
    }

    /** A photo in time order with its real (UTC) time; position may be null. */
    public record Stop(ImageFile photo, Instant time, GeoPosition position, double videoTime) {
    }

    /** A shown photo and the video time it owns, until the next slot starts. */
    public record Slot(Stop stop, double start, double end, boolean showMap, Duration pause) {
    }

    /**
     * What to show at one moment.
     *
     * @param from    what's fading out ({@code null} = the full-screen map)
     * @param to      what's fading in ({@code null} = the full-screen map)
     * @param alpha   0 = only {@code from}, 1 = only {@code to}
     * @param marker  where the moving marker is, or {@code null} if nothing is known yet
     * @param photoAt location of the photo being shown (the small dot), or {@code null}
     * @param caption e.g. "+9 h" while a stay is squeezed, or {@code null}
     */
    public record Frame(double videoTime, Instant time, ImageFile from, ImageFile to, double alpha,
                        GeoPosition marker, GeoPosition photoAt, String caption, int slot) {
        /** The photo mostly visible, or {@code null} while the map is. */
        public ImageFile visible() {
            return alpha >= 0.5 ? to : from;
        }
    }

    private static final double MIN_MAP_TIME = 2.0;
    private static final double MIN_TRAVEL_DISTANCE_M = 200;

    private final Settings settings;
    private final List<Stop> stops = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<GeoPosition> straightRoute = new ArrayList<>();
    private final TrackMatcher track;
    private final double length;
    private int squeezedStays;
    private int shortenedJourneys;
    private int skipped;
    private double secondsPerVideoSecond;

    /**
     * @param photos   candidates; those without a date are left out
     * @param realTime converts a photo's camera time to UTC
     * @param tracks   GPX tracks for the route; empty for straight lines between the photos
     */
    public TravelTimeline(List<ImageFile> photos, Function<ImageFile, Instant> realTime, List<Track> tracks,
                          Settings settings) {
        this.settings = settings;
        this.length = seconds(settings.videoLength());
        TrackMatcher matcher = new TrackMatcher(tracks);
        this.track = matcher.hasTimedPoints() ? matcher : null;

        List<ImageFile> dated = photos.stream().filter(p -> null != p.getTaken())
                .sorted(java.util.Comparator.comparing(realTime).thenComparing(p -> p.getFile().getName())).toList();
        if (dated.isEmpty()) {
            return;
        }
        List<Instant> times = dated.stream().map(realTime).toList();

        // How much video time each gap between consecutive photos gets
        int n = dated.size();
        boolean[] stay = new boolean[Math.max(0, n - 1)];
        boolean[] journey = new boolean[Math.max(0, n - 1)];
        double[] gapSeconds = new double[Math.max(0, n - 1)];
        double travelSeconds = 0;
        for (int i = 0; i < n - 1; i++) {
            Duration gap = Duration.between(times.get(i), times.get(i + 1));
            gapSeconds[i] = Math.max(0, seconds(gap));
            boolean longGap = gap.compareTo(settings.stayGap()) >= 0;
            GeoPosition a = dated.get(i).getGp();
            GeoPosition b = dated.get(i + 1).getGp();
            stay[i] = settings.squeezeStays() && longGap && isStay(a, b);
            journey[i] = !stay[i] && longGap && null != a && null != b
                    && TrackMatcher.distanceMetres(a, b) >= settings.stayDistanceM();
            if (stay[i]) {
                squeezedStays++;
            } else {
                travelSeconds += gapSeconds[i];
            }
        }
        double minPhoto = seconds(settings.minPhoto());
        double available = Math.max(0, length - minPhoto); // the last photo still gets its minimum time
        // A squeezed stop lasts at least the minimum photo time, so the first photo after a night isn't merged
        // into the last one before it (and skipped)
        double stayTime = Math.min(Math.max(seconds(settings.stayVideo()), minPhoto),
                squeezedStays == 0 ? 0 : available / 2 / squeezedStays);
        double travelVideo = available - squeezedStays * stayTime;
        double[] gapVideo = allocate(gapSeconds, stay, journey, stayTime, travelVideo, minPhoto);

        double v = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                v += gapVideo[i - 1];
            }
            stops.add(new Stop(dated.get(i), times.get(i), dated.get(i).getGp(), v));
            if (null != dated.get(i).getGp()) {
                straightRoute.add(dated.get(i).getGp());
            }
        }

        // Photos marked "skip" still shape the route and the timing, but are never shown
        List<Integer> visible = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (TripMark.SKIP != stops.get(k).photo().getTripMark()) {
                visible.add(k);
            } else {
                skipped++;
            }
        }
        // Group photos reached within the minimum photo time; show one per group: the middle preferred one if
        // there is one, else the middle one
        List<int[]> groups = new ArrayList<>();
        int i = 0;
        while (i < visible.size()) {
            int j = i + 1;
            while (j < visible.size()
                    && stops.get(visible.get(j)).videoTime() < stops.get(visible.get(i)).videoTime() + minPhoto) {
                j++;
            }
            groups.add(new int[]{i, j - 1});
            i = j;
        }
        for (int g = 0; g < groups.size(); g++) {
            int first = visible.get(groups.get(g)[0]);
            int last = visible.get(groups.get(g)[1]);
            Stop shown = pick(visible.subList(groups.get(g)[0], groups.get(g)[1] + 1));
            // The first shown photo starts the film, even if skipped photos come before it
            double start = 0 == g ? 0 : stops.get(first).videoTime();
            int next = g + 1 < groups.size() ? visible.get(groups.get(g + 1)[0]) : -1;
            double end = next >= 0 ? stops.get(next).videoTime() : length;
            boolean showMap = false;
            Duration pause = null;
            if (next >= 0) {
                // Squeezed stays between this and the next shown photo: the photo stays up with a "+9 h" caption
                for (int k = last; k < next; k++) {
                    if (stay[k]) {
                        Duration d = Duration.between(stops.get(k).time(), stops.get(k + 1).time());
                        pause = null == pause ? d : pause.plus(d);
                    }
                }
                if (null == pause && settings.mapBetweenPhotos() && end - start >= minPhoto + MIN_MAP_TIME) {
                    GeoPosition a = lastKnown(last);
                    GeoPosition b = stops.get(next).position();
                    showMap = null == a || null == b || TrackMatcher.distanceMetres(a, b) >= MIN_TRAVEL_DISTANCE_M;
                }
            }
            slots.add(new Slot(shown, start, end, showMap, pause));
        }
    }

    /** The photo to show for a group of stops: the middle preferred one, else the middle one. */
    private Stop pick(List<Integer> group) {
        List<Integer> preferred = group.stream()
                .filter(k -> TripMark.PREFER == stops.get(k).photo().getTripMark()).toList();
        List<Integer> from = preferred.isEmpty() ? group : preferred;
        return stops.get(from.get((from.size() - 1) / 2));
    }

    /**
     * Video time per gap: stays get {@code stayTime}; the rest shares {@code budget} in proportion to real time,
     * except that a journey gets at most {@code maxJourney} - the time it gives up goes to the other gaps. If only
     * capped journeys are left to absorb the time, the limit gives way so the film keeps its length.
     */
    private double[] allocate(double[] gapSeconds, boolean[] stay, boolean[] journey, double stayTime,
                              double budget, double minPhoto) {
        int gaps = gapSeconds.length;
        double[] video = new double[gaps];
        // A journey keeps room for the photo's minimum time plus a moment on the map
        double cap = null == settings.maxJourney() ? Double.MAX_VALUE
                : Math.max(seconds(settings.maxJourney()), minPhoto + MIN_MAP_TIME);
        boolean[] capped = new boolean[gaps];
        double scale = 0;
        while (true) {
            double fixed = 0;
            double proportional = 0;
            for (int i = 0; i < gaps; i++) {
                if (stay[i]) {
                    continue;
                }
                if (capped[i]) {
                    fixed += cap;
                } else {
                    proportional += gapSeconds[i];
                }
            }
            scale = proportional > 0 ? Math.max(0, budget - fixed) / proportional : 0;
            boolean changed = false;
            for (int i = 0; i < gaps; i++) {
                if (!stay[i] && journey[i] && !capped[i] && gapSeconds[i] * scale > cap) {
                    capped[i] = true;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        double total = 0;
        int nonStay = 0;
        for (int i = 0; i < gaps; i++) {
            if (stay[i]) {
                video[i] = stayTime;
            } else {
                video[i] = capped[i] ? cap : gapSeconds[i] * scale;
                total += video[i];
                nonStay++;
            }
        }
        shortenedJourneys = 0;
        for (boolean c : capped) {
            shortenedJourneys += c ? 1 : 0;
        }
        if (nonStay > 0 && Math.abs(total - budget) > 1e-9) {
            // Nothing left to absorb the time (e.g. only capped journeys, or photos all at one moment):
            // share the whole budget by real time, or evenly
            double real = 0;
            for (int i = 0; i < gaps; i++) {
                real += stay[i] ? 0 : gapSeconds[i];
            }
            for (int i = 0; i < gaps; i++) {
                if (!stay[i]) {
                    video[i] = real > 0 ? budget * gapSeconds[i] / real : budget / nonStay;
                }
            }
            shortenedJourneys = 0;
            scale = real > 0 ? budget / real : 0;
        }
        secondsPerVideoSecond = scale > 0 ? 1 / scale : 0;
        return video;
    }

    /** Photos marked "skip in trips". */
    public int getSkipped() {
        return skipped;
    }

    public int getShortenedJourneys() {
        return shortenedJourneys;
    }

    private boolean isStay(GeoPosition a, GeoPosition b) {
        // Without both locations we can't tell travel from staying; squeezing is the safer choice for the video
        return null == a || null == b || TrackMatcher.distanceMetres(a, b) < settings.stayDistanceM();
    }

    private GeoPosition lastKnown(int index) {
        for (int i = index; i >= 0; i--) {
            if (null != stops.get(i).position()) {
                return stops.get(i).position();
            }
        }
        return null;
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }

    public double getLength() {
        return length;
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public List<Slot> getSlots() {
        return slots;
    }

    public List<Stop> getStops() {
        return stops;
    }

    public int getSqueezedStays() {
        return squeezedStays;
    }

    /** Real seconds that pass per second of video during travel. */
    public double getRealSecondsPerVideoSecond() {
        return secondsPerVideoSecond;
    }

    public boolean usesTrack() {
        return null != track;
    }

    /** Locations of the photos in time order: the route when there's no GPX track. */
    public List<GeoPosition> getStraightRoute() {
        return straightRoute;
    }

    /** One-line summary, e.g. "Shows 84 of 300 photos; 1 s of travel = 6 min; 2 nights squeezed". */
    public String summary() {
        if (stops.isEmpty()) {
            return "No photos with a date.";
        }
        StringBuilder sb = new StringBuilder("Shows " + slots.size() + " of " + stops.size() + " photos");
        if (skipped > 0) {
            sb.append(" (").append(skipped).append(" skipped)");
        }
        if (secondsPerVideoSecond > 0) {
            sb.append("; 1 s of video = ").append(me.rothens.gpsexif.gpx.PhotoTime.describe(
                    Duration.ofMillis(Math.round(secondsPerVideoSecond * 1000)))).append(" of travel");
        }
        if (squeezedStays > 0) {
            sb.append("; ").append(squeezedStays).append(squeezedStays == 1 ? " long stop" : " long stops")
                    .append(" squeezed");
        }
        if (shortenedJourneys > 0) {
            sb.append("; ").append(shortenedJourneys).append(shortenedJourneys == 1 ? " journey" : " journeys")
                    .append(" shortened");
        }
        return sb.toString();
    }

    /** The real time at video time {@code v}: linear between the photos' video times. */
    public Instant timeAt(double v) {
        if (stops.isEmpty()) {
            return null;
        }
        if (v <= stops.get(0).videoTime()) {
            return stops.get(0).time();
        }
        for (int i = 1; i < stops.size(); i++) {
            Stop a = stops.get(i - 1);
            Stop b = stops.get(i);
            if (v <= b.videoTime()) {
                double span = b.videoTime() - a.videoTime();
                double f = span <= 0 ? 1 : (v - a.videoTime()) / span;
                long millis = Duration.between(a.time(), b.time()).toMillis();
                return a.time().plusMillis(Math.round(f * millis));
            }
        }
        return stops.get(stops.size() - 1).time();
    }

    /** Where the marker is at real time {@code t}: on the GPX track, or between the photos' locations. */
    /** How far from the track's points (in time) it's still followed, e.g. across a short recording pause. */
    private static final Duration TRACK_GAP = Duration.ofMinutes(30);

    public GeoPosition markerAt(Instant t) {
        if (null != track) {
            // Outside the recorded time (e.g. a track of only the first day), straight lines between the photos
            TrackMatcher.Match match = track.match(t, TRACK_GAP);
            if (match.isMatched()) {
                return match.position();
            }
        }
        Stop before = null;
        Stop after = null;
        for (Stop s : stops) {
            if (null == s.position()) {
                continue;
            }
            if (!s.time().isAfter(t)) {
                before = s;
            } else {
                after = s;
                break;
            }
        }
        if (null == before) {
            return null == after ? null : after.position();
        }
        if (null == after) {
            return before.position();
        }
        double span = Duration.between(before.time(), after.time()).toMillis();
        double f = span <= 0 ? 1 : Duration.between(before.time(), t).toMillis() / span;
        return new GeoPosition(before.position().getLatitude() + f * (after.position().getLatitude()
                - before.position().getLatitude()), before.position().getLongitude() + f
                * (after.position().getLongitude() - before.position().getLongitude()));
    }

    /** The frame at video time {@code v} (clamped to the video). */
    public Frame frameAt(double v) {
        if (slots.isEmpty()) {
            return new Frame(v, null, null, null, 1, null, null, null, -1);
        }
        v = Math.max(0, Math.min(length, v));
        int index = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).start() <= v) {
                index = i;
            }
        }
        Slot slot = slots.get(index);
        Instant time = timeAt(v);
        GeoPosition marker = markerAt(time);
        GeoPosition photoAt = slot.stop().position();
        double fade = Math.max(0.001, seconds(settings.fade()));
        double hold = seconds(settings.minPhoto());
        double t = v - slot.start();
        ImageFile photo = slot.stop().photo();

        if (t < fade && index > 0) {
            Slot previous = slots.get(index - 1);
            ImageFile from = previous.showMap() ? null : previous.stop().photo();
            return new Frame(v, time, from, photo, t / fade, marker, photoAt, null, index);
        }
        if (slot.showMap() && t >= hold - fade) {
            double alpha = Math.min(1, (t - (hold - fade)) / fade);
            return new Frame(v, time, photo, null, alpha, marker, photoAt, null, index);
        }
        // The clock races through the stop during the whole slot, so the caption shows once the photo is in
        String caption = null != slot.pause() && t >= Math.min(fade, hold)
                ? "+" + me.rothens.gpsexif.gpx.PhotoTime.describe(slot.pause()) : null;
        return new Frame(v, time, photo, photo, 1, marker, photoAt, caption, index);
    }
}
