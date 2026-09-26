package org.weathermap.grib;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribVariable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A dependency-free GRIB2 reader covering what the NOMADS subsetting service
 * actually returns.
 *
 * <h2>The container</h2>
 *
 * <p>A GRIB2 file is a concatenation of messages, each built from numbered
 * sections:</p>
 *
 * <pre>
 *   Section 0  Indicator      16 bytes: "GRIB", discipline, edition, total length
 *   Section 1  Identification centre, reference time
 *   Section 2  Local use      optional
 *   Section 3  Grid           grid definition template - shape, size, corners
 *   Section 4  Product        product definition template - parameter, level, time
 *   Section 5  Representation packing template and its parameters
 *   Section 6  Bitmap         which points are present
 *   Section 7  Data           the packed values
 *   Section 8  End            "7777"
 * </pre>
 *
 * <p>Every section after 0 begins with a 4-byte length and a 1-byte number, so
 * the file can be walked without understanding any template.</p>
 *
 * <h2>What it decodes</h2>
 *
 * <p><b>Grid template 3.0</b> (regular latitude/longitude) packed with
 * <b>template 5.0</b> (simple packing), which is what every request through a
 * NOMADS {@code filter_*.pl} endpoint comes back as - verified against live GFS
 * 0.25&deg; responses for TMP, PRMSL and APCP. The filter re-encodes each
 * subset, so the JPEG2000 and complex packing used in the full published files
 * never reaches a client that goes through it.</p>
 *
 * <p>Anything else - a raw published file, a Lambert-conformal NAM or HRRR grid,
 * a complex-packed field - is refused by name, and the optional NetCDF-Java
 * decoder handles it. See {@link GribReaders} and README.md.</p>
 */
public final class Grib2Scanner implements GribReader {

    private static final Logger LOG = Logger.getLogger(Grib2Scanner.class.getName());

    private static final byte[] MAGIC = {'G', 'R', 'I', 'B'};
    private static final int INDICATOR_LENGTH = 16;

    /** Grid definition template: regular lat/lon. The only one decoded here. */
    private static final int GRID_LATLON = 0;

    /** Data representation template: simple packing. The only one decoded here. */
    private static final int PACKING_SIMPLE = 0;

    /** Section 6 indicator meaning "every point is present". */
    private static final int NO_BITMAP = 255;

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String description() {
        return "built-in GRIB2 reader (lat/lon grids, simple packing)";
    }

    @Override
    public List<Grid> read(Path gribFile) throws IOException {
        final byte[] bytes = Files.readAllBytes(gribFile);
        final List<Grid> grids = new ArrayList<>();
        for (Message m : walk(bytes, gribFile)) {
            grids.add(decode(bytes, m, gribFile));
        }
        return grids;
    }

    /**
     * Reports what each message in the file contains, without decoding values.
     *
     * <p>Useful on its own: it answers "did the subset request match anything,
     * and what?" - the question that matters when a filter returns a small file
     * because the variable was not published at that level.</p>
     */
    public List<MessageInfo> scan(Path gribFile) throws IOException {
        final byte[] bytes = Files.readAllBytes(gribFile);
        final List<MessageInfo> out = new ArrayList<>();
        for (Message m : walk(bytes, gribFile)) out.add(m.info());
        return out;
    }

    // -----------------------------------------------------------------
    // Walking
    // -----------------------------------------------------------------

    /** Where each section of one message starts, and the facts read from them. */
    private static final class Message {
        int offset;
        long length;
        int discipline;

        int section1 = -1;
        int section3 = -1;
        int section4 = -1;
        int section5 = -1;
        int section6 = -1;
        int section7 = -1;

        int gridTemplate = -1;
        int productTemplate = -1;
        int packingTemplate = -1;
        int pointCount;
        int valueCount;

        MessageInfo info() {
            final MessageInfo i = new MessageInfo();
            i.offset = offset;
            i.length = length;
            i.discipline = discipline;
            i.gridTemplate = gridTemplate;
            i.productTemplate = productTemplate;
            i.packingTemplate = packingTemplate;
            i.pointCount = pointCount;
            i.valueCount = valueCount;
            return i;
        }
    }

