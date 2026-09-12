package org.weathermap.model;

import java.util.Locale;

/**
 * Which of the two looks the desktop application wears.
 *
 * <p>In {@code model} rather than {@code gui} because {@link Preferences} has to
 * read and write it, and the preferences file is shared with the command-line
 * tool - which must never load a look and feel, or Swing at all. The enum is the
 * choice; {@code gui.Themes} is the only thing that knows what a
 * {@code FlatLaf} is.</p>
 *
 * <p>Two, deliberately. A theme picker with a dozen entries is a settings screen;
 * light and dark is a decision someone makes once about the room they are
 * sitting in.</p>
 */
public enum Theme {

    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    private final String id;
    private final String displayName;

    Theme(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The stable token written to the preferences file. */
    public String id() { return id; }

    public String displayName() { return displayName; }

    /**
     * @return the theme with this id, or {@link #LIGHT} for anything
     *         unrecognised - an unreadable preference should give a working
     *         window, not an error
     */
    public static Theme byId(String id) {
        if (id != null) {
            final String wanted = id.trim().toLowerCase(Locale.ROOT);
            for (Theme t : values()) {
                if (t.id.equals(wanted)) return t;
            }
        }
        return LIGHT;
    }
}
