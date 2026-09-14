package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Copies must carry everything, checked by reflection rather than by listing.
 *
 * <p>Both copy methods are written field by field, and the desktop application
 * runs on copies while the command-line tool does not. That asymmetry turns any
 * omission into the worst kind of bug: a setting that demonstrably works from
 * the command line and silently does nothing in the window, with no error to
 * follow.</p>
 *
 * <p>It has happened twice - to the backward leg of a series, and to the chart
 * time zone. A test that lists the fields it expects would have missed both,
 * because whoever adds a field is exactly the person who would forget to add it
 * to the list. So this walks the declared fields instead: add one, forget to
 * copy it, and this fails without anyone having to remember.</p>
 *
 * <p>That was almost true. Walking the fields only catches a dropped one if the
 * original has something in it worth dropping, and a field the fixture never
 * sets sits at its default in both - equal, and silently untested. A rename map
 * was added and copying it could be deleted outright with this still passing,
 * which is the same forgetting the reflection was supposed to remove, one step
 * further back. So every field is now required to differ from a newly built
 * object as well: leave one out of the fixture and it says so.</p>
 */
class CopyCompletenessTest {

    @Test
    void aRenderSpecCopyCarriesEveryField() throws Exception {
        final RenderSpec original = new RenderSpec();
        original.setMaxSize(1234, 567);
        original.setMercator(true);
        original.setGribOpacity(0.42f);
        original.setAutoScaleRamp(false);
        original.setZone(ZoneId.of("Asia/Kathmandu"));
        original.setEnabled(RenderSpec.LayerKind.GRATICULE, true);
        original.setEnabled(RenderSpec.LayerKind.ISOBARS, false);

        original.renameLabel("South China Sea", "East Sea");

        assertEveryFieldMatches(original, original.copy(), new RenderSpec());
    }

    @Test
    void aGribSelectionCopyCarriesEveryField() throws Exception {
        final GribSelection original = new GribSelection();
        original.setModel(GribModel.GFS_0P50);
        original.setForecastHours(List.of(0, 6, 12));
        original.setVariables(Set.of(GribCatalog.WIND, GribCatalog.PRECIPITATION));
        original.setLevels(Set.of(GribCatalog.LEVEL_10M, GribCatalog.LEVEL_SURFACE));
        original.setSeries(6, 24, 72);
        original.setRun(java.time.LocalDate.of(2026, 9, 11), 18);

        assertEveryFieldMatches(original, original.copy(), new GribSelection());
    }

    /**
     * @param pristine a newly built object, so a field the fixture forgot to
     *                 change can be told from one that was genuinely copied
     */
    private static void assertEveryFieldMatches(Object original, Object copy, Object pristine)
            throws Exception {
        for (Field field : original.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);

            assertNotEquals(field.get(pristine), field.get(original),
                            field.getName() + " is still at its default in this test, so "
                                    + "whether copy() carries it is not actually being "
                                    + "checked - give it a value above");

            assertEquals(field.get(original), field.get(copy),
                         field.getName() + " was not carried into the copy - "
                                 + "add it to " + original.getClass().getSimpleName()
                                 + ".copy()");
        }
    }
}