    private List<Message> walk(byte[] bytes, Path file) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        final List<Message> out = new ArrayList<>();
        int offset = 0;

        while (offset + INDICATOR_LENGTH <= bytes.length) {
            if (!startsWithMagic(bytes, offset)) {
                offset++;                       // some sources pad between messages
                continue;
            }
            final int edition = bytes[offset + 7] & 0xFF;
            if (edition != 2) {
                throw new IOException(file.getFileName() + ": GRIB edition " + edition
                        + " at offset " + offset + "; only edition 2 is supported");
            }
            final long totalLength = buf.getLong(offset + 8);
            if (totalLength <= 0 || offset + totalLength > bytes.length) {
                throw new IOException(file.getFileName() + ": message at offset " + offset
                        + " claims length " + totalLength + " but the file is " + bytes.length
                        + " bytes - a truncated download?");
            }

            final Message m = new Message();
            m.offset = offset;
            m.length = totalLength;
            m.discipline = bytes[offset + 6] & 0xFF;

            int p = offset + INDICATOR_LENGTH;
            final long end = offset + totalLength;
            while (p < end - 4) {
                if (bytes[p] == '7' && bytes[p + 1] == '7'
                        && bytes[p + 2] == '7' && bytes[p + 3] == '7') {
                    break;                      // section 8
                }
                final int sectionLength = buf.getInt(p);
                if (sectionLength <= 0) break;
                final int number = bytes[p + 4] & 0xFF;
                switch (number) {
                    case 1 -> m.section1 = p;
                    case 3 -> {
                        m.section3 = p;
                        // Octets 7-10 are the point count; octets 13-14 the template.
                        m.pointCount = buf.getInt(p + 6);
                        m.gridTemplate = buf.getShort(p + 12) & 0xFFFF;
                    }
                    case 4 -> {
                        m.section4 = p;
                        m.productTemplate = buf.getShort(p + 7) & 0xFFFF;
                    }
                    case 5 -> {
                        m.section5 = p;
                        m.valueCount = buf.getInt(p + 5);
                        m.packingTemplate = buf.getShort(p + 9) & 0xFFFF;
                    }
                    case 6 -> m.section6 = p;
                    case 7 -> m.section7 = p;
                    default -> { }
                }
                p += sectionLength;
            }
            out.add(m);
            LOG.fine(() -> "GRIB2 message at " + m.offset + ": " + m.info());
            offset = (int) (offset + totalLength);
        }

