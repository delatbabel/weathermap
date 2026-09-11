#!/usr/bin/env python3
"""Convert Natural Earth 1:110m shapefiles into the compact form the
application bundles.

Run once, when the world outline needs regenerating:

    tools/make-world-basemap.py /path/to/ne_110m_coastline.shp \
        src/main/resources/basemap/world-coastline.bin

Why a private format rather than reading the shapefile directly: a shapefile
needs a parser, an index file and a .dbf alongside it, and none of that earns
its place when all the application wants is "a list of polylines".  The output
is:

    magic   "WMBM"          4 bytes
    version 1               int
    count   number of lines int
    then, per line:
        n   number of points  int
        n x (float lat, float lon)   big-endian IEEE 754

Floats rather than doubles: at 1:110m the data is good to a few hundred
metres, so seven significant digits is far more than the source carries, and
it halves the file.

Natural Earth is public domain (https://www.naturalearthdata.com/about/terms-of-use/).
"""
import struct
import sys


def read_shapefile_polylines(path):
    """Yields each part of every PolyLine record as a list of (lat, lon)."""
    with open(path, 'rb') as f:
        data = f.read()

    # Main file header is 100 bytes; shape type at offset 32 (little-endian).
    shape_type = struct.unpack_from('<i', data, 32)[0]
    if shape_type not in (3, 13, 23):          # PolyLine, PolyLineZ, PolyLineM
        raise SystemExit(f'{path}: shape type {shape_type} is not a PolyLine')

    offset = 100
    total = len(data)
    while offset < total:
        # Record header: number and content length, both big-endian, in
        # 16-bit words.
        _, content_words = struct.unpack_from('>ii', data, offset)
        record = offset + 8
        offset = record + content_words * 2

        record_type = struct.unpack_from('<i', data, record)[0]
        if record_type == 0:                   # null shape
            continue

        num_parts, num_points = struct.unpack_from('<ii', data, record + 36)
        parts_at = record + 44
        points_at = parts_at + num_parts * 4

        starts = list(struct.unpack_from(f'<{num_parts}i', data, parts_at))
        starts.append(num_points)

        for i in range(num_parts):
            first, last = starts[i], starts[i + 1]
            if last - first < 2:
                continue
            coords = struct.unpack_from(f'<{(last - first) * 2}d', data,
                                        points_at + first * 16)
            # Shapefiles store x,y - longitude first.
            yield [(coords[j + 1], coords[j]) for j in range(0, len(coords), 2)]


def write(lines, path):
    with open(path, 'wb') as out:
        out.write(b'WMBM')
        out.write(struct.pack('>ii', 1, len(lines)))
        points = 0
        for line in lines:
            out.write(struct.pack('>i', len(line)))
            for lat, lon in line:
                out.write(struct.pack('>ff', lat, lon))
                points += 1
    return points


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    lines = list(read_shapefile_polylines(sys.argv[1]))
    n = write(lines, sys.argv[2])
    print(f'{sys.argv[2]}: {len(lines)} lines, {n} points')
