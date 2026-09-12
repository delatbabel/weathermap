# Weather Map

Composites NOAA GRIB forecast fields over a base map drawn from OpenStreetMap
data. A desktop application to choose the area and the data, and a command-line
tool that repeats the last choice against the newest model run.

**This is an outline.** The pipeline runs end to end against the live services —
the screenshots below are real output — but several pieces are deliberately
sketched rather than finished. [What is not done yet](#what-is-not-done-yet) is
the honest list.

```
┌──────────────┐     ┌─────────────┐     ┌──────────────┐     ┌──────────┐
│ Overpass API │────▶│             │     │              │     │          │
│ (OSM vector) │     │ MapService  │────▶│  Compositor  │────▶│   PNG    │
├──────────────┤     │             │     │  (Java2D)    │     │          │
│ NOAA NOMADS  │────▶│             │     │              │     │          │
│ (GRIB2)      │     └─────────────┘     └──────────────┘     └──────────┘
└──────────────┘            ▲
                            │
                 ┌──────────┴──────────┐
                 │                     │
           MainWindow            WeatherMapCli
            (Swing)                (headless)
```

## Build and run

Java 21 or later. One runtime dependency — [FlatLaf](https://www.formdev.com/flatlaf/),
about a megabyte, and only the window uses it. Everything else is in the JDK.

```bash
./mvnw package                                               # jars + tests
./run.sh                                                     # the desktop application
java -jar target/weathermap-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

The command-line tool is the same jar, and runs from the plain one too, since
it never creates a window:

```bash
java -jar target/weathermap-0.1.0-SNAPSHOT.jar --cli --help
```

The desktop application started from the plain jar still opens — it falls back
to the platform look and feel rather than failing on the missing class — it just
will not be themed.

With no options it repeats the last area and GRIB selection the desktop
application stored, against the latest published run — which is the point:

```bash
# in a crontab, ten minutes after each GFS cycle is published
10 4,10,16,22 * * *  java -jar /opt/weathermap.jar --cli --quiet
```

Everything stored can still be overridden:

```bash
java -jar target/weathermap-0.1.0-SNAPSHOT.jar --cli \
    --area "-6,53.5,0,57" --var TMP --level 2_m_above_ground \
    --hours 0,6,12-24 --out ~/maps --size 1600x1200
```

## How it works

### The rectangle is not just what gets drawn

NOMADS publishes whole model output as single files — a GFS 0.25° forecast hour
is around 500 MB. The `filter_*.pl` CGI in front of it cuts a file down to the
requested variables, levels and geographic subregion **server-side**.

Measured: 2m temperature over the UK, one forecast hour, is **2,359 bytes**.

That is why `BoundingBox` threads through the whole application — the selected
rectangle is what gets transferred, not only what gets rendered.

### The base map is vector, not tiles

Coastline, administrative boundaries and populated places are fetched from the
Overpass API as OSM features and drawn as separate Java2D layers. Two reasons:

- **Control.** The GRIB field is drawn translucently over the base map, so the
  base map has to be styled to sit underneath it. Baked tiles cannot be.
- **Policy.** The openstreetmap.org tile servers explicitly forbid automated
  bulk downloading, so they are not a legal source for a
  "download-a-region" feature. Overpass is designed for exactly this query.

### Names come from a bundle, shapes from OSM

Overpass is asked for coastline, boundaries and populated places. It is never
asked for water, and above 20 degrees it is not asked at all — a 56 degree box
is refused or times out, and full-resolution coastline at that scale lands
several nodes to the pixel anyway.

That left two holes, and a bundled Natural Earth gazetteer
(`WorldGazetteer`, 29 KB, public domain) fills both:

- **Water has no name at any scale.** The place query asks for
  `place=city|town|…`, so nothing ever supplied "Gulf of Thailand". On a chart
  read for the wind this is the wrong thing to omit: whether a southerly over
  southern Vietnam comes off the Gulf of Thailand or out of the South China Sea
  is the difference between two forecasts. Marine names are therefore drawn at
  every scale, not only wide ones.
- **Wide charts had no labels at all.** Beyond the Overpass limit a composite
  was unnamed outlines and several hundred barbs. Bundled cities and country
  names now stand in, and are used *only* there — where OSM has answered, its
  places are better.

Two resolutions, deliberately: marine features and cities at 1:50m, countries
at 1:110m. The 1:110m marine set has 29 entries and stops at "South China
Sea"; 1:50m has 118 and includes the Gulf of Thailand, the Andaman Sea and the
Gulf of Tonkin. Everything is bundled and the *renderer* cuts by span, because
what belongs on a chart depends on how wide it is.

Marine features are polygons in the source, so `tools/make-gazetteer.py`
derives a point for the name to sit on: the area centroid where it falls inside
the shape, and the midpoint of the widest horizontal chord where it does not —
a crescent like the Gulf of Thailand has its centroid on land.

All three kinds compete for one label budget against a single collision set, so
a sea name and a city name cannot be drawn on top of each other.

### One renderer, two front ends

`MapService` is the whole job: fetch, download, decode, composite, write.
`MainWindow` runs it on a `SwingWorker`; `WeatherMapCli` runs it and exits.
There is no second renderer and no "preview quality", so what is displayed and
what is saved cannot drift apart.

### Persistence

`~/.weathermap/preferences.properties` holds the last area, the GRIB selection,
the render settings, the theme and the window layout. The desktop application
writes it **before** a download starts, not after — a run that was cancelled or
that failed still represents what the user asked for, and that is what the CLI
should repeat.

`ui.layout` is six integers: window position and size, then the two divider
positions — the map against the controls, and the area controls against the
GRIB controls. One key rather than six, because they are only read and written
together and a half-restored window is worse than a default one. Two things it
refuses to restore: a size below 320 px, which is what a window minimised on
some platforms reports and which comes back too small to find the controls in;
and a position on a screen that is no longer attached, which opens the window
where the user cannot reach it.

A maximised window is not saved as a size, or un-maximising would restore to
full screen and the size actually chosen would be lost.

All of it fails soft: a missing or unreadable file yields defaults, and one bad
value is dropped individually rather than discarding the file.

### Themes

Three choices in the **Appearance** menu, switchable without a restart: FlatLaf
**Light**, FlatLaf **Dark**, and **System** — the platform look and feel, for
anyone who would rather the application stopped having opinions and matched the
rest of their desktop. The choice is saved as soon as it is made rather than on
close, since someone who switches theme and then kills the window has still
expressed a preference.

The two FlatLaf themes share their metrics, so switching between them changes
only colours. System does not: the platform look and feel has its own fonts and
insets, and controls genuinely change the size they ask for. Every open window
is therefore revalidated after a switch, not just repainted — which works
because the panels use layout managers rather than absolute positions.

**The map does not follow the theme, and that is deliberate.** It is drawn by
the same `Compositor` that writes the PNG, so theming it would either fork the
renderer — the one thing this design refuses, since the point is that what is
seen and what is saved cannot differ — or make the command-line tool's output
depend on a setting in a window it never opens. A chart is a document: it gets
saved, printed and read beside paper ones, and its colours answer to the
conventions of a weather chart rather than to the time of day. So the map stays
a light page in a dark room. The chrome around it — the letterboxing, the hint
pill — does follow the theme.

### Caching

`~/.weathermap/cache` holds both sides, with different expiry policies because
they differ in kind:

| | Cached for | Why |
|---|---|---|
| OSM features | 28 days | Slow to fetch, rate-limited, and change slowly |
| GRIB subsets | Until evicted | A published run is immutable — it will never change |

## Things established by probing the live services

Three assumptions turned out to be wrong, and the design changed because of it.

### NOMADS filter output is simple-packed, not JPEG2000

GRIB2 packs its data section with one of a dozen templates, and the raw
published GFS files use JPEG2000 (template 5.40) — which is why decoding GRIB
normally means taking a large dependency.

But the filter endpoints **re-encode each subset**, and what comes back is
template 5.0, simple packing, on a regular lat/lon grid (3.0). Verified against
live GFS 0.25° responses for `TMP`, `PRMSL` and `APCP`.

So `Grib2Scanner` decodes it directly in about 200 lines, with no dependency at
all. NetCDF-Java became the *optional* path for everything else rather than the
main one — see [Optional NetCDF-Java](#optional-netcdf-java).

### GRIB2 angles are sign-magnitude

Latitudes, longitudes and the binary/decimal scale factors are stored with the
top bit as a sign flag, not in two's complement. Reading a southern latitude as
a signed integer gives a number near −2 billion. `signedMicroDegrees` and
`signedShort` exist for this.

### Section 3's point count is at octet 7, not 6

An off-by-one here reported "9 points" for a 2,491-point grid, because it read
the last three bytes of the count plus the preceding source byte. It is the kind
of error that produces plausible-looking nonsense rather than an exception, and
it was only caught by checking the number against the arithmetic — 13° ÷ 0.25 +
1 = 53 columns, 11.5° ÷ 0.25 + 1 = 47 rows.

## Two rendering decisions worth knowing

**Colour ramps are fitted to the data by default.** The temperature ramp spans
233–318 K so that freezing always sits at the same colour — good for comparing
maps. But a September field over Britain occupies 282–292 K and renders as a
single flat orange. `RenderSpec.autoScaleRamp` fits the ramp to each field's own
range instead; the legend always shows the range actually used, so a fitted map
never misleads. Turn it off to compare maps against each other.

**Place labels are thinned by importance before placement.** An Overpass query
for a few degrees returns every hamlet — 14,889 features for northern Britain in
one test. Collision detection alone does not help, because it keeps whichever
labels are placed first and the cities get crowded out by villages. The cut is
made by `place=` rank against the size of the area.

## Layout

```
org.weathermap
├── Main                 dispatches GUI or CLI
├── MapService           the whole job, shared by both front ends
├── model/               BoundingBox, projections, GRIB selection, Preferences
├── osm/                 Overpass client, OSM XML parser, Feature
├── grib/                NOMADS client, GRIB2 reader, Grid
│   └── netcdf/          the optional NetCDF-Java decoder (profile: netcdf)
├── render/              Layer stack, colour ramps, Compositor, PngWriter
├── gui/                 MainWindow, MapPanel, AreaPanel, DataPanel
├── cli/                 WeatherMapCli
└── util/                Http, Cache
```

The layer stack, bottom to top: land/sea fill, **scalar fields**, graticule,
coastline, boundaries, place labels, **wind barbs**, annotation.

That order is the argument of `Compositor`, and it is the opposite of what
"weather overlay" usually means. A marine chart is read for the wind; the
coastline is what tells you where the wind is. Both have to survive whatever
else is drawn, so the colour fields go *underneath* rather than over the top — a
temperature wash painted over a coastline does not make the temperature clearer,
it makes the coastline worse.

Each field carries its own weight rather than sharing one opacity setting,
because the right answer differs (`render/FieldStyle`):

| Field | Treatment | Opacity |
|---|---|---|
| Temperature, dew point | Desaturated and lightened — a pale wash to read past | 0.32 |
| Precipitation | Darkened and saturated — weight on the map | 0.62 |
| Cloud, humidity | A grey veil | 0.38 |

`RenderSpec.gribOpacity` (`--opacity`) scales all of them at once.

### Wind barbs

The top layer. `WindBarbLayer` draws the standard notation: the staff points
**into** the wind, a short tick is 5 knots, a long tick 10, a filled triangle 50,
and calm is an open circle. Speed is rounded to the nearest 5 before
decomposition, since the notation cannot express anything finer.

Feathers sit on the side toward low pressure, which Buys Ballot's law mirrors at
the equator — decided per station, so a chart spanning it is right on both sides.

Wind is one selectable variable that fetches `UGRD` and `VGRD` together;
selecting a single component would download data that cannot be drawn.

### Isobars

Pressure is drawn as isolines rather than as a colour wash, because a wash of
pressure is a meaningless pastel gradient while the lines are what a synoptic
chart is read by: their *spacing* is the pressure gradient, so tight lines mean
strong wind whether or not a barb happens to have been drawn there.

Marching squares over a lattice sampled every 4 output pixels — finer would
trace interpolation artefacts rather than weather, since the field underneath is
a 0.25° grid. Both ambiguous saddle cases are resolved against the cell centre;
getting that wrong joins two systems that are not connected.

The interval is 4 hPa, which is the marine and synoptic convention, so the
spacing means the same thing here as on a chart from anywhere else. Every fifth
line — each 20 hPa — is drawn heavier, and the interval is written in the title,
because a reader cannot judge a gradient without knowing what one gap is worth.
Labels lie along their own contour, kept upright, spaced so a short closed
contour gets one and a long one gets several.

### Highs and lows

The letters a reader looks at before any individual isobar: the lines say what
the gradient is doing, the letters say what is driving it. A closed contour
already implies a centre, but finding it by eye means tracing rings inwards, and
near the edge of the chart the innermost ring may not be drawn at all.

Local extrema of the field, with four filters, each removing a different kind of
false centre:

- **Smoothing** first. A quarter-degree field interpolated up to chart size
  carries ripples that are extrema in the strict sense and weather in no sense.
- **A radius.** A centre must beat everything within 110 px, not just the samples
  next to it — measured over a circle, since the corners of a square are half
  again as far away and would bias the test.
- **Prominence** of half a contour interval. The real test is for a *closed*
  isobar: a system worth a letter has lines that go round it, and a bump on a
  slope does not. A quarter of an interval was tried first and marked two highs
  on one European ridge plus a 1 hPa dip over the Gulf of Lion, neither of which
  a forecaster would have drawn.
- **The saddle between two centres.** Distance cannot tell a ridge with two
  bumps from two adjacent highs; the ground between them can. Walk the field
  from one to the other, and if it never drops a full interval below the lower
  of the two, no isobar can close around either alone and it is one system.

Candidates within the search radius of the edge are dropped outright. A field
still rising as it leaves the chart has its maximum on the border, and that is
not a high — it is a high somewhere off the map, and marking the edge of the
paper claims to know where it is.

Central pressure is written underneath in whole hectopascals. The extra digit is
below the accuracy of the analysis and reads as a precision the forecast does
not have. Only pressure gets letters: the extrema of a geopotential height field
are ridges and troughs, and marking those with an H would be worse than leaving
them unmarked.

The letters follow the blue-H, red-L convention. Both are pushed well clear of
what is already on the map — the blue is more saturated than the coastline's
muted navy, and the red is nowhere near the boundaries' dusty purple. The
isobars themselves stay brown: colouring lines by the system they belong to is
not something charts do, and could not be done anyway, since one isobar usually
runs past several.

The colour is a warm dark brown, chosen against what is already on the map: the
coastline is dark blue and the boundaries a dusty purple, so a cool colour would
read as more of the base map, and the barbs are near-black and have to stay the
most prominent thing. Brown is what is left, and it is also what paper charts
use.

### A series of charts

A chart every *n* hours covering *m* hours from now — three and forty-eight by
default. `--series 3,48` on the command line, or the **Series from now** row in
the window, which disables the explicit forecast-hours field because a series
and a list of hours are two answers to the same question.

It can start in the past: `--series 6,24,48` covers twenty-four hours back as
well as forty-eight ahead, up to `MAX_SERIES_HOURS_BACK` — **eight days**.

That limit is set from what the archive holds rather than from a guess about
what anyone would want; the first version guessed two days and was wrong within
a week. Probing NOMADS found runs from nine days ago served and ten days gone,
and the boundary moves each day as a day rolls off, so eight leaves a day of
margin. A live `--series 24,192,0` returned eight charts spanning a week, every
one of them an analysis.

It is a margin and not a guarantee: a run can disappear between one request and
the next. A chart whose run has rolled off is therefore **skipped with a
warning** rather than failing the series around it — one chart of a week ago
being gone is no reason to throw away the other fifteen. Anything other than a
404 still stops the run, since a broken connection would fail every remaining
chart too. Downloads come back paired with the request that produced them rather
than matched by position, because the result can now be shorter than what was
asked for.

**Past charts are not forecasts re-run.** Each chart picks its own run by a
single rule that covers both directions:

```
run  = the latest cycle at or before min(valid time, now - publication lag)
lead = valid time - run
```

A chart of the future takes the newest run and a long lead, because that is the
best forecast there is. A chart of last night takes *last night's run* at a lead
of a few hours — often zero, the model's own analysis of that moment. A live
run of `--series 6,24,12` produced six charts, three of them analyses, longest
lead f018.

The `min` is what keeps it honest near the present: a run exists on paper before
NOMADS finishes publishing it, and asking for one that is not there yet gets a
404 rather than a fallback. That is also why a chart of three hours ago can have
a six-hour lead while one of thirty hours ago has none.

**An analysis is not an observation.** It is the model's own best estimate of
that moment, having assimilated observations into it. It is the closest this
source comes to "what actually happened", and it is what synoptic charts of the
past normally show, but it is not a measurement.

The subtlety on the forward side is that **"starting now" is not forecast hour 0**. Forecast hours
count from the model run, and the run in hand is always hours old — NOMADS
publishes one about four hours after its nominal time. So the first chart is the
first step at or after the gap between the run and the clock: for the 00Z run at
09:30 UTC with a three-hour step, that is hour 12. Getting this wrong is not
visible in the output, because hour 0 renders perfectly well — it is simply a
chart of this morning.

The series is stored as its definition rather than as the hours it resolved to,
which is what makes it correct in a crontab: "every three hours for the next two
days" means the same thing every night, while `9,12,15,18` means three moments
that have already passed. It is resolved to actual hours exactly once per run,
so the download and the labelling cannot disagree across a step boundary.

In the window, **◀** and **▶** step through the series one chart at a time, as
do the arrow keys while the map has focus. A front's arrival is obvious in the
difference between two charts and nearly invisible in either one alone, so the
buttons are on the toolbar rather than in a menu.

**Chart → Copy chart image** (Ctrl-Shift-C) puts the chart on screen on the
clipboard as an image, not as a path — a chart is usually wanted in a message or
a document. The accelerator avoids Ctrl-C deliberately: a menu accelerator fires
wherever the focus is, and taking Ctrl-C would break copying out of the latitude
and longitude fields.

### Chart times

Two labels, top right and much larger than the title: **VALID AT**, the instant
the forecast describes, and **CHART CREATED**, when the PNG was rendered. They
are what a chart gets checked against — whether this is the forecast for
tomorrow's crossing, and whether it was made before or after the last model run.
A chart whose age is not obvious gets trusted long after it should have been
thrown away.

The valid time used to sit in the title line, small and always in UTC. It does
not any more: having it in two places in two zones is two answers to one
question.

Times are written in the zone set by `--timezone` or **Chart → Time zone…**,
defaulting to the machine's own — "valid 06:00 UTC" makes someone in Bangkok do
arithmetic before they can tell whether it is this afternoon. The zone changes
how an instant is written down, never which instant it is.

The label is always an offset — `GMT+7`, `GMT+5:30`, or `UTC` at zero — and
never an abbreviation. The abbreviations are not unique and not international:
BST is both British Summer Time and Bangladesh Standard Time, IST is India,
Ireland and Israel. The offset is resolved at each instant rather than once per
chart, so the two labels differ correctly across a clock change.

**File names stay UTC**, so a directory of charts sorts chronologically no
matter who generated it or where they were.

## Optional NetCDF-Java

```bash
./mvnw package -Pnetcdf
```

Compiles `grib/netcdf/NetcdfGribReader`, which is loaded reflectively so the
application builds, ships and runs without it. Needed only for sources the
built-in reader does not cover:

- raw published files rather than filter output (JPEG2000, complex packing);
- Lambert-conformal grids — NAM and HRRR, grid template 3.30;
- the full WMO parameter table, rather than the nine variables catalogued here.

**It is currently a stub** that reports itself unavailable. The class javadoc
sets out the intended route.

## What is not done yet

Ordered by how much they matter.

| Gap | Where | Notes |
|---|---|---|
| Barb station spacing is a fixed pixel grid | `WindBarbLayer` | Never finer than the data, but it does not thin toward the poles under Mercator, where stations crowd together in latitude. |
| Land/sea fill is flat | `VectorLayers.LandSeaLayer` | Deriving land needs the OSM convention that land is left of a coastline way, plus stitching open segments into closed rings against the box edges. Today the coastline stroke does the work, which reads correctly for a coastal box and wrongly for an inland one. |
| `NetcdfGribReader` | `grib/netcdf/` | Stub. |
| Accumulated fields are labelled with the start of their period | `Grib2Scanner.validTime` | Product template 4.8 stores the interval end further into the template; an `APCP` message for f006 still reports 00Z. `Compositor.validTimeOf` works around it for the chart caption by taking the latest time across the fields, but the `Grid` itself is still wrong. |
| Overpass queries are not tiled | `OverpassClient` | A very large box exceeds the server limit. Splitting needs coastline segments stitched across tile seams. |
| OSM relations are ignored | `OsmXmlParser` | Multipolygon boundaries render as their member ways — right for strokes, wrong for fills. |
| Antimeridian | `BoundingBox` | Refused rather than split. Supporting it means two boxes and compositing the halves. |
| Download progress is end-only | `Http.download` | `BodyHandlers.ofFile` gives no intermediate callbacks; needs a counting `BodySubscriber`. |
| Variable/level availability is only partly validated | `GribCatalog.STANDARD_LEVELS` | The common levels are known and an impossible pair is now repaired by the CLI and queried by the UI, but the full set is only discoverable from each model's filter form, so an unknown pairing is still allowed through and fails at download time. |
| Isoline segments are not stitched into paths | `IsolineLayer` | Marching squares emits unordered segments, which draw correctly and label adequately. Stitching would buy smoothing and a gap in the line under each label. |
| A marine label point can land on land | `tools/make-gazetteer.py` | The centroid-then-chord rule holds for convex and crescent shapes; a many-armed one such as "Inner Seas off the West Coast of Scotland" can still put its name over an island. A pole of inaccessibility would fix it. |
| Cache eviction is never called | `Cache.evictTo` | Implemented but unwired; needs a configured budget. |
| Elevation | — | Left out by decision. OSM has `ele` tags on peaks but no terrain model; shaded relief needs SRTM or Copernicus DEM as a separate source. |

## Before pointing this at the public services

- **Set a real `Http.USER_AGENT`.** It currently names `example.invalid`. Both
  the OSM Foundation's Overpass instances and NOMADS police the User-Agent, and
  anonymous clients get blocked.
- **Keep the attribution layer on.** OSM data is ODbL, which requires the source
  to be credited on anything produced from it. `AnnotationLayer` draws
  "© OpenStreetMap contributors" whenever the annotation layer is enabled.
- **Be sparing.** Both services are shared capacity. The cache exists so that
  re-rendering the same area costs nothing; requests are made in sequence, never
  in parallel.

## Licensing of the data

| Source | Licence | Obligation |
|---|---|---|
| OpenStreetMap | ODbL 1.0 | Attribute; share alike if you publish derived data |
| Natural Earth | Public domain | None, but the outline and the gazetteer are credited on charts that use them |
| NOAA NOMADS | US Government public domain | None, but crediting NOAA is conventional |
