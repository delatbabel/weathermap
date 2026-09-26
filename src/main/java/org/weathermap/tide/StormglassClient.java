package org.weathermap.tide;

import org.weathermap.util.Cache;
import org.weathermap.util.Http;
import org.weathermap.util.Json;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Tide predictions from the Storm Glass API.
 *
 * <h2>Two requests, because there are two questions</h2>
 *
 * <p>{@code /tide/sea-level/point} gives the curve hour by hour and
 * {@code /tide/extremes/point} gives the turning points. The chart needs both
 * and neither can be derived from the other: picking the maxima out of hourly
 * samples would put every high water on the hour and understate it, which on a
 * two-metre range is tens of centimetres and half an hour of error.</p>
 *
 * <h2>The daily limit is the design constraint</h2>
 *
 * <p>Storm Glass counts requests against a daily quota - ten a day on the free
 * tier - so this asks for ten days at once and keeps the answer for a day. Two
 * requests then serve every chart for that place until tomorrow, whichever of
 * those days is being looked at, and paging through them costs nothing. Asking
 * per day instead would have spent the whole free quota on five days of one
 * harbour.</p>
 *
 * <p>The cache is keyed on the request URL, which carries the coordinates and
 * the dates, and the coordinates are rounded first - see
 * {@link #COORDINATE_PLACES}. The responses are stored as they arrived, the way
 * the GRIB and Overpass caches store theirs, so a parser change does not
 * invalidate a day of cached answers.</p>
 *
 * <h2>Errors worth knowing</h2>
 *
 * <ul>
 *   <li><b>402</b> is the daily limit, not a payment problem to solve now. It
 *       clears at midnight UTC.</li>
 *   <li><b>403</b> means the key is missing or malformed - the usual cause is
 *       an empty Preferences field, which is checked for before any request so
 *       the user is sent somewhere useful.</li>
 *   <li>The key goes in the {@code Authorization} header <b>bare</b>, with no
 *       {@code Bearer} prefix, which is unusual enough to be worth saying.</li>
 * </ul>
 */
public final class StormglassClient implements TideSource {

    private static final Logger LOG = Logger.getLogger(StormglassClient.class.getName());

    /**
     * Where the service lives.
     *
     * <p>Overridable by system property, the way {@code Http.USER_AGENT} is,
     * so the window can be pointed at a local stand-in and driven end to end
     * without a key or a request against anyone's quota.</p>
     */
    public static final String DEFAULT_BASE = System.getProperty(
            "weathermap.stormglassBase", "https://api.stormglass.io/v2/");

    /**
     * How far ahead to ask for, in days.
     *
     * <p>Ten, which is also what the API documents as the default end when no
     * {@code end} is given - so this asks for exactly as much as the service
     * offers without being asked, and cannot run past a plan's limit. The
     * astronomical tide is known indefinitely far ahead; the constraint is the
     * response size and what anyone actually plans against.</p>
     *
     * <p>Nothing assumes the whole ten days arrives. The days on offer in the
     * window are built from what came back, so a shorter answer is a shorter
     * chart rather than an error.</p>
     */
    public static final int DAYS_AHEAD = 10;

    /**
     * How long a stored answer is reused.
     *
     * <p>A day, as a deliberate ceiling rather than a guess at how fast the
     * data changes - the astronomical tide for next Tuesday is the same
     * whenever it is asked for. What changes daily is which days are in range,
     * and the quota resets.</p>
     */
    public static final java.time.Duration TTL = java.time.Duration.ofHours(24);

    /**
     * Decimal places the coordinates are rounded to before the request.
     *
     * <p>Three, which is about a hundred metres - far finer than the spacing of
     * tide stations, and coarse enough that the cache key stops moving. Without
     * this, a point taken from the centre of a dragged rectangle is a slightly
     * different number every time and every look at the same harbour is two
     * more requests against the daily quota.</p>
     */
    public static final int COORDINATE_PLACES = 3;

    private final String base;
    private final Cache cache;
    private final Supplier<String> apiKey;

    /**
     * @param apiKey read when a request is made rather than held, so a key
     *               typed into Preferences takes effect without a restart
     */
    public StormglassClient(Supplier<String> apiKey) {
        this(DEFAULT_BASE, new Cache(), apiKey);
    }

    public StormglassClient(String base, Cache cache, Supplier<String> apiKey) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.cache = cache;
        this.apiKey = apiKey;
    }

    @Override
    public String description() {
        return "Storm Glass at " + URI.create(base).getHost();
    }

    /** True when there is a key to make a request with at all. */
    public boolean hasApiKey() {
        final String key = apiKey.get();
        return key != null && !key.isBlank();
    }

    /** The ten days from yesterday, which is what the window asks for. */
    public TideData fetchForecast(double lat, double lon)
            throws IOException, InterruptedException {
        // From yesterday, not today: the days on the chart are local days, and
        // for a zone ahead of UTC the local day already under way began before
        // the UTC one. Starting at today's UTC midnight leaves the first hours
        // of the chart empty for everyone east of Greenwich.
        // Keep the window in step with hasCachedForecast, which rebuilds the
        // same two URLs to find out whether they are already on disk.
        return fetch(lat, lon, LocalDate.now(ZoneOffset.UTC).minusDays(1), DAYS_AHEAD + 2);
    }

    /**
     * True when {@link #fetchForecast} for this point would cost nothing.
     *
     * <p>Asked before the confirmation dialog, so that it can say whether
     * pressing the button spends two of the day's requests or none. With ten a
     * day that is the difference between idly comparing four harbours and
     * running out before lunch, and it is not something a user can work out
     * for themselves.</p>
     */
    public boolean hasCachedForecast(double lat, double lon) {
        final LocalDate startDay = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        final long start = startDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        final long end = startDay.plusDays(DAYS_AHEAD + 2).atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();
        final double roundedLat = round(lat);
        final double roundedLon = round(lon);

        for (String path : new String[]{"tide/sea-level/point", "tide/extremes/point"}) {
            final URI uri = uriFor(path, roundedLat, roundedLon, start, end);
            if (!cache.isFresh(cache.pathFor("tide", uri.toString(), ".json"), TTL)) return false;
        }
        return true;
    }

    @Override
    public TideData fetch(double lat, double lon, LocalDate startDay, int days)
            throws IOException, InterruptedException {

        if (!hasApiKey()) {
            throw new IOException("No Storm Glass API key. Enter one under File > Preferences.");
        }
        final double roundedLat = round(lat);
        final double roundedLon = round(lon);
        final long start = startDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        final long end = startDay.plusDays(days).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

        final Retrieved seaLevel =
                body(uriFor("tide/sea-level/point", roundedLat, roundedLon, start, end));
        final Retrieved extremes =
                body(uriFor("tide/extremes/point", roundedLat, roundedLon, start, end));

        final Map<String, Object> seaLevelRoot = read(seaLevel.text(), "sea level");
        final Map<String, Object> extremesRoot = read(extremes.text(), "extremes");

        // Whichever was actually fetched has the live count; a pair that both
        // came from the cache reports the count as at the older of the two.
        final boolean cached = seaLevel.cached() && extremes.cached();
        final Instant retrievedAt = seaLevel.at().isBefore(extremes.at())
                ? seaLevel.at() : extremes.at();

        return new TideData(
                stationOf(seaLevelRoot, extremesRoot),
                datumOf(seaLevelRoot),
                readingsOf(seaLevelRoot),
                extremesOf(extremesRoot),
                // The count from whichever half was actually fetched. Both
                // cached means neither count is current, which is what the
                // cached flag beside it is for.
                quotaOf(seaLevel.cached() ? extremesRoot : seaLevelRoot),
                retrievedAt,
                cached);
    }

    /** Rounded to {@link #COORDINATE_PLACES}, so the cache key stops moving. */
    static double round(double degrees) {
        final double scale = Math.pow(10, COORDINATE_PLACES);
        return Math.round(degrees * scale) / scale;
    }

    /** Builds one request. Package-private so the shape can be asserted in a test. */
    URI uriFor(String path, double lat, double lon, long start, long end) {
        return URI.create(base + path
                + "?lat=" + trim(lat)
                + "&lng=" + trim(lon)
                + "&start=" + start
                + "&end=" + end);
    }

    private static String trim(double d) {
        final String s = String.format(java.util.Locale.ROOT, "%." + COORDINATE_PLACES + "f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    // ---- fetching --------------------------------------------------------

    /** One response body, and whether it had to be asked for. */
    private record Retrieved(String text, Instant at, boolean cached) { }

    private Retrieved body(URI uri) throws IOException, InterruptedException {
        final Path entry = cache.pathFor("tide", uri.toString(), ".json");
        if (cache.isFresh(entry, TTL)) {
            LOG.fine(() -> "tide cache hit: " + entry);
            return new Retrieved(Files.readString(entry, StandardCharsets.UTF_8),
                                 Files.getLastModifiedTime(entry).toInstant(), true);
        }

        LOG.info(() -> "Requesting " + uri.getPath() + " from " + uri.getHost());
        final String text;
        try {
            text = Http.getStringAuthorized(uri, apiKey.get().trim());
        }
        catch (Http.HttpStatusException e) {
            throw explain(e);
        }

        Files.createDirectories(entry.getParent());
        Files.writeString(entry, text, StandardCharsets.UTF_8);
        return new Retrieved(text, Instant.now(), false);
    }

    /**
     * Turns a status code into something a user can act on.
     *
     * <p>Storm Glass answers a spent quota with <b>402</b> and a bad key with
     * <b>403</b>, neither of which reads as what it is. "Payment Required" in
     * particular sends people to a billing page when all that has happened is
     * that they looked at eleven harbours today.</p>
     */
    static IOException explain(Http.HttpStatusException e) {
        return switch (e.status()) {
            case 402 -> new IOException("Storm Glass daily request limit reached. "
                    + "It resets at midnight UTC; charts already cached still work.", e);
            case 403 -> new IOException("Storm Glass rejected the API key. "
                    + "Check it under File > Preferences.", e);
            case 422 -> new IOException("Storm Glass could not process the request: "
                    + e.body(), e);
            default -> e;
        };
    }

    // ---- reading the response --------------------------------------------

    private static Map<String, Object> read(String text, String what) throws IOException {
        try {
            final Map<String, Object> root = Json.object(Json.parse(text));
            if (root == null) throw new IOException("the " + what + " response is not an object");
            return root;
        }
        catch (Json.SyntaxException e) {
            throw new IOException("could not read the " + what + " response: " + e.getMessage(), e);
        }
    }

    private static TideData.Station stationOf(Map<String, Object> seaLevelRoot,
                                              Map<String, Object> extremesRoot) {
        Map<String, Object> station = Json.object(Json.object(seaLevelRoot, "meta"), "station");
        if (station == null) {
            station = Json.object(Json.object(extremesRoot, "meta"), "station");
        }
        if (station == null) return new TideData.Station(null, null, Double.NaN, Double.NaN, Double.NaN);
        return new TideData.Station(
                Json.text(station, "name"),
                Json.text(station, "source"),
                Json.number(station, "distance", Double.NaN),
                Json.number(station, "lat", Double.NaN),
                Json.number(station, "lng", Double.NaN));
    }

    private static String datumOf(Map<String, Object> root) {
        final String datum = Json.text(Json.object(root, "meta"), "datum");
        // The documented default, and what the response omits the field for.
        return datum == null ? "MSL" : datum;
    }

    private static TideData.Quota quotaOf(Map<String, Object> root) {
        final Map<String, Object> meta = Json.object(root, "meta");
        if (meta == null) return TideData.Quota.UNKNOWN;
        final Double count = Json.number(meta, "requestCount");
        final Double quota = Json.number(meta, "dailyQuota");
        if (count == null || quota == null) return TideData.Quota.UNKNOWN;
        return new TideData.Quota(count.intValue(), quota.intValue());
    }

    private static List<TideData.Reading> readingsOf(Map<String, Object> root) {
        final List<Object> data = Json.array(root, "data");
        if (data == null) return List.of();

        final List<TideData.Reading> out = new ArrayList<>();
        for (Object item : data) {
            final Map<String, Object> point = Json.object(item);
            if (point == null) continue;
            final Instant time = parseTime(Json.text(point, "time"));
            final Double metres = heightOf(point);
            if (time != null && metres != null) out.add(new TideData.Reading(time, metres));
        }
        out.sort(java.util.Comparator.comparing(TideData.Reading::time));
        return out;
    }

    /**
     * The sea level out of one point of the curve.
     *
     * <p>The field is named after whichever source supplied it rather than
     * being called "height", so it is looked up by elimination: the one numeric
     * field that is not the timestamp. {@code sg} is Storm Glass's own and is
     * what comes back in practice, so it is preferred where a response carries
     * more than one.</p>
     */
    private static Double heightOf(Map<String, Object> point) {
        final Double sg = Json.number(point, "sg");
        if (sg != null) return sg;
        for (Map.Entry<String, Object> field : point.entrySet()) {
            if (!"time".equals(field.getKey()) && field.getValue() instanceof Double d) return d;
        }
        return null;
    }

    private static List<TideData.Extreme> extremesOf(Map<String, Object> root) {
        final List<Object> data = Json.array(root, "data");
        if (data == null) return List.of();

        final List<TideData.Extreme> out = new ArrayList<>();
        for (Object item : data) {
            final Map<String, Object> point = Json.object(item);
            if (point == null) continue;
            final Instant time = parseTime(Json.text(point, "time"));
            final Double metres = Json.number(point, "height");
            final String type = Json.text(point, "type");
            if (time == null || metres == null || type == null) continue;
            out.add(new TideData.Extreme(time, metres,
                    "high".equalsIgnoreCase(type)
                            ? TideData.Extreme.Kind.HIGH
                            : TideData.Extreme.Kind.LOW));
        }
        out.sort(java.util.Comparator.comparing(TideData.Extreme::time));
        return out;
    }

    /**
     * Reads a Storm Glass timestamp.
     *
     * <p>The two endpoints do not agree: the extremes are documented as
     * {@code 2019-03-15 03:40:44+00:00} with a space and the sea level as
     * {@code 2020-02-24T00:00:00+00:00} with a T. Both are accepted rather than
     * one being assumed, because assuming the wrong one loses half the chart
     * and the difference is a single character.</p>
     *
     * @return the instant, or null if it cannot be read - one unreadable point
     *         should cost its own point and not the whole response
     */
    static Instant parseTime(String text) {
        if (text == null || text.isBlank()) return null;
        final String iso = text.trim().replaceFirst("^(\\d{4}-\\d{2}-\\d{2}) ", "$1T");
        try {
            return OffsetDateTime.parse(iso).toInstant();
        }
        catch (java.time.format.DateTimeParseException e) {
            LOG.warning("Ignoring an unreadable tide timestamp: " + text);
            return null;
        }
    }
}
