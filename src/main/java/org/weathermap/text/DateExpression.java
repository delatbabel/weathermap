package org.weathermap.text;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A date written the way {@code date --date=STRING} takes one.
 *
 * <p>"tomorrow", "next friday", "3 days ago", "2026-09-15 14:30". A caption is
 * written once and posted every day, so the dates in it have to be expressions
 * rather than dates - which is exactly the problem the {@code date} command
 * already solves, and its notation is worth borrowing rather than inventing a
 * worse one.</p>
 *
 * <h2>Why this is not a call to date(1)</h2>
 *
 * <p>{@code --date} is a GNU extension. BSD {@code date} on macOS does not have
 * it and Windows has no {@code date} of this kind at all, and this application
 * ships packages for all three. Shelling out would work on the machine it was
 * written on and nowhere else.</p>
 *
 * <h2>The rules, taken from the real thing</h2>
 *
 * <p>Established by running GNU {@code date} rather than from memory, because
 * two of them are not what you would guess:</p>
 *
 * <ul>
 *   <li><b>A relative offset keeps the time of day.</b> {@code tomorrow} at
 *       22:17 is tomorrow at 22:17, not tomorrow morning.</li>
 *   <li><b>A day of the week does not.</b> {@code friday} is Friday at
 *       00:00:00, as is {@code 2026-09-15} - naming a date without a time means
 *       midnight.</li>
 *   <li><b>{@code next monday} on a Monday is a week away</b>, while a bare
 *       {@code monday} on a Monday is today. "next" means strictly after today;
 *       a bare weekday, or "this", means on or after it.</li>
 *   <li>An explicit time wins over both: {@code tomorrow 09:00}.</li>
 * </ul>
 *
 * <p>This is a subset. Everything it does not know it says so about, by name,
 * rather than quietly answering with the wrong day - a caption is published
 * before anyone reads it.</p>
 */
public final class DateExpression {

    private DateExpression() { }

