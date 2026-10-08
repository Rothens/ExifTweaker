package me.rothens.gpsexif.tutorial;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A guided tour over a window: each step dims the window except one component, and a speech bubble next to it
 * says what to do. A step moves on by itself when its condition is met (e.g. "a folder is open"), or with Next.
 * The window stays fully usable while the tour runs.
 */
public final class Tutorial {

    /** A button in place of Back/Next, e.g. on the welcome step. */
    public record Choice(String label, Runnable action) {
    }

    /**
     * One step of the tour.
     *
     * @param target  the component to point at, or {@code null} (or one that isn't showing) for a bubble in the
     *                middle of the window
     * @param title   bold first line of the bubble
     * @param text    the explanation (HTML body text)
     * @param done    when it returns true the tour moves on by itself; {@code null} to wait for Next
     * @param enter   run when the step is shown, e.g. to remember the state {@code done} compares against;
     *                may be {@code null}
     * @param choices buttons instead of Back/Next; empty for the usual buttons
     */
    public record Step(Supplier<? extends Component> target, String title, String text, BooleanSupplier done,
                       Runnable enter, List<Choice> choices) {

        public Step {
            choices = List.copyOf(choices);
        }

        /** A step that waits for Next. */
        public static Step explain(Supplier<? extends Component> target, String title, String text) {
            return new Step(target, title, text, null, null, List.of());
        }

        /** A step that moves on when {@code done} becomes true (Next still works). */
        public static Step action(Supplier<? extends Component> target, String title, String text,
                                  Runnable enter, BooleanSupplier done) {
            return new Step(target, title, text, done, enter, List.of());
        }
    }

    /** How often the step condition and the target's position are checked. */
    private static final int TICK_MS = 200;
    /** Pause after a step's condition is met, so the user sees what happened before the bubble moves. */
    private static final int ADVANCE_DELAY_MS = 700;

    private final JFrame frame;
    private final List<Step> steps;
    private final Runnable onClose;
    private final TutorialOverlay overlay;
    private final Component previousGlassPane;
    private final Timer timer;
    private int index = -1;
    private long doneSince;
    private boolean closed;

    /**
     * @param onClose called once when the tour ends, finished or skipped
     */
    public Tutorial(JFrame frame, List<Step> steps, Runnable onClose) {
        this.frame = frame;
        this.steps = List.copyOf(steps);
        this.onClose = onClose;
        this.overlay = new TutorialOverlay(this);
        this.previousGlassPane = frame.getGlassPane();
        this.timer = new Timer(TICK_MS, e -> tick());
    }

    public void start() {
        frame.setGlassPane(overlay);
        overlay.setVisible(true);
        frame.getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        timer.start();
        show(0);
    }

    public boolean isRunning() {
        return !closed && index >= 0;
    }

    /** Ends the tour (Skip, Esc or Finish). */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        timer.stop();
        overlay.setVisible(false);
        frame.getRootPane().unregisterKeyboardAction(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        frame.setGlassPane(previousGlassPane);
        frame.getRootPane().repaint();
        onClose.run();
    }

    public void next() {
        if (index + 1 < steps.size()) {
            show(index + 1);
        } else {
            close();
        }
    }

    public void back() {
        if (index > 0) {
            show(index - 1);
        }
    }

    int getIndex() {
        return index;
    }

    int getStepCount() {
        return steps.size();
    }

    Step getStep() {
        return steps.get(index);
    }

    private void show(int i) {
        index = i;
        doneSince = 0;
        Step step = steps.get(i);
        if (null != step.enter()) {
            step.enter().run();
        }
        overlay.showStep(step);
    }

    private void tick() {
        if (closed || index < 0) {
            return;
        }
        Step step = steps.get(index);
        if (null != step.done()) {
            boolean done;
            try {
                done = step.done().getAsBoolean();
            } catch (RuntimeException e) {
                done = false;
            }
            if (done) {
                long now = System.currentTimeMillis();
                if (0 == doneSince) {
                    doneSince = now;
                    overlay.markDone();
                } else if (now - doneSince >= ADVANCE_DELAY_MS) {
                    next();
                    return;
                }
            }
        }
        overlay.relayout();
    }

    /** The component a step points at, if it's on screen in this window. */
    Component visibleTarget() {
        Step step = steps.get(index);
        Component c = null == step.target() ? null : step.target().get();
        if (null == c || !c.isShowing() || SwingUtilities.getRoot(c) != frame) {
            return null;
        }
        // A list or table: point at its scroll pane, which is what the user sees
        if (c.getParent() instanceof JViewport && c.getParent().getParent() instanceof JScrollPane scroll) {
            return scroll;
        }
        return c;
    }
}
