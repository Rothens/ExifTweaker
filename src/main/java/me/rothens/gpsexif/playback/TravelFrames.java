package me.rothens.gpsexif.playback;

import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactory;

import java.awt.image.BufferedImage;
import java.util.List;

/** Travel mode as video frames: {@link TravelTimeline#frameAt} at every frame time, drawn by a {@link TravelView}. */
final class TravelFrames extends OffscreenFrames {

    private final TravelTimeline timeline;
    private final int fps;
    private final TravelView view;

    /** Call on the Swing thread. */
    TravelFrames(TravelTimeline timeline, List<List<GeoPosition>> route, ClockZone clock, TileFactory tileFactory,
                 int width, int height, int fps, double insetFraction, boolean followMarker, int insetZoom) {
        super(width, height);
        this.timeline = timeline;
        this.fps = fps;
        this.view = new TravelView(tileFactory, this::photo, clock);
        view.setInsetFraction(insetFraction);
        view.setFollowMarker(followMarker, insetZoom);
        hideLoadingTiles(view.getMaps());
        size(view);
        view.setTimeline(timeline, route);
    }

    @Override
    public int getFrameCount() {
        return Math.max(1, (int) Math.ceil(timeline.getLength() * fps));
    }

    @Override
    public void render(int index, BufferedImage target) throws Exception {
        TravelTimeline.Frame frame = timeline.frameAt((double) index / fps);
        loadPhoto(frame.from());
        loadPhoto(frame.to());
        onEdt(() -> {
            view.show(frame);
            return null;
        });
        awaitTiles(view.getMaps());
        paint(view, target);
    }
}
