# Weather Map

Composites NOAA GRIB forecast fields over a base map drawn from OpenStreetMap
data. A desktop application to choose the area and the data, and a command-line
tool that repeats the last choice — or a saved profile — against the newest
model run.

**This is an outline.** The pipeline runs end to end against the live services,
but several pieces are deliberately sketched rather than finished.
[What is not done yet](docs/not-done-yet.md) is the honest list.

![The chart pipeline, from a chosen area to a composited PNG](docs/architecture/svg/pipeline.svg)

The image written to disk is the image on screen: one `Compositor`, called by
both front ends, so what is seen and what is saved cannot drift apart.

```bash
make help                                  # every target
make run                                   # the desktop application
make run ARGS="--cli --profile 'Western Approaches'"
```

## The code

![The packages of org.weathermap and how they depend on each other](docs/diagrams/code-structure.svg)

## Documentation

The same pages are in the application: **Help → User's Guide** (F1) and
**Help → Developer's Guide**, each a tab per page.

| Page | What is in it |
|---|---|
| [Basic usage](docs/basic-usage.md) | Choosing an area, choosing data, reading the chart, stepping a series |
| [Packaging](docs/packaging.md) | Installable packages for Linux, Windows and macOS; the version scheme |
| [Build and run](docs/build-and-run.md) | Building, the two front ends, the CLI options, the optional NetCDF-Java profile |
| [How it works](docs/how-it-works.md) | The rectangle, the vector base map, the bundled gazetteer, persistence, caching |
| [Rendering](docs/rendering.md) | The layer stack, field treatments, wind barbs, isobars, highs and lows |
| [Series, profiles and chart times](docs/series-and-profiles.md) | Charts every *n* hours across past and future, saved profiles, the time labels |
| [Themes](docs/themes.md) | Light, dark and system, and why the map is never themed |
| [GRIB decoding](docs/grib-decoding.md) | What probing NOMADS established, and why there is no third-party decoder |
| [Running against public services](docs/operations.md) | The User-Agent, rate limits, polite usage, data licensing |
| [What is not done yet](docs/not-done-yet.md) | The gaps, ordered by how much they matter |
| [Architecture diagrams](docs/architecture/) | C4 context and containers, the pipeline, the data structures |

## Data and licensing

| Source | Licence | Obligation |
|---|---|---|
| OpenStreetMap | ODbL 1.0 | Attribute; share alike if you publish derived data |
| Natural Earth | Public domain | None, but credited on charts that use it |
| NOAA NOMADS | US Government public domain | None, but crediting NOAA is conventional |

Every composited chart carries the credits for the sources it was actually drawn
from — see [Running against public services](docs/operations.md).
