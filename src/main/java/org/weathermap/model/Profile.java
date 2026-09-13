package org.weathermap.model;

/**
 * A named download: where, from which model, and which fields.
 *
 * <p>Exactly the things that describe <em>what to fetch</em> - the area, the
 * model and run, the forecast hours or series, the variables and the levels -
 * and nothing about how the result is drawn. Someone who works a home coastline
 * and an ocean passage wants to switch between those two questions without
 * losing their theme, their time zone or the size of their window, so the
 * rendering settings deliberately stay where they are.</p>
 *
 * @param name      what the user called it
 * @param area      the rectangle to fetch
 * @param selection the model, run, hours or series, variables and levels
 */
public record Profile(String name, BoundingBox area, GribSelection selection) {

    public Profile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a profile needs a name");
        }
        name = name.trim();
    }

    @Override
    public String toString() { return name; }
}
