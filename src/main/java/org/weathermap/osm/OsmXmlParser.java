package org.weathermap.osm;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Turns an Overpass XML response into flat {@link Feature}s.
 *
 * <p>Streamed with StAX rather than parsed into a DOM: a coastline query for a
 * country is tens of megabytes of XML, nearly all of it {@code <nd>} references,
 * and holding that as a document tree is wasteful when one pass suffices.</p>
 *
 * <p>Two passes over one file would be simpler but the file is not seekable
 * here, so nodes are collected into a map as they arrive and ways are resolved
 * against it afterwards. Overpass emits {@code out body; >; out skel qt;} with
 * the matched elements first and the referenced nodes after, so resolution
 * cannot happen inline.</p>
 */
public final class OsmXmlParser {

    private static final Logger LOG = Logger.getLogger(OsmXmlParser.class.getName());

    /** id -> {lat, lon} for every node seen, including geometry-only ones. */
    private final Map<Long, double[]> nodes = new HashMap<>();

    /** Ways in document order, as node-id lists plus their tags. */
    private final List<RawWay> ways = new ArrayList<>();

    /** Nodes that were themselves matched (places), rather than pure geometry. */
    private final List<RawNode> taggedNodes = new ArrayList<>();

    private record RawWay(List<Long> nodeIds, Map<String, String> tags) { }

    private record RawNode(long id, Map<String, String> tags) { }

    public List<Feature> parse(InputStream in) throws IOException {
        final XMLInputFactory factory = XMLInputFactory.newInstance();
        // Untrusted input from a public endpoint: no external entities, no DTDs.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try {
            final XMLStreamReader reader = factory.createXMLStreamReader(in);
            readElements(reader);
            reader.close();
        }
        catch (XMLStreamException e) {
            throw new IOException("Malformed OSM XML", e);
        }
        return buildFeatures();
    }

    private void readElements(XMLStreamReader reader) throws XMLStreamException {
        List<Long> currentWayNodes = null;
        Map<String, String> currentTags = null;
        long currentNodeId = 0;
        boolean inWay = false;
        boolean inNode = false;

        while (reader.hasNext()) {
            final int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "node" -> {
                        currentNodeId = Long.parseLong(reader.getAttributeValue(null, "id"));
                        final String lat = reader.getAttributeValue(null, "lat");
                        final String lon = reader.getAttributeValue(null, "lon");
                        if (lat != null && lon != null) {
                            nodes.put(currentNodeId,
                                      new double[]{Double.parseDouble(lat), Double.parseDouble(lon)});
                        }
                        currentTags = new LinkedHashMap<>();
                        inNode = true;
                    }
                    case "way" -> {
                        currentWayNodes = new ArrayList<>();
                        currentTags = new LinkedHashMap<>();
                        inWay = true;
                    }
                    case "nd" -> {
                        if (currentWayNodes != null) {
                            currentWayNodes.add(Long.parseLong(reader.getAttributeValue(null, "ref")));
                        }
                    }
                    case "tag" -> {
                        if (currentTags != null) {
                            currentTags.put(reader.getAttributeValue(null, "k"),
                                            reader.getAttributeValue(null, "v"));
                        }
                    }
                    // TODO: <relation> is ignored. Multipolygon boundaries and
                    // islands whose outline is a relation therefore render as
                    // their individual member ways, which is usually right for
                    // strokes and wrong for fills. See README.md.
                    default -> { }
                }
            }
            else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "way" -> {
                        if (inWay && currentWayNodes != null && currentTags != null) {
                            ways.add(new RawWay(currentWayNodes, currentTags));
                        }
                        inWay = false;
                        currentWayNodes = null;
                        currentTags = null;
                    }
                    case "node" -> {
                        if (inNode && currentTags != null && !currentTags.isEmpty()) {
                            taggedNodes.add(new RawNode(currentNodeId, currentTags));
                        }
                        inNode = false;
                        currentTags = null;
                    }
                    default -> { }
                }
            }
        }
    }

    private List<Feature> buildFeatures() {
        final List<Feature> out = new ArrayList<>();
        int unresolved = 0;

        for (RawWay way : ways) {
            final FeatureKind kind = classifyWay(way.tags());
            if (kind == null) continue;
            final List<double[]> points = new ArrayList<>(way.nodeIds().size());
            for (long id : way.nodeIds()) {
                final double[] p = nodes.get(id);
                if (p != null) points.add(p);
                else unresolved++;
            }
            if (points.size() >= 2) out.add(new Feature(kind, points, way.tags()));
        }

        for (RawNode node : taggedNodes) {
            if (node.tags().get("place") == null) continue;
            final double[] p = nodes.get(node.id());
            if (p != null) out.add(new Feature(FeatureKind.PLACE, List.of(p), node.tags()));
        }

        if (unresolved > 0) {
            // Normal at the edges of a bbox query: a way can reference nodes
            // outside the box, which Overpass does not return.
            final int count = unresolved;
            LOG.fine(() -> count + " way node(s) fell outside the query area");
        }
        return out;
    }

    private static FeatureKind classifyWay(Map<String, String> tags) {
        if ("coastline".equals(tags.get("natural"))) return FeatureKind.COASTLINE;
        if ("administrative".equals(tags.get("boundary"))) return FeatureKind.BOUNDARY;
        return null;
    }
}
