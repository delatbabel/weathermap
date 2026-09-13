package org.weathermap.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Named download profiles, one file each under {@code ~/.weathermap/profiles}.
 *
 * <h2>Why a file each</h2>
 *
 * <p>A directory of small files can be listed, copied to another machine,
 * deleted with {@code rm}, and put under version control, none of which is true
 * of sections inside one large file. It also means a corrupt profile costs one
 * profile rather than all of them.</p>
 *
 * <h2>Why the same format as the preferences</h2>
 *
 * <p>A profile is an area and a {@link GribSelection}, which is precisely what
 * {@link Preferences} already reads and writes. Reusing it means one codec for
 * bounding boxes, variable lists and series definitions instead of two that can
 * drift apart - and a profile written by an older version keeps working for the
 * same reason the preferences file does.</p>
 *
 * <h2>Names</h2>
 *
 * <p>The file is named after the profile with anything awkward replaced, and the
 * name the user typed is stored inside it, so a profile can be called
 * "Ushant → Finisterre" and still live on a filesystem that would rather it did
 * not. Two names that reduce to the same file name are the same profile; that is
 * a deliberate simplification, and the save path asks before overwriting.</p>
 */
public final class ProfileStore {

    private static final Logger LOG = Logger.getLogger(ProfileStore.class.getName());

    public static final String DIRECTORY = "profiles";
    private static final String SUFFIX = ".properties";

    /** The display name, kept inside the file because the file name is sanitised. */
    private static final String KEY_NAME = "profile.name";

    private final Path directory;

    public ProfileStore() {
        this(Path.of(System.getProperty("user.home"), Preferences.CONFIG_DIR, DIRECTORY));
    }

    public ProfileStore(Path directory) {
        this.directory = directory;
    }

    public Path directory() { return directory; }

    /**
     * Every profile's display name, in case-insensitive order.
     *
     * <p>A file that cannot be read is skipped with a warning rather than
     * failing the listing: one unreadable profile should not hide the rest.</p>
     */
    public List<String> names() {
        if (!Files.isDirectory(directory)) return List.of();

        final List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(SUFFIX)).toList()) {
                final Preferences stored = new Preferences(file);
                final String name = stored.property(KEY_NAME);
                out.add(name == null || name.isBlank() ? stripSuffix(file) : name);
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list " + directory, e);
        }
        // Collated rather than compared by code point, so "Åland" sorts with the
        // other A's instead of after Z where a byte comparison puts it. This is
        // a list someone reads down to find a name.
        final java.text.Collator collator = java.text.Collator.getInstance();
        collator.setStrength(java.text.Collator.SECONDARY);
        out.sort(collator);
        return out;
    }

    public boolean exists(String name) {
        return Files.isRegularFile(fileFor(name));
    }

    /** @return the profile, or empty when there is no such file */
    public Optional<Profile> load(String name) {
        final Path file = fileFor(name);
        if (!Files.isRegularFile(file)) return Optional.empty();

        final Preferences stored = new Preferences(file);
        final String display = stored.property(KEY_NAME);
        return Optional.of(new Profile(
                display == null || display.isBlank() ? name : display,
                stored.area(),
                stored.selection()));
    }

    /**
     * Writes a profile, replacing any with the same name.
     *
     * @throws IOException if the directory cannot be created or the file written
     */
    public void save(String name, BoundingBox area, GribSelection selection) throws IOException {
        Files.createDirectories(directory);

        // A fresh Preferences on the profile's own path, so the file contains
        // the profile and nothing else - no theme, no window layout, none of the
        // things that belong to the machine rather than to the question.
        final Preferences stored = new Preferences(fileFor(name));
        stored.setProperty(KEY_NAME, name.trim());
        stored.setArea(area);
        stored.setSelection(selection);
        stored.saveOrThrow();
        LOG.info(() -> "Saved profile " + name + " to " + fileFor(name));
    }

    /** @return true if a profile was deleted */
    public boolean delete(String name) {
        try {
            return Files.deleteIfExists(fileFor(name));
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete profile " + name, e);
            return false;
        }
    }

    Path fileFor(String name) {
        return directory.resolve(sanitise(name) + SUFFIX);
    }

    /**
     * A file name that every filesystem will accept.
     *
     * <p>Lower-cased so that "Channel" and "channel" are one profile rather than
     * two that differ only where the filesystem happens to care.</p>
     */
    static String sanitise(String name) {
        final String cleaned = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._ -]", "_")
                .replaceAll("\\s+", "-")
                .replaceAll("^[.]+", "_");
        return cleaned.isBlank() ? "profile" : cleaned;
    }

    private static String stripSuffix(Path file) {
        final String name = file.getFileName().toString();
        return name.substring(0, name.length() - SUFFIX.length());
    }
}
