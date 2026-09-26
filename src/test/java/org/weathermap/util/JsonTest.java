package org.weathermap.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void readsTheShapeATideResponseComesIn() {
        final Object root = Json.parse("""
                {
                  "data": [
                    {"sg": -0.62, "time": "2020-02-24T00:00:00+00:00"},
                    {"sg": 0.16,  "time": "2020-02-24T01:00:00+00:00"}
                  ],
                  "meta": {
                    "cost": 1, "dailyQuota": 10, "requestCount": 4,
                    "station": {"distance": 4, "name": "bilbao", "source": "sg"}
                  }
                }
                """);

        final Map<String, Object> object = Json.object(root);
        final List<Object> data = Json.array(object, "data");
        assertEquals(2, data.size());
        assertEquals(-0.62, Json.number(Json.object(data.get(0)), "sg"), 1e-9);

        final Map<String, Object> station = Json.object(Json.object(object, "meta"), "station");
        assertEquals("bilbao", Json.text(station, "name"));
        assertEquals(4.0, Json.number(station, "distance"), 1e-9);
    }

    @Test
    void keepsTheOrderTheFieldsArrivedIn() {
        final Map<String, Object> object = Json.object(Json.parse("{\"b\":1,\"a\":2,\"c\":3}"));
        assertEquals(List.of("b", "a", "c"), List.copyOf(object.keySet()));
    }

    @Test
    void readsTheScalarsAndTheEmptyContainers() {
        assertEquals("", Json.parse("\"\""));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse(" false "));
        assertNull(Json.parse("null"));
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals(Map.of(), Json.parse("{}"));
    }

    @Test
    void readsNumbersInEveryFormJsonAllows() {
        assertEquals(0.0, (Double) Json.parse("0"), 1e-12);
        assertEquals(-1.5, (Double) Json.parse("-1.5"), 1e-12);
        assertEquals(1200.0, (Double) Json.parse("1.2e3"), 1e-12);
        assertEquals(0.0012, (Double) Json.parse("1.2E-3"), 1e-12);
    }

    @Test
    void readsEscapesIncludingTheStationNamesThatNeedThem() {
        // Storm Glass names its Norwegian stations "måløy" and "kristiansund",
        // which arrive escaped from some encoders and raw from others.
        assertEquals("måløy", Json.parse("\"m\\u00e5l\\u00f8y\""));
        assertEquals("måløy", Json.parse("\"måløy\""));
        assertEquals("a\"b\\c/d\te", Json.parse("\"a\\\"b\\\\c\\/d\\te\""));
    }

    // ---- what it refuses --------------------------------------------------

    @Test
    void refusesTrailingContent() {
        // An HTML error page appended to a body, or two documents concatenated,
        // should not read as the first value and a shrug.
        final Json.SyntaxException e = assertThrows(Json.SyntaxException.class,
                () -> Json.parse("{\"a\":1} <html>"));
        assertTrue(e.getMessage().contains("trailing content"), e.getMessage());
    }

    @Test
    void refusesTruncatedAndMalformedInput() {
        assertThrows(Json.SyntaxException.class, () -> Json.parse("{\"a\":"));
        assertThrows(Json.SyntaxException.class, () -> Json.parse("[1,2"));
        assertThrows(Json.SyntaxException.class, () -> Json.parse("{a:1}"));
        assertThrows(Json.SyntaxException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.SyntaxException.class, () -> Json.parse(""));
        assertThrows(Json.SyntaxException.class, () -> Json.parse(null));
    }

    /**
     * A recursive-descent parser fed a few thousand open brackets ends in a
     * StackOverflowError, which is not an error a caller can catch and report.
     */
    @Test
    void refusesInputNestedAbsurdlyDeep() {
        final String deep = "[".repeat(5000) + "]".repeat(5000);
        final Json.SyntaxException e =
                assertThrows(Json.SyntaxException.class, () -> Json.parse(deep));
        assertTrue(e.getMessage().contains("deep"), e.getMessage());
    }

    @Test
    void saysWhereTheProblemIs() {
        final Json.SyntaxException e = assertThrows(Json.SyntaxException.class,
                () -> Json.parse("{\"a\": 1, \"b\": }"));
        assertTrue(e.getMessage().contains("offset"), e.getMessage());
    }

    // ---- getting at the result --------------------------------------------

    @Test
    void anAbsentFieldAndAWrongTypedOneBothReadAsNothing() {
        final Map<String, Object> object = Json.object(Json.parse("{\"n\": 1, \"s\": \"x\"}"));
        assertNull(Json.text(object, "n"), "a number is not text");
        assertNull(Json.number(object, "s"), "text is not a number");
        assertNull(Json.text(object, "absent"));
        assertNull(Json.object(object, "n"));
        assertNull(Json.array(object, "n"));
        assertEquals(7.0, Json.number(object, "absent", 7.0), 1e-9);
    }
}
