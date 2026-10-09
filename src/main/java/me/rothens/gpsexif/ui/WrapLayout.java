package me.rothens.gpsexif.ui;

import javax.swing.*;
import java.awt.*;

/**
 * A {@link FlowLayout} that wraps onto more lines when its container is too narrow, and asks for the height of
 * all its lines, so nothing is cut off at the end of a row (long translations, large fonts, small windows).
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    /** The size last asked for; while the container is laid out at a new width it may need another. */
    private Dimension reported;

    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        // The parent asked for the size before this container had its new width: ask again with it
        Dimension now = layoutSize(target, true);
        if (null != reported && !now.equals(reported)) {
            SwingUtilities.invokeLater(target::revalidate);
        }
        reported = now;
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        reported = layoutSize(target, true);
        return reported;
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // The width to wrap at: the container's own, or its parent's before it was laid out
            Container sized = target;
            while (sized.getSize().width == 0 && null != sized.getParent()) {
                sized = sized.getParent();
            }
            int targetWidth = sized.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }
            Insets insets = target.getInsets();
            int maxWidth = targetWidth - (insets.left + insets.right + getHgap() * 2);
            Dimension size = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component c : target.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth > 0 && rowWidth + getHgap() + d.width > maxWidth) {
                    addRow(size, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += (rowWidth > 0 ? getHgap() : 0) + d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(size, rowWidth, rowHeight);
            size.width += insets.left + insets.right + getHgap() * 2;
            size.height += insets.top + insets.bottom + getVgap() * 2;
            // In a scroll pane, the parent would never let it shrink again
            Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (null != scroll && target.isValid()) {
                size.width -= getHgap() + 1;
            }
            return size;
        }
    }

    private void addRow(Dimension size, int rowWidth, int rowHeight) {
        size.width = Math.max(size.width, rowWidth);
        if (size.height > 0) {
            size.height += getVgap();
        }
        size.height += rowHeight;
    }
}
