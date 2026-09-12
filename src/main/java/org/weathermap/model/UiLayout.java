package org.weathermap.model;

import java.util.logging.Logger;

/**
 * Where the window was and how it was divided, so the next run looks like the
 * last one.
 *
 * <p>A window that reopens at a default size every time is a small insult
 * repeated daily: anyone who has widened the map to see a front, or dragged the
 * data panel taller to work through a long variable list, has to do it again
 * before they can start.</p>
 *
 * <p>Toolkit-free on purpose. {@link Preferences} is read by the command-line
 * tool, which has no windows and must not load AWT classes to find that out, so
 * this carries plain integers rather than a {@code Rectangle}.</p>
 *
 * @param x            window position; {@link #UNSET} to let the platform decide
 * @param y            window position
 * @param width        window size
 * @param height       window size
 * @param mainDivider  pixels from the left edge to the split between the map and
 *                     the side panel, or {@link #UNSET}
 * @param sideDivider  pixels from the top of the side panel to the split between
 *                     the area controls and the GRIB controls, or {@link #UNSET}
 */
public record UiLayout(int x, int y, int width, int height,
                       int mainDivider, int sideDivider) {

    private static final Logger LOG = Logger.getLogger(UiLayout.class.getName());

    /** A value that was never stored, or was stored and is no longer usable. */
    public static final int UNSET = -1;

    /** What the window opens at the first time it is ever run. */
    public static final UiLayout DEFAULT =
            new UiLayout(UNSET, UNSET, 1280, 800, UNSET, UNSET);

    /**
     * Smallest window worth restoring to.
     *
     * <p>A stored size below this is treated as absent rather than honoured. It
     * means something went wrong - a window saved while minimised on some
     * platforms reports a few pixels - and restoring it hands back a window too
     * small to find the controls in, which looks like the application failing
     * to start.</p>
     */
    public static final int MIN_SENSIBLE = 320;

    public boolean hasSize() {
        return width >= MIN_SENSIBLE && height >= MIN_SENSIBLE;
    }

    public boolean hasPosition() {
        return x != UNSET && y != UNSET;
    }

    public UiLayout withWindow(int x, int y, int width, int height) {
        return new UiLayout(x, y, width, height, mainDivider, sideDivider);
    }

    public UiLayout withDividers(int mainDivider, int sideDivider) {
        return new UiLayout(x, y, width, height, mainDivider, sideDivider);
    }

    /** Six integers, comma-separated, in declaration order. */
    @Override
    public String toString() {
        return x + "," + y + "," + width + "," + height + ","
                + mainDivider + "," + sideDivider;
    }

    /**
     * @return the layout this string describes, or {@link #DEFAULT} if it
     *         describes nothing usable
     */
    public static UiLayout parse(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        final String[] parts = s.split(",");
        if (parts.length != 6) {
            LOG.warning("Ignoring unreadable window layout: " + s);
            return DEFAULT;
        }
        try {
            final int[] v = new int[6];
            for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(parts[i].trim());
            final UiLayout layout = new UiLayout(v[0], v[1], v[2], v[3], v[4], v[5]);
            // A stored size that is not sensible falls back to the default size
            // while keeping whatever else was readable.
            return layout.hasSize() ? layout
                    : layout.withWindow(layout.x(), layout.y(),
                                        DEFAULT.width(), DEFAULT.height());
        }
        catch (NumberFormatException e) {
            LOG.warning("Ignoring unreadable window layout: " + s);
            return DEFAULT;
        }
    }
}
