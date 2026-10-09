package me.rothens.gpsexif.ui;

import java.awt.*;

/** Sizes windows to the screen they open on. */
public final class Screens {

    private Screens() {
    }

    /** A window size that uses most of the screen (at most {@code fraction} of it), but no less than the minimum. */
    public static Dimension windowSize(Window owner, int minWidth, int minHeight, double fraction) {
        GraphicsConfiguration gc = null != owner ? owner.getGraphicsConfiguration() : null;
        Rectangle screen = null != gc ? gc.getBounds()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (null != gc) {
            Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            screen = new Rectangle(screen.x + taskbar.left, screen.y + taskbar.top,
                    screen.width - taskbar.left - taskbar.right, screen.height - taskbar.top - taskbar.bottom);
        }
        int w = Math.min(screen.width, Math.max(minWidth, (int) (screen.width * fraction)));
        int h = Math.min(screen.height, Math.max(minHeight, (int) (screen.height * fraction)));
        return new Dimension(w, h);
    }
}
