#!/usr/bin/env python3
"""Build the bundled gazetteer from Natural Earth.

The world outline that ships with the application (see make-world-basemap.py)
has shapes but no names, so a chart of an area too large for Overpass came out
with no labels on it whatsoever -- readable only by someone who already knew
the coastline.  This adds the names.

Run once, when the gazetteer needs regenerating:

    tools/make-gazetteer.py /path/to/natural_earth \
        src/main/resources/basemap/world-gazetteer.bin

expecting these unpacked alongside each other in that directory:

    ne_50m_populated_places_simple/   cities, with scalerank and population
    ne_50m_geography_marine_polys/    seas, gulfs, bays, straits, oceans
    ne_110m_admin_0_countries/        country names, with their label points

Three sources at two resolutions, which is deliberate.  The 1:110m marine set
has 29 features and stops at "South China Sea"; the 1:50m set has 118 and
includes the Gulf of Thailand, the Andaman Sea and the Gulf of Tonkin -- the
scale at which a chart of South-East Asia is actually read.  Cities likewise:
1:110m has one per country and omits Ho Chi Minh City.  Countries stay at
1:110m because all that is taken from them is a name and a label point.

Marine features are polygons, so a point has to be derived for the name to sit
on.  The area centroid is used where it falls inside the shape and the midpoint
of the widest horizontal chord where it does not -- a crescent like the Gulf of
Thailand has a centroid on land.

Natural Earth is public domain (https://www.naturalearthdata.com/about/terms-of-use/).

Output format:

    magic    "WMGZ"                4 bytes
    version  1                     int
    count    number of entries     int
    then, per entry:
        kind   0 city, 1 marine, 2 country   byte
        rank   0 = most important             byte
        lat, lon                              float, float   big-endian
        len    UTF-8 name length              short
        name   UTF-8 bytes
"""
import os
import struct
import sys

CITY, MARINE, COUNTRY = 0, 1, 2

# Cities below this rank are never bundled.  Rank 4 is about 1,100 places
# worldwide, which is more than any one chart will draw - the renderer cuts
# further by the span it is drawing - and still only tens of kilobytes.
CITY_RANK_LIMIT = 4


# ---- shapefile and dbf, enough of each ---------------------------------

def read_dbf(path):
    """Field name to value, per record.  Natural Earth writes UTF-8 (.cpg)."""
    with open(path, 'rb') as f:
        data = f.read()
    count, header_len, record_len = struct.unpack_from('<IHH', data, 4)

    fields, off = [], 32
    while data[off] != 0x0D:
        name = data[off:off + 11].split(b'\0')[0].decode('ascii')
        fields.append((name, data[off + 16]))
        off += 32

    out = []
    for i in range(count):
        record = data[header_len + i * record_len: header_len + (i + 1) * record_len]
        at, row = 1, {}                      # byte 0 is the deletion flag
        for name, length in fields:
            row[name] = record[at:at + length].decode('utf-8', 'replace').strip('\0 ').strip()
            at += length
        out.append(row)
    return out


def read_polygons(path):
    """Yields, per record, its list of rings; each ring a list of (lat, lon)."""
    with open(path, 'rb') as f:
        data = f.read()

    offset, total = 100, len(data)
    while offset < total:
        _, content_words = struct.unpack_from('>ii', data, offset)
        record = offset + 8
        offset = record + content_words * 2

        if struct.unpack_from('<i', data, record)[0] == 0:       # null shape
            yield []
            continue

        num_parts, num_points = struct.unpack_from('<ii', data, record + 36)
        parts_at = record + 44
        points_at = parts_at + num_parts * 4
        starts = list(struct.unpack_from(f'<{num_parts}i', data, parts_at)) + [num_points]

        rings = []
        for i in range(num_parts):
            first, last = starts[i], starts[i + 1]
            if last - first < 3:
                continue
            xy = struct.unpack_from(f'<{(last - first) * 2}d', data, points_at + first * 16)
            rings.append([(xy[j + 1], xy[j]) for j in range(0, len(xy), 2)])
        yield rings


# ---- putting a name inside a shape -------------------------------------

def ring_area(ring):
    """Twice the signed area, in square degrees.  Sign gives the winding."""
    total = 0.0
    for i in range(len(ring)):
        lat0, lon0 = ring[i]
        lat1, lon1 = ring[(i + 1) % len(ring)]
        total += lon0 * lat1 - lon1 * lat0
    return total


