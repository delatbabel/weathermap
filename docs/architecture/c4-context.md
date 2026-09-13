# Weather Map — C4 Level 1: System Context Diagram

![Weather Map — C4 Level 1: System Context Diagram](svg/c4-context.svg)

**What it is for**

- Composites NOAA forecast fields over an OSM base map
- Wind barbs on top; the chart is read for the wind

**External dependencies**

- NOMADS serves GRIB2 already cut to the area
- Overpass serves coastline, boundaries and place names
- Natural Earth ships in the jar and needs no network

**Fail-soft boundaries**

- No Overpass answer still renders, from the bundle
- A run that has rolled off the archive is skipped, not fatal

---

Generated from `architecture` source by Archify, then exported with `tools/archify-export.py`. Edit the JSON under `docs/architecture/src/`, re-deliver, and re-export — never edit the SVG by hand.