        if (out.isEmpty()) {
            throw new IOException(file.getFileName()
                    + " contains no GRIB2 messages - an empty or HTML response?");
        }
        return out;
    }

    private static boolean startsWithMagic(byte[] bytes, int offset) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[offset + i] != MAGIC[i]) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------
    // Decoding
    // -----------------------------------------------------------------

    private Grid decode(byte[] bytes, Message m, Path file) throws IOException {
        if (m.gridTemplate != GRID_LATLON) {
            throw new IOException(file.getFileName() + ": grid definition template 3."
                    + m.gridTemplate + " is not a regular lat/lon grid. "
                    + "Build with -Pnetcdf to decode it.");
        }
        if (m.packingTemplate != PACKING_SIMPLE) {
            throw new IOException(file.getFileName() + ": " + packingName(m.packingTemplate)
                    + " is not decoded by the built-in reader. Build with -Pnetcdf, "
                    + "or fetch through a NOMADS filter endpoint, which re-packs as 5.0.");
        }
        if (m.section3 < 0 || m.section5 < 0 || m.section7 < 0) {
            throw new IOException(file.getFileName() + ": message at " + m.offset
                    + " is missing a required section");
        }

        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        final LatLonGrid grid = readLatLonGrid(buf, m.section3);
        final SimplePacking packing = readSimplePacking(buf, m.section5);
        final boolean[] bitmap = readBitmap(bytes, m.section6, grid.width * grid.height);

        final float[] values = unpack(bytes, m.section7, packing, bitmap,
                                      grid.width * grid.height);
        final float[] oriented = grid.northFirst ? values : flipRows(values, grid.width, grid.height);

        return new Grid(variableOf(bytes, m), levelOf(buf, m),
                        validTime(buf, m), grid.bounds(),
                        grid.width, grid.height, oriented);
    }

    // ---- section 3: grid definition template 3.0 ------------------------

    private record LatLonGrid(int width, int height, double la1, double lo1,
                              double la2, double lo2, boolean northFirst) {

        BoundingBox bounds() {
            final double south = Math.min(la1, la2);
            final double north = Math.max(la1, la2);
            // GRIB2 longitudes run 0..360 and the rest of the application uses
            // -180..180, so both are folded back into that range.
            //
            // First and last, not smallest and largest: lo1 is the west edge
            // and lo2 the east one, and taking min and max of the pair turns a
            // grid that runs through 180 - a Pacific subregion, or a whole
            // global field, whose lo1 is 0 and lo2 359.75 - inside out, into
            // the narrow slice on the other side of the seam.
            return BoundingBox.of(south, BoundingBox.normaliseLon(lo1),
                                  north, BoundingBox.normaliseEastLon(lo2));
        }
    }

    private static LatLonGrid readLatLonGrid(ByteBuffer buf, int p) {
        final int ni = buf.getInt(p + 30);               // octets 31-34
        final int nj = buf.getInt(p + 34);               // octets 35-38
        final double la1 = signedMicroDegrees(buf, p + 46);
        final double lo1 = signedMicroDegrees(buf, p + 50);
        final double la2 = signedMicroDegrees(buf, p + 55);
        final double lo2 = signedMicroDegrees(buf, p + 59);
        final int scanningMode = buf.get(p + 71) & 0xFF; // octet 72

        // Bit 2 (0x40) set means rows run south to north. GFS leaves it clear,
        // so the first row is the northernmost - which is what Grid wants.
        final boolean northFirst = (scanningMode & 0x40) == 0;
        return new LatLonGrid(ni, nj, la1, lo1, la2, lo2, northFirst);
    }

    /**
     * Reads a 4-byte GRIB2 angle in units of 1e-6 degrees.
     *
     * <p>GRIB2 stores these <b>sign-magnitude</b>, not two's complement: the top
     * bit is the sign and the remaining 31 bits the magnitude. Reading one as a
     * signed int turns a southern latitude into a number near -2 billion.</p>
     */
    private static double signedMicroDegrees(ByteBuffer buf, int at) {
        final int raw = buf.getInt(at);
        final int magnitude = raw & 0x7FFFFFFF;
        return ((raw & 0x80000000) != 0 ? -magnitude : magnitude) * 1e-6;
    }

    // ---- section 5: data representation template 5.0 --------------------

    /** value = (R + X * 2^E) / 10^D, over {@code bits}-wide fields. */
    private record SimplePacking(float referenceValue, int binaryScale,
                                 int decimalScale, int bits) { }

    private static SimplePacking readSimplePacking(ByteBuffer buf, int p) {
        return new SimplePacking(
                buf.getFloat(p + 11),                     // octets 12-15, IEEE 754
                signedShort(buf, p + 15),                 // octets 16-17
                signedShort(buf, p + 17),                 // octets 18-19
                buf.get(p + 19) & 0xFF);                  // octet 20
    }

    /** GRIB2 2-byte signed values are sign-magnitude, like the angles. */
    private static int signedShort(ByteBuffer buf, int at) {
        final int raw = buf.getShort(at) & 0xFFFF;
        final int magnitude = raw & 0x7FFF;
        return ((raw & 0x8000) != 0) ? -magnitude : magnitude;
    }

    // ---- section 6: bitmap ----------------------------------------------

    /** @return which points are present, or {@code null} when all of them are */
    private static boolean[] readBitmap(byte[] bytes, int p, int pointCount) {
        if (p < 0) return null;
        final int indicator = bytes[p + 5] & 0xFF;        // octet 6
        if (indicator == NO_BITMAP) return null;

        final boolean[] present = new boolean[pointCount];
        final int start = p + 6;
        for (int i = 0; i < pointCount; i++) {
            final int bit = 7 - (i % 8);
            present[i] = ((bytes[start + i / 8] >> bit) & 1) != 0;
        }
        return present;
    }

    // ---- section 7: the values ------------------------------------------

    /**
     * Unpacks the data section.
     *
     * <p>Values are {@code bits}-wide big-endian fields packed end to end with no
     * padding between them, so a field can straddle byte boundaries - which is
     * why this reads bit by bit rather than by byte. A width of 0 is legal and
     * means every point holds the reference value.</p>
     */
    private static float[] unpack(byte[] bytes, int p, SimplePacking packing,
                                  boolean[] bitmap, int pointCount) {
        final float[] out = new float[pointCount];
        final double scale = Math.pow(2, packing.binaryScale()) / Math.pow(10, packing.decimalScale());
        final double reference = packing.referenceValue() / Math.pow(10, packing.decimalScale());

        final int start = p + 5;                          // octets 6+
        long bitPosition = 0;

        for (int i = 0; i < pointCount; i++) {
            if (bitmap != null && !bitmap[i]) {
                out[i] = Grid.MISSING;
                continue;
            }
            if (packing.bits() == 0) {
                out[i] = (float) reference;
                continue;
            }
            long x = 0;
            for (int b = 0; b < packing.bits(); b++) {
                final long absolute = bitPosition + b;
                final int index = start + (int) (absolute >> 3);
                final int bit = 7 - (int) (absolute & 7);
                x = (x << 1) | ((bytes[index] >> bit) & 1);
            }
            bitPosition += packing.bits();
            out[i] = (float) (reference + x * scale);
        }
        return out;
    }

    /** Turns a south-first grid into the north-first order {@link Grid} stores. */
    private static float[] flipRows(float[] values, int width, int height) {
        final float[] out = new float[values.length];
        for (int y = 0; y < height; y++) {
            System.arraycopy(values, (height - 1 - y) * width, out, y * width, width);
        }
        return out;
    }

    // ---- metadata --------------------------------------------------------

    /**
     * Identifies the field from its discipline, category and parameter number.
     *
     * <p>A tiny table rather than the full WMO parameter database - the
     * catalogued variables are the ones this application offers, and anything
     * else gets a synthetic entry named after its numbers so it still renders
     * and still labels. A complete table is one of the things NetCDF-Java
     * brings.</p>
     */
    private static GribVariable variableOf(byte[] bytes, Message m) {
        if (m.section4 < 0) return GribCatalog.variable("UNKNOWN");
        final int discipline = m.discipline;
        final int category = bytes[m.section4 + 9] & 0xFF;   // octet 10
        final int number = bytes[m.section4 + 10] & 0xFF;    // octet 11

        final String code = switch (discipline * 10000 + category * 100 + number) {
            case 0 * 10000 + 0 * 100 + 0 -> "TMP";
            case 0 * 10000 + 0 * 100 + 6 -> "DPT";
            case 0 * 10000 + 1 * 100 + 1 -> "RH";
            case 0 * 10000 + 1 * 100 + 8 -> "APCP";
            case 0 * 10000 + 2 * 100 + 2 -> "UGRD";
            case 0 * 10000 + 2 * 100 + 3 -> "VGRD";
            case 0 * 10000 + 3 * 100 + 1 -> "PRMSL";
            case 0 * 10000 + 3 * 100 + 5 -> "HGT";
            case 0 * 10000 + 6 * 100 + 1 -> "TCDC";
            default -> "D" + discipline + "C" + category + "N" + number;
        };
        return GribCatalog.variable(code);
    }

    /**
     * Names the vertical level from the first fixed surface.
     *
     * <p>Only the surfaces the catalogue offers are named; anything else is
     * labelled by its type and value so the map is still captioned truthfully.</p>
     */
    private static GribLevel levelOf(ByteBuffer buf, Message m) {
        if (m.section4 < 0 || m.productTemplate < 0) return GribCatalog.level("unknown");
        final int type = buf.get(m.section4 + 22) & 0xFF;        // octet 23
        final int scaleFactor = buf.get(m.section4 + 23);        // octet 24
        final int scaledValue = buf.getInt(m.section4 + 24);     // octets 25-28
        final double value = scaledValue * Math.pow(10, -scaleFactor);

        return switch (type) {
            case 1 -> GribCatalog.LEVEL_SURFACE;
            case 101 -> GribCatalog.LEVEL_MSL;
            case 100 -> GribCatalog.level(Math.round(value / 100) + "_mb");
            case 103 -> GribCatalog.level(Math.round(value) + "_m_above_ground");
            case 10 -> GribCatalog.LEVEL_ENTIRE_ATMOSPHERE;
            default -> new GribLevel("type_" + type, "level type " + type + " @ " + value);
        };
    }

    /**
     * The time this forecast is <em>for</em>: the run's reference time from
     * section 1 plus the forecast offset from section 4.
     *
     * <p><b>Correct for product template 4.0, early for 4.8.</b> A statistically
     * processed field - accumulated precipitation, say - stores the <em>start</em>
     * of its accumulation period in the forecast-time field and the end of the
     * interval further into the template. So an APCP message for f006 reports
     * 00Z rather than 06Z. Reading octets 35-41 of template 4.8 for the end of
     * the overall interval is the fix; until then such a field is labelled with
     * the start of its period.</p>
     */
    private static Instant validTime(ByteBuffer buf, Message m) {
        if (m.section1 < 0) return Instant.EPOCH;
        final int year = buf.getShort(m.section1 + 12) & 0xFFFF;  // octets 13-14
        final int month = buf.get(m.section1 + 14) & 0xFF;
        final int day = buf.get(m.section1 + 15) & 0xFF;
        final int hour = buf.get(m.section1 + 16) & 0xFF;
        final int minute = buf.get(m.section1 + 17) & 0xFF;
        final int second = buf.get(m.section1 + 18) & 0xFF;

        Instant reference;
        try {
            reference = LocalDateTime.of(year, Math.max(1, month), Math.max(1, day),
                                         hour, minute, second).toInstant(ZoneOffset.UTC);
        }
        catch (RuntimeException e) {
            return Instant.EPOCH;
        }
        if (m.section4 < 0) return reference;

        final int unit = buf.get(m.section4 + 17) & 0xFF;         // octet 18
        final int offset = buf.getInt(m.section4 + 18);           // octets 19-22
        return reference.plusSeconds((long) offset * unitSeconds(unit));
    }

    /** Code table 4.4, for the units a forecast offset can be expressed in. */
    private static long unitSeconds(int unit) {
        return switch (unit) {
            case 0 -> 60;            // minute
            case 1 -> 3600;          // hour
            case 2 -> 86400;         // day
            case 10 -> 3 * 3600;     // 3 hours
            case 11 -> 6 * 3600;     // 6 hours
            case 12 -> 12 * 3600;    // 12 hours
            case 13 -> 1;            // second
            default -> 3600;
        };
    }

    private static String packingName(int template) {
        return switch (template) {
            case 0 -> "template 5.0 simple packing";
            case 2 -> "template 5.2 complex packing";
            case 3 -> "template 5.3 complex packing with spatial differencing";
            case 40, 40000 -> "template 5.40 JPEG2000";
            case 41 -> "template 5.41 PNG";
            default -> "template 5." + template;
        };
    }

    /** What one message declares about itself. */
    public static final class MessageInfo {
        public int offset;
        public long length;
        public int discipline;
        public int gridTemplate = -1;
        public int productTemplate = -1;
        public int packingTemplate = -1;
        public int pointCount;
        public int valueCount;

        @Override
        public String toString() {
            return "grid 3." + gridTemplate + ", product 4." + productTemplate
                    + ", " + packingName(packingTemplate)
                    + ", " + pointCount + " points, " + length + " bytes";
        }
    }
}
