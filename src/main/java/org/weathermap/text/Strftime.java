package org.weathermap.text;

import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.Locale;

/**
 * A date written the way {@code date +FORMAT} writes one.
 *
 * <p>{@code %A %e %B %Y} for "Tuesday 15 September 2026". The same notation the
 * {@code date} command uses, for the same reason {@link DateExpression} borrows
 * its input notation: it is already known, already documented, and already in
 * the fingers of anyone who would want this.</p>
 *
 * <p>Padding is the part people get wrong, so it follows {@code date} exactly:
 * {@code %d} is zero-padded, {@code %e} space-padded, {@code %-d} not padded at
 * all. On the fifth of the month those are "05", " 5" and "5".</p>
 *
 * <p>Unknown conversions are refused by name rather than passed through. A
 * caption is published before anyone reads it, and a literal {@code %Q} on the
 * post is worse than being told about it while there is still time.</p>
 */
public final class Strftime {

    private Strftime() { }

    /** A conversion this does not implement. */
    public static final class UnsupportedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public UnsupportedException(String message) { super(message); }
    }

    /** Every conversion understood, for an error message worth reading. */
    public static final String SUPPORTED =
            "%a %A %b %B %C %d %D %e %F %h %H %I %j %k %l %m %M %n %p %P "
            + "%r %R %s %S %t %T %u %V %w %y %Y %z %Z %%";

    /**
     * @param format a {@code date}-style format; a leading {@code +} and
     *               surrounding quotes are accepted and ignored, so a format
     *               can be pasted straight from a shell command
     */
    public static String format(ZonedDateTime when, String format, Locale locale) {
        final String pattern = unwrap(format);
        final StringBuilder out = new StringBuilder(pattern.length() + 16);

        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (i + 1 >= pattern.length()) {
                throw new UnsupportedException("the format ends with a stray %");
            }

            // Flags, then an optional width, then the conversion.
            char pad = 0;
            boolean upper = false;
            int j = i + 1;
            for (; j < pattern.length(); j++) {
                final char flag = pattern.charAt(j);
                if (flag == '-' || flag == '_' || flag == '0') pad = flag;
                else if (flag == '^') upper = true;
                else break;
            }
            int width = 0;
            while (j < pattern.length() && Character.isDigit(pattern.charAt(j))) {
                width = width * 10 + (pattern.charAt(j++) - '0');
            }
            if (j >= pattern.length()) {
                throw new UnsupportedException("the format ends with a stray %");
            }

            String piece = convert(when, pattern.charAt(j), locale);
            piece = repad(piece, pad, width);
            out.append(upper ? piece.toUpperCase(locale) : piece);
            i = j;
        }
        return out.toString();
    }

    /**
     * Strips the {@code +} and any quotes a format was copied with.
     *
     * <p>In either order, and either kind of quote: a format may be written
     * {@code '+%F'} as it would be in a shell, or {@code "+%F"}, or plainly.</p>
     */
    public static String unwrap(String format) {
        String text = format == null ? "" : format.trim();
        if (text.length() >= 2
                && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.startsWith("+")) text = text.substring(1);
        return text;
    }

    private static String convert(ZonedDateTime t, char conversion, Locale locale) {
        return switch (conversion) {
            case 'a' -> t.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
            case 'A' -> t.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
            case 'b', 'h' -> t.getMonth().getDisplayName(TextStyle.SHORT, locale);
            case 'B' -> t.getMonth().getDisplayName(TextStyle.FULL, locale);
            case 'C' -> pad(t.getYear() / 100, 2);
            case 'd' -> pad(t.getDayOfMonth(), 2);
            case 'D' -> format(t, "%m/%d/%y", locale);
            case 'e' -> space(t.getDayOfMonth(), 2);
            case 'F' -> format(t, "%Y-%m-%d", locale);
            case 'H' -> pad(t.getHour(), 2);
            case 'I' -> pad(hour12(t), 2);
            case 'j' -> pad(t.getDayOfYear(), 3);
            case 'k' -> space(t.getHour(), 2);
            case 'l' -> space(hour12(t), 2);
            case 'm' -> pad(t.getMonthValue(), 2);
            case 'M' -> pad(t.getMinute(), 2);
            case 'n' -> "\n";
            case 'p' -> t.getHour() < 12 ? "AM" : "PM";
            case 'P' -> t.getHour() < 12 ? "am" : "pm";
            case 'r' -> format(t, "%I:%M:%S %p", locale);
            case 'R' -> format(t, "%H:%M", locale);
            case 's' -> Long.toString(t.toEpochSecond());
            case 'S' -> pad(t.getSecond(), 2);
            case 't' -> "\t";
            case 'T' -> format(t, "%H:%M:%S", locale);
            case 'u' -> Integer.toString(t.getDayOfWeek().getValue());
            case 'V' -> pad(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 2);
            case 'w' -> Integer.toString(t.getDayOfWeek().getValue() % 7);
            case 'y' -> pad(t.getYear() % 100, 2);
            case 'Y' -> Integer.toString(t.getYear());
            case 'z' -> offset(t, false);
            case 'Z' -> offset(t, true);
            case '%' -> "%";
            default -> throw new UnsupportedException(
                    "%" + conversion + " is not one this understands. It knows: " + SUPPORTED);
        };
    }

    private static int hour12(ZonedDateTime t) {
        final int hour = t.get(ChronoField.CLOCK_HOUR_OF_AMPM);
        return hour;
    }

    /**
     * The zone as {@code date} writes it.
     *
     * <p>{@code %Z} is the zone's name, which for a plain offset zone is the
     * offset - GNU prints "+07" for Asia/Bangkok, not "ICT", when the zone came
     * from TZ as an offset. The chart labels settled on offsets for the same
     * reason: an abbreviation means nothing to a reader in another country.</p>
     */
    private static String offset(ZonedDateTime t, boolean named) {
        final String id = t.getOffset().getId();          // "Z" or "+07:00"
        if (named) return id.equals("Z") ? "UTC" : id.replace(":00", "").replace(":", "");
        if (id.equals("Z")) return "+0000";
        return id.replace(":", "");
    }

    private static String pad(int value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    private static String space(int value, int width) {
        return String.format(Locale.ROOT, "%" + width + "d", value);
    }

    /** Applies a {@code -}, {@code _} or {@code 0} flag, and any explicit width. */
    private static String repad(String piece, char pad, int width) {
        String text = piece;
        if (pad == '-') {
            text = stripLeading(text);
        }
        else if (pad == '_' || pad == '0') {
            final char with = pad == '0' ? '0' : ' ';
            final String bare = stripLeading(text);
            final int target = Math.max(width, text.length());
            text = String.valueOf(with).repeat(Math.max(0, target - bare.length())) + bare;
        }
        if (width > text.length()) {
            text = " ".repeat(width - text.length()) + text;
        }
        return text;
    }

    private static String stripLeading(String text) {
        int i = 0;
        while (i < text.length() - 1 && (text.charAt(i) == '0' || text.charAt(i) == ' ')) i++;
        return text.substring(i);
    }
}
