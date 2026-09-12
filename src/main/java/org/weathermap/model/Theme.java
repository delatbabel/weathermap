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
 * <p>Three, and no more. A theme picker with a dozen entries is a settings
 * screen; light or dark is a decision someone makes once about the room they
 * are sitting in, and {@link #SYSTEM} is for the people who would rather the
 * application stopped having opinions and matched the rest of their
 * desktop.</p>
 */
public enum Theme {

    LIGHT("light", "Light"),
    DARK("dark", "Dark"),

    /**
     * Whatever the desktop itself uses.
     *
     * <p>Not a third set of colours but a refusal to choose: the platform look
     * and feel, which on a well-set-up desktop already matches the other windows
     * on it. It is the only option whose appearance this application does not
     * control, so it is also the only one that can look different on two
     * machines - which is the point of asking for it.</p>
     */
    SYSTEM("system", "System");

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
