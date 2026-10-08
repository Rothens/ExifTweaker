package me.rothens.gpsexif.tutorial;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The window's glass pane while the tutorial runs: dims everything but the current step's component and shows the
 * speech bubble next to it. It has no mouse listeners itself, so clicks go through to the window underneath; only
 * the bubble takes clicks.
 */
final class TutorialOverlay extends JComponent {

    private static final int GAP = 14;
    private static final int PADDING = 6;
    private static final int BUBBLE_WIDTH = 340;
    private static final Color DIM = new Color(0, 0, 0, 125);

    private final Tutorial tutorial;
    private final Bubble bubble = new Bubble();
    /** Spotlight and bubble placement, in this component's coordinates. */
    private Rectangle spot;
    private Side side = Side.CENTER;

    private enum Side { RIGHT, LEFT, BELOW, ABOVE, CENTER }

    TutorialOverlay(Tutorial tutorial) {
        this.tutorial = tutorial;
        setLayout(null);
        setOpaque(false);
        add(bubble);
        // An open menu sits underneath us: don't dim it
        MenuSelectionManager.defaultManager().addChangeListener(e -> repaint());
    }

    void showStep(Tutorial.Step step) {
        bubble.show(step, tutorial.getIndex(), tutorial.getStepCount());
        relayout();
    }

    void markDone() {
        bubble.markDone();
    }

