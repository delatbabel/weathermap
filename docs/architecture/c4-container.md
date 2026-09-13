# Weather Map — C4 Level 2: Containers Diagram

![Weather Map — C4 Level 2: Containers Diagram](svg/c4-container.svg)

**Two front ends, one pipeline**

- MainWindow runs MapService on a SwingWorker
- WeatherMapCli runs the same call and exits
- No second renderer, so screen and file cannot differ

**Shared state on disk**

- Preferences carries the last area and selection
- ProfileStore keeps named areas and selections
- Cache holds GRIB runs indefinitely and OSM for four weeks
- instagram.properties holds a bearer token, owner-readable only

**No third-party decoder**

- NOMADS filter output is simple-packed, so Grib2Scanner reads it directly
- NetCDF-Java stays an optional profile for everything else

**Publishing**

- Instagram fetches every image; nothing local can be posted
- So charts are written to a folder served at a configured public URL

---

Generated from `architecture` source by Archify, then exported with `tools/archify-export.py`. Edit the JSON under `docs/architecture/src/`, re-deliver, and re-export — never edit the SVG by hand.