def centroid(ring):
    area = ring_area(ring)
    if abs(area) < 1e-12:
        return sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring)
    lat = lon = 0.0
    for i in range(len(ring)):
        lat0, lon0 = ring[i]
        lat1, lon1 = ring[(i + 1) % len(ring)]
        cross = lon0 * lat1 - lon1 * lat0
        lat += (lat0 + lat1) * cross
        lon += (lon0 + lon1) * cross
    return lat / (3 * area), lon / (3 * area)


def contains(ring, lat, lon):
    inside = False
    for i in range(len(ring)):
        lat0, lon0 = ring[i]
        lat1, lon1 = ring[(i - 1) % len(ring)]
        if (lat0 > lat) != (lat1 > lat):
            if lon < (lon1 - lon0) * (lat - lat0) / (lat1 - lat0) + lon0:
                inside = not inside
    return inside


def widest_chord_midpoint(ring, lat):
    """Midpoint of the longest span of the ring's interior along one parallel."""
    crossings = []
    for i in range(len(ring)):
        lat0, lon0 = ring[i]
        lat1, lon1 = ring[(i - 1) % len(ring)]
        if (lat0 > lat) != (lat1 > lat):
            crossings.append((lon1 - lon0) * (lat - lat0) / (lat1 - lat0) + lon0)
    crossings.sort()
    best, best_width = None, -1.0
    for i in range(0, len(crossings) - 1, 2):
        width = crossings[i + 1] - crossings[i]
        if width > best_width:
            best, best_width = (crossings[i] + crossings[i + 1]) / 2, width
    return best


def label_point(rings):
    """A point inside the largest ring, for the name to sit on."""
    if not rings:
        return None
    ring = max(rings, key=lambda r: abs(ring_area(r)))
    lat, lon = centroid(ring)
    if contains(ring, lat, lon):
        return lat, lon
    # A crescent - the Gulf of Thailand's centroid is inland.  Slide along the
    # centroid's parallel to the middle of the widest stretch of water on it.
    moved = widest_chord_midpoint(ring, lat)
    if moved is not None:
        return lat, moved
    return lat, lon


# ---- the three sources --------------------------------------------------

def cities(root):
    name = 'ne_50m_populated_places_simple'
    for row in read_dbf(os.path.join(root, name, name + '.dbf')):
        rank = int(row['scalerank'])
        if rank > CITY_RANK_LIMIT:
            continue
        label = row['name'].strip()
        if label:
            yield CITY, rank, float(row['latitude']), float(row['longitude']), label


def marine(root):
    name = 'ne_50m_geography_marine_polys'
    rows = read_dbf(os.path.join(root, name, name + '.dbf'))
    shapes = read_polygons(os.path.join(root, name, name + '.shp'))
    for row, rings in zip(rows, shapes):
        point = label_point(rings)
        label = row['name'].strip()
        if point and label:
            yield MARINE, int(row['scalerank']), point[0], point[1], label


def countries(root):
    name = 'ne_110m_admin_0_countries'
    for row in read_dbf(os.path.join(root, name, name + '.dbf')):
        label = (row['NAME'] or row['ADMIN']).strip()
        if not label or not row['LABEL_X'] or not row['LABEL_Y']:
            continue
        yield COUNTRY, int(row['LABELRANK']), float(row['LABEL_Y']), float(row['LABEL_X']), label


def main(argv):
    if len(argv) != 3:
        raise SystemExit(__doc__)
    root, out_path = argv[1], argv[2]

    entries = list(cities(root)) + list(marine(root)) + list(countries(root))
    entries.sort(key=lambda e: (e[0], e[1]))

    with open(out_path, 'wb') as out:
        out.write(b'WMGZ')
        out.write(struct.pack('>ii', 1, len(entries)))
        for kind, rank, lat, lon, label in entries:
            encoded = label.encode('utf-8')
            out.write(struct.pack('>BBffh', kind, min(rank, 255), lat, lon, len(encoded)))
            out.write(encoded)

    counts = {CITY: 0, MARINE: 0, COUNTRY: 0}
    for kind, *_ in entries:
        counts[kind] += 1
    print(f'{out_path}: {len(entries)} entries '
          f'({counts[CITY]} cities, {counts[MARINE]} marine, {counts[COUNTRY]} countries), '
          f'{os.path.getsize(out_path)} bytes')


if __name__ == '__main__':
    main(sys.argv)