    /** Follows the target when the window is resized, scrolled or rearranged. */
    void relayout() {
        if (getWidth() <= 0 || tutorial.getIndex() < 0) {
            return;
        }
        Component target = tutorial.visibleTarget();
        Rectangle newSpot = null;
        if (null != target) {
            newSpot = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), this);
            newSpot.grow(PADDING, PADDING);
            newSpot = newSpot.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
        }
        Dimension size = bubble.getPreferredSize();
        Rectangle bounds = place(newSpot, size);
        if (!bounds.equals(bubble.getBounds()) || !java.util.Objects.equals(newSpot, spot)) {
            spot = newSpot;
            bubble.setBounds(bounds);
            bubble.revalidate();
            repaint();
        }
    }

    /** Puts the bubble next to the spotlight where it fits: right, left, below or above; else in the middle. */
    private Rectangle place(Rectangle target, Dimension size) {
        Rectangle area = new Rectangle(8, 8, getWidth() - 16, getHeight() - 16);
        if (null != target) {
            int cy = clamp(target.y + target.height / 2 - size.height / 2, area.y, area.y + area.height - size.height);
            int cx = clamp(target.x + target.width / 2 - size.width / 2, area.x, area.x + area.width - size.width);
            Object[][] candidates = {
                    {Side.RIGHT, new Rectangle(target.x + target.width + GAP, cy, size.width, size.height)},
                    {Side.LEFT, new Rectangle(target.x - GAP - size.width, cy, size.width, size.height)},
                    {Side.BELOW, new Rectangle(cx, target.y + target.height + GAP, size.width, size.height)},
                    {Side.ABOVE, new Rectangle(cx, target.y - GAP - size.height, size.width, size.height)}};
            for (Object[] c : candidates) {
                Rectangle r = (Rectangle) c[1];
                if (area.contains(r)) {
                    side = (Side) c[0];
                    return r;
                }
            }
            // A big target (e.g. the map): inside it, bottom left, so the middle stays visible
            if (target.width > size.width + 2 * GAP && target.height > size.height + 2 * GAP) {
                side = Side.CENTER;
                return new Rectangle(target.x + GAP, target.y + target.height - GAP - size.height,
                        size.width, size.height);
            }
        }
        side = Side.CENTER;
        return new Rectangle((getWidth() - size.width) / 2, (getHeight() - size.height) / 2, size.width,
                size.height);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean menuOpen = MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
            Shape hole = null == spot ? null : new RoundRectangle2D.Double(spot.x, spot.y, spot.width, spot.height,
                    12, 12);
            if (!menuOpen) {
                Area dim = new Area(new Rectangle2D.Double(0, 0, getWidth(), getHeight()));
                if (null != hole) {
                    dim.subtract(new Area(hole));
                }
                g2.setColor(DIM);
                g2.fill(dim);
            }
            if (null != hole) {
                g2.setColor(accent());
                g2.setStroke(new BasicStroke(2.5f));
                g2.draw(hole);
                paintArrow(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    /** A small triangle from the bubble towards the spotlight. */
    private void paintArrow(Graphics2D g2) {
        Rectangle b = bubble.getBounds();
        Path2D arrow = new Path2D.Double();
        switch (side) {
            case RIGHT -> {
                int y = clamp(spot.y + spot.height / 2, b.y + 16, b.y + b.height - 16);
                arrow.moveTo(b.x + 1, y - 9);
                arrow.lineTo(b.x - GAP + 3, y);
                arrow.lineTo(b.x + 1, y + 9);
            }
            case LEFT -> {
                int y = clamp(spot.y + spot.height / 2, b.y + 16, b.y + b.height - 16);
                arrow.moveTo(b.x + b.width - 1, y - 9);
                arrow.lineTo(b.x + b.width + GAP - 3, y);
                arrow.lineTo(b.x + b.width - 1, y + 9);
            }
            case BELOW -> {
                int x = clamp(spot.x + spot.width / 2, b.x + 16, b.x + b.width - 16);
                arrow.moveTo(x - 9, b.y + 1);
                arrow.lineTo(x, b.y - GAP + 3);
                arrow.lineTo(x + 9, b.y + 1);
            }
            case ABOVE -> {
                int x = clamp(spot.x + spot.width / 2, b.x + 16, b.x + b.width - 16);
                arrow.moveTo(x - 9, b.y + b.height - 1);
                arrow.lineTo(x, b.y + b.height + GAP - 3);
                arrow.lineTo(x + 9, b.y + b.height - 1);
            }
            default -> {
                return;
            }
        }
        arrow.closePath();
        g2.setColor(accent());
        g2.fill(arrow);
    }

    static Color accent() {
        Color c = UIManager.getColor("Component.accentColor");
        if (null == c) {
            c = UIManager.getColor("Component.focusColor");
        }
        return null != c ? c : new Color(38, 117, 191);
    }

    /** The speech bubble: title, text, step counter and buttons. */
    private final class Bubble extends JPanel {
        private final JLabel title = new JLabel();
        private final JLabel text = new JLabel();
        private final JLabel counter = new JLabel();
        private final JLabel done = new JLabel("✓ Done");
        private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        Bubble() {
            super(new BorderLayout(0, 8));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
            addMouseListener(new MouseAdapter() {
                // takes the clicks on the bubble, so they don't reach the window underneath
            });
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
            counter.putClientProperty("FlatLaf.styleClass", "small");
            counter.setEnabled(false);
            done.setForeground(new Color(40, 160, 70));
            done.setFont(done.getFont().deriveFont(Font.BOLD));
            buttons.setOpaque(false);
            JPanel footer = new JPanel(new BorderLayout(8, 0));
            footer.setOpaque(false);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(counter);
            left.add(done);
            footer.add(left, BorderLayout.WEST);
            footer.add(buttons, BorderLayout.EAST);
            add(title, BorderLayout.NORTH);
            add(text, BorderLayout.CENTER);
            add(footer, BorderLayout.SOUTH);
        }

        void show(Tutorial.Step step, int index, int count) {
            title.setText(step.title());
            text.setText("<html><body style='width:" + (BUBBLE_WIDTH - 32) + "px'>" + step.text() + "</body></html>");
            done.setVisible(false);
            buttons.removeAll();
            if (!step.choices().isEmpty()) {
                counter.setText(" ");
                JButton first = null;
                for (Tutorial.Choice choice : step.choices()) {
                    JButton b = new JButton(choice.label());
                    b.addActionListener(e -> choice.action().run());
                    buttons.add(b);
                    if (null == first) {
                        first = b;
                    }
                }
                makeDefault(first);
            } else {
                // The welcome and closing bubbles don't count as steps
                counter.setText(index + " of " + (count - 2));
                boolean last = index == count - 1;
                if (!last) {
                    JButton skip = new JButton("Skip tour");
                    skip.addActionListener(e -> tutorial.close());
                    buttons.add(skip);
                }
                if (index > 1) {
                    JButton back = new JButton("Back");
                    back.addActionListener(e -> tutorial.back());
                    buttons.add(back);
                }
                JButton next = new JButton(last ? "Finish" : "Next");
                next.addActionListener(e -> tutorial.next());
                buttons.add(next);
                makeDefault(next);
                if (last) {
                    counter.setText(" ");
                }
            }
            for (Component c : buttons.getComponents()) {
                c.setFocusable(false); // keep the keyboard on the window, e.g. for the search field
            }
            setSize(getPreferredSize());
            revalidate();
            repaint();
        }

        private void makeDefault(JButton b) {
            if (null != b) {
                b.putClientProperty("JButton.buttonType", null);
                b.setBackground(accent());
                b.setForeground(Color.WHITE);
            }
        }

        void markDone() {
            done.setVisible(true);
            revalidate();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(BUBBLE_WIDTH, d.width), d.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color background = UIManager.getColor("Panel.background");
                g2.setColor(null != background ? background : Color.WHITE);
                g2.fill(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 16, 16));
                g2.setColor(accent());
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 16, 16));
            } finally {
                g2.dispose();
            }
        }
    }
}
