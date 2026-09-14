package org.weathermap.text;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A caption with {@code ${...}} parameters in it, expanded when it is posted.
 *
 * <p>A daily chart's caption is the same sentence every day with the date
 * changed. Storing the text and retyping the date is the arrangement that
 * guarantees the day someone forgets; storing
 * {@code ${tomorrow:'+%A %e %B %Y'}} is the arrangement that cannot.</p>
 *
 * <p>The stored caption keeps the template, not the result. Expansion happens
 * at the moment of posting, so tomorrow's post says tomorrow's date without
 * anybody touching it.</p>
 *
 * <h2>The parameters</h2>
 *
 * <p>{@code ${DATE:FORMAT}}, where {@code DATE} is any expression
 * {@link DateExpression} takes - the {@code --date} of {@code date(1)} - and
 * {@code FORMAT} is its {@code +FORMAT}, handled by {@link Strftime}. The
 * format may keep the quotes and the {@code +} it would have in a shell, so one
 * can be pasted straight across.</p>
 *
 * <p>{@code ${DATE}} alone gives the ISO date, {@code %F}. Either half may be
 * wrapped in single or double quotes, which is how a date containing a colon -
 * a time - is kept on its own side of the divider.</p>
 *
 * <p>{@code $${} is a literal {@code ${}, for a caption that needs one.</p>
 *
 * <h2>Where the two parts are divided</h2>
 *
 * <p>Not at the first colon, and not at the last: both halves can contain one.
 * {@code ${2026-09-15 14:30:'+%H:%M'}} has four, and only the third divides it.
 * The format is the half that starts with {@code +}, so the divider is the last
 * colon whose remainder does - which is unambiguous, and leaves a time free to
 * appear on either side.</p>
 */
public final class CaptionTemplate {

    private CaptionTemplate() { }

    /** A parameter that could not be expanded, and why. */
    public record Problem(String parameter, String reason) {
        @Override
        public String toString() { return "${" + parameter + "} - " + reason; }
    }

    /** What {@code ${DATE}} means with no format given. */
    private static final String DEFAULT_FORMAT = "%F";

    /** True if there is anything in here to expand. */
    public static boolean hasParameters(String template) {
        return template != null && template.contains("${");
    }

    /**
     * Expands every parameter.
     *
     * <p>A parameter that cannot be expanded is left exactly as it was written,
     * so this never throws and never silently invents a date. Call
     * {@link #problems} first - the dialog does - to refuse the post instead of
     * publishing the raw text.</p>
     *
     * @param now    the moment the dates are relative to
     * @param locale for day and month names
     */
    public static String expand(String template, ZonedDateTime now, Locale locale) {
        return walk(template, now, locale, null);
    }

    /** Everything wrong with the template, in the order it is written. */
    public static List<Problem> problems(String template, ZonedDateTime now, Locale locale) {
        final List<Problem> found = new ArrayList<>();
        walk(template, now, locale, found);
        return found;
    }

    /**
     * One pass over the text, expanding or complaining.
     *
     * @param problems collects failures when given; when null they are left as
     *                 written
     */
    private static String walk(String template, ZonedDateTime now, Locale locale,
                               List<Problem> problems) {
        if (template == null || template.isEmpty()) return "";

        final StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            final int open = template.indexOf("${", i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            // $${ is an escaped ${ - the only escape there is.
            if (open > 0 && template.charAt(open - 1) == '$') {
                out.append(template, i, open - 1).append("${");
                i = open + 2;
                continue;
            }
            final int close = template.indexOf('}', open);
            if (close < 0) {
                // An unclosed parameter is text, not a parameter.
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);

            final String body = template.substring(open + 2, close);
            try {
                out.append(resolve(body, now, locale));
            }
            catch (IllegalArgumentException e) {
                if (problems != null) problems.add(new Problem(body, e.getMessage()));
                out.append(template, open, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static String resolve(String body, ZonedDateTime now, Locale locale) {
        final int divide = divider(body);
        final String date = unquote(divide < 0 ? body : body.substring(0, divide));
        final String format = divide < 0 ? DEFAULT_FORMAT : body.substring(divide + 1);

        return Strftime.format(DateExpression.evaluate(date, now), format, locale);
    }

    /**
     * The colon between the date and the format, or -1 when there is no format.
     *
     * <p>A quoted date settles it outright: the divider is the first colon after
     * the closing quote, and everything inside the quotes is the date however
     * many colons it contains.</p>
     *
     * <p>Unquoted, the format is the half that begins with {@code +} once its
     * own quotes are off, so the divider is the last colon whose remainder does.
     * {@code ${2026-09-15 14:30:'+%H:%M'}} has four colons and only the third
     * divides it. Falling back to the last colon when no half claims to be a
     * format, so one written without its {@code +} still works.</p>
     */
    private static int divider(String body) {
        if (!body.isEmpty() && isQuote(body.charAt(0))) {
            final int end = body.indexOf(body.charAt(0), 1);
            if (end > 0) return body.indexOf(':', end + 1);
        }
        int fallback = -1;
        for (int i = body.length() - 1; i >= 0; i--) {
            if (body.charAt(i) != ':') continue;
            if (fallback < 0) fallback = i;
            if (unquote(body.substring(i + 1)).startsWith("+")) return i;
        }
        return fallback;
    }

    /** Drops one matching pair of surrounding quotes, of either kind. */
    static String unquote(String text) {
        final String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() >= 2 && isQuote(trimmed.charAt(0))
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isQuote(char c) { return c == '\'' || c == '"'; }
}
