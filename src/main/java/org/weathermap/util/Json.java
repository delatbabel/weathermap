package org.weathermap.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, because the project carries no JSON dependency.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The same reasoning as {@link org.weathermap.grib.Grib2Scanner}: a
 * dependency has to earn its place, and reading a response of a shape we
 * already know does not need a general-purpose binding library. What it does
 * need is to be an actual parser. {@code InstagramClient.field} pulls a single
 * value out of a flat object with a regular expression, which is fine for
 * {@code {"id": "17..."}} and hopeless for the tide response, where the answer
 * is an array of objects and the station sits one level down inside
 * {@code meta}.</p>
 *
 * <p>Values come back as the obvious Java types - {@link Map} for an object
 * (insertion-ordered), {@link List} for an array, {@link String},
 * {@link Double} for any number, {@link Boolean}, and {@code null}. There is no
 * writer: nothing here sends JSON.</p>
 *
 * <h2>Reading a response is not trusting it</h2>
 *
 * <p>Input comes off the network, so the parser is strict about what it
 * accepts, refuses trailing content after the value, and bounds nesting depth -
 * a few thousand open brackets would otherwise end a recursive-descent parser
 * with a {@link StackOverflowError} rather than a message anyone can act on.</p>
 */
public final class Json {

    /** As deep as any response here nests, with room to spare. */
    private static final int MAX_DEPTH = 64;

    /** Malformed JSON. Unchecked, because a caller reading a known API treats it as a bug. */
    public static final class SyntaxException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SyntaxException(String message) {
            super(message);
        }
    }

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses one JSON value.
     *
     * @return a {@link Map}, {@link List}, {@link String}, {@link Double},
     *         {@link Boolean} or {@code null}
     * @throws SyntaxException if the text is not one complete JSON value
     */
    public static Object parse(String text) {
        if (text == null) throw new SyntaxException("no JSON to read");
        final Json json = new Json(text);
        json.skipWhitespace();
        final Object value = json.readValue(0);
        json.skipWhitespace();
        if (json.at < text.length()) {
            throw json.fail("trailing content after the value");
        }
        return value;
    }

    // ---- reading ---------------------------------------------------------

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) throw fail("nested more than " + MAX_DEPTH + " deep");
        final char c = peek();
        switch (c) {
            case '{': return readObject(depth);
            case '[': return readArray(depth);
            case '"': return readString();
            case 't': expect("true");  return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null");  return null;
            default:  return readNumber();
        }
    }

    private Map<String, Object> readObject(int depth) {
        // Insertion-ordered, so a value read back out and logged reads the way
        // the server wrote it.
        final Map<String, Object> out = new LinkedHashMap<>();
        at++;                                             // '{'
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw fail("expected a field name");
            final String name = readString();
            skipWhitespace();
            if (peek() != ':') throw fail("expected ':' after \"" + name + "\"");
            at++;
            skipWhitespace();
            out.put(name, readValue(depth + 1));
            skipWhitespace();
            final char c = peek();
            at++;
            if (c == '}') return out;
            if (c != ',') throw fail("expected ',' or '}' in an object");
        }
    }

    private List<Object> readArray(int depth) {
        final List<Object> out = new ArrayList<>();
        at++;                                             // '['
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(readValue(depth + 1));
            skipWhitespace();
            final char c = peek();
            at++;
            if (c == ']') return out;
            if (c != ',') throw fail("expected ',' or ']' in an array");
        }
    }

    private String readString() {
        at++;                                             // opening quote
        final StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw fail("unterminated string");
            final char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) throw fail("unterminated escape");
            final char escape = text.charAt(at++);
            switch (escape) {
                case '"'  -> out.append('"');
                case '\\' -> out.append('\\');
                case '/'  -> out.append('/');
                case 'b'  -> out.append('\b');
                case 'f'  -> out.append('\f');
                case 'n'  -> out.append('\n');
                case 'r'  -> out.append('\r');
                case 't'  -> out.append('\t');
                case 'u'  -> out.append(readUnicodeEscape());
                default   -> throw fail("unknown escape \\" + escape);
            }
        }
    }

    private char readUnicodeEscape() {
        if (at + 4 > text.length()) throw fail("truncated \\u escape");
        final String digits = text.substring(at, at + 4);
        try {
            final char c = (char) Integer.parseInt(digits, 16);
            at += 4;
            return c;
        }
        catch (NumberFormatException e) {
            throw fail("\\u" + digits + " is not four hex digits");
        }
    }

    /**
     * Every number is a double.
     *
     * <p>Tide heights, latitudes and distances are all measurements, and the
     * one integer in the response - the request count - is far inside what a
     * double represents exactly. Distinguishing integers from reals would buy
     * nothing and would make every caller ask which it had.</p>
     */
    private Double readNumber() {
        final int start = at;
        if (peek() == '-') at++;
        while (at < text.length() && isNumberChar(text.charAt(at))) at++;
        final String literal = text.substring(start, at);
        try {
            return Double.valueOf(literal);
        }
        catch (NumberFormatException e) {
            throw fail("\"" + literal + "\" is not a number");
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, at)) throw fail("expected " + literal);
        at += literal.length();
    }

    private char peek() {
        if (at >= text.length()) throw fail("the text ended mid-value");
        return text.charAt(at);
    }

    private void skipWhitespace() {
        while (at < text.length()) {
            final char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            at++;
        }
    }

    /** Says where, and shows the neighbourhood: a position alone is no help in a log. */
    private SyntaxException fail(String what) {
        final int from = Math.max(0, at - 20);
        final int to = Math.min(text.length(), at + 20);
        return new SyntaxException(what + " at offset " + at
                + " (near \"" + text.substring(from, to).replace('\n', ' ') + "\")");
    }

    // ---- getting at the result -------------------------------------------
    //
    // Typed rather than cast at every call site. A field that is absent and a
    // field of the wrong type are the same thing to a caller reading a known
    // API - both mean "the response was not what this code was written for" -
    // so both give null and one check covers them.

    /** @return {@code value} as an object, or null if it is not one */
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        @SuppressWarnings("unchecked")
        final Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    /** @return {@code value} as an array, or null if it is not one */
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) return null;
        @SuppressWarnings("unchecked")
        final List<Object> typed = (List<Object>) list;
        return typed;
    }

    /** @return the named field of {@code object} as an object, or null */
    public static Map<String, Object> object(Map<String, Object> object, String key) {
        return object == null ? null : object(object.get(key));
    }

    /** @return the named field of {@code object} as an array, or null */
    public static List<Object> array(Map<String, Object> object, String key) {
        return object == null ? null : array(object.get(key));
    }

    /** @return the named field as text, or null if absent or not a string */
    public static String text(Map<String, Object> object, String key) {
        return (object != null && object.get(key) instanceof String s) ? s : null;
    }

    /** @return the named field as a number, or null if absent or not one */
    public static Double number(Map<String, Object> object, String key) {
        return (object != null && object.get(key) instanceof Double d) ? d : null;
    }

    /** @return the named field as a number, or {@code fallback} if absent or not one */
    public static double number(Map<String, Object> object, String key, double fallback) {
        final Double value = number(object, key);
        return value == null ? fallback : value;
    }
}