    /** An expression this does not understand, with something useful to say. */
    public static final class UnsupportedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public UnsupportedException(String message) { super(message); }
    }

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern TIME =
            Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern SIGNED = Pattern.compile("([+-]?\\d+)");
    private static final Pattern EPOCH = Pattern.compile("@(-?\\d+)");

    /** Where "next"/"last"/"this" put a day of the week. */
    private enum Ordinal { NONE, THIS, NEXT, LAST }

    private record Offset(long amount, ChronoUnit unit) { }

    /**
     * Evaluates {@code expression} against {@code reference}.
     *
     * @throws UnsupportedException if any part of it is not understood
     */
    public static ZonedDateTime evaluate(String expression, ZonedDateTime reference) {
        final String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            throw new UnsupportedException("the date is empty");
        }

        final Matcher epoch = EPOCH.matcher(text);
        if (epoch.matches()) {
            return Instant.ofEpochSecond(Long.parseLong(epoch.group(1)))
                    .atZone(reference.getZone());
        }

        ZonedDateTime result = reference;
        boolean dateGiven = false;
        boolean timeGiven = false;
        DayOfWeek weekday = null;
        Ordinal ordinal = Ordinal.NONE;
        Long pending = null;
        final List<Offset> offsets = new ArrayList<>();

        for (String token : tokenise(text)) {
            final Matcher isoDate = ISO_DATE.matcher(token);
            final Matcher time = TIME.matcher(token);

            if (isoDate.matches()) {
                result = result.with(LocalDate.of(Integer.parseInt(isoDate.group(1)),
                        Integer.parseInt(isoDate.group(2)), Integer.parseInt(isoDate.group(3))));
                dateGiven = true;
            }
            else if (time.matches()) {
                result = result.with(LocalTime.of(
                        Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)),
                        time.group(3) == null ? 0 : Integer.parseInt(time.group(3))));
                timeGiven = true;
            }
            else if (token.equals("am") || token.equals("pm")) {
                if (!timeGiven) throw new UnsupportedException("\"" + token + "\" needs a time before it");
                final int hour = result.getHour() % 12;
                result = result.withHour(token.equals("pm") ? hour + 12 : hour);
            }
            else if (token.equals("now") || token.equals("today")) {
                // The reference moment, unchanged.
            }
            else if (token.equals("tomorrow")) {
                offsets.add(new Offset(1, ChronoUnit.DAYS));
            }
            else if (token.equals("yesterday")) {
                offsets.add(new Offset(-1, ChronoUnit.DAYS));
            }
            else if (token.equals("next") || token.equals("last") || token.equals("this")) {
                ordinal = switch (token) {
                    case "next" -> Ordinal.NEXT;
                    case "last" -> Ordinal.LAST;
                    default -> Ordinal.THIS;
                };
            }
            else if (token.equals("ago")) {
                // "ago" reverses everything relative said so far: "3 days ago",
                // and equally "2 hours 30 minutes ago".
                for (int i = 0; i < offsets.size(); i++) {
                    offsets.set(i, new Offset(-offsets.get(i).amount(), offsets.get(i).unit()));
                }
            }
            else if (SIGNED.matcher(token).matches()) {
                pending = Long.parseLong(token);
            }
            else if (unitOf(token) != null) {
                final ChronoUnit unit = unitOf(token);
                long amount = pending != null ? pending
                        : switch (ordinal) {
                            case LAST -> -1;
                            case THIS -> 0;
                            default -> 1;      // "next week", and a bare "week"
                        };
                if (token.startsWith("fortnight")) amount *= 2;
                offsets.add(new Offset(amount, unit));
                pending = null;
                ordinal = Ordinal.NONE;
            }
            else if (dayOf(token) != null) {
                weekday = dayOf(token);
            }
            else {
                throw new UnsupportedException("\"" + token + "\" is not something this "
                        + "understands. It knows now, today, tomorrow, yesterday, "
                        + "day names, offsets such as \"+3 days\" or \"2 weeks ago\", "
                        + "dates as 2026-09-15, times as 14:30, and @seconds.");
            }
        }
        if (pending != null) {
            throw new UnsupportedException("\"" + pending + "\" has no unit after it - "
                    + "try \"" + pending + " days\"");
        }

        for (Offset offset : offsets) {
            result = result.plus(offset.amount(), offset.unit());
        }
        if (weekday != null) {
            result = moveTo(result, weekday, ordinal);
        }
        // Naming a date, or a day, without a time means midnight.
        if ((dateGiven || weekday != null) && !timeGiven) {
            result = result.truncatedTo(ChronoUnit.DAYS);
        }
        return result;
    }

    /**
     * @param ordinal NEXT skips today even when today is the day asked for;
     *                a bare weekday, or THIS, accepts today; LAST looks back
     */
    private static ZonedDateTime moveTo(ZonedDateTime from, DayOfWeek day, Ordinal ordinal) {
        final int today = from.getDayOfWeek().getValue();
        final int wanted = day.getValue();
        if (ordinal == Ordinal.LAST) {
            int back = today - wanted;
            if (back <= 0) back += 7;
            return from.minusDays(back);
        }
        int forward = wanted - today;
        if (forward < 0) forward += 7;
        if (forward == 0 && ordinal == Ordinal.NEXT) forward = 7;
        return from.plusDays(forward);
    }

    /** Splits on whitespace and commas, and separates "3days" into "3" and "days". */
    private static List<String> tokenise(String text) {
        final String spaced = text.toLowerCase(Locale.ROOT)
                .replace(',', ' ')
                .replaceAll("(?<=\\d)(?=[a-z])", " ")
                .replaceAll("(?<=[a-z])(?=[+-]?\\d)", " ")
                // 2026-09-15T14:30 is one token to a human and two to this.
                .replaceAll("(?<=\\d)t(?=\\d{1,2}:)", " ");
        final List<String> out = new ArrayList<>();
        for (String token : spaced.trim().split("\\s+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    private static ChronoUnit unitOf(String token) {
        final String word = token.endsWith("s") && token.length() > 1
                ? token.substring(0, token.length() - 1) : token;
        return switch (word) {
            case "second", "sec" -> ChronoUnit.SECONDS;
            case "minute", "min" -> ChronoUnit.MINUTES;
            case "hour" -> ChronoUnit.HOURS;
            case "day" -> ChronoUnit.DAYS;
            case "week" -> ChronoUnit.WEEKS;
            case "fortnight" -> ChronoUnit.WEEKS;
            case "month" -> ChronoUnit.MONTHS;
            case "year" -> ChronoUnit.YEARS;
            default -> null;
        };
    }

    private static DayOfWeek dayOf(String token) {
        return switch (token) {
            case "monday", "mon" -> DayOfWeek.MONDAY;
            case "tuesday", "tue", "tues" -> DayOfWeek.TUESDAY;
            case "wednesday", "wed" -> DayOfWeek.WEDNESDAY;
            case "thursday", "thu", "thur", "thurs" -> DayOfWeek.THURSDAY;
            case "friday", "fri" -> DayOfWeek.FRIDAY;
            case "saturday", "sat" -> DayOfWeek.SATURDAY;
            case "sunday", "sun" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }
}
