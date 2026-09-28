# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

`make help` lists every target. The Makefile wraps `./mvnw`; either works.

```bash
make build                 # compile
make test                  # all tests
make jar                   # the fat jar, target/weathermap-<version>-jar-with-dependencies.jar
make run                   # the desktop application
make run ARGS="--cli --profile 'Western Approaches'"
make cli ARGS="--help"     # the command-line tool
make javadoc
```

A single test class, or one method:

```bash
./mvnw test -Dtest=BoundingBoxTest
./mvnw test -Dtest='BoundingBoxTest#splitsAtTheSeamWestFirst'
./mvnw test -Dtest='Map*Test,GridJoinTest' -DfailIfNoSpecifiedTests=false
```

**`JAVA_HOME` must point at a full JDK 21 or newer.** The project sets
`maven.compiler.release=21`, and on this machine the default `java` on `PATH` is
a JRE with no compiler, so Maven fails with `release version 21 not supported`
before compiling anything. `make run` on the built jar is unaffected — only
compilation needs it.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw test
```

Tests never touch the network: the ones that exercise HTTP stand up a
`com.sun.net.httpserver` instance on `127.0.0.1:0` (see `HttpTest`,
`NomadsClientTest`).

## Architecture

### One pipeline, two front ends

`MapService.run()` is the whole job — fetch base map, download GRIB, decode,
composite, write PNGs — and both front ends call it and nothing else.
`Main` dispatches to `gui.MainWindow` or `cli.WeatherMapCli` on `--cli` (or on
any argument at all). "The CLI repeats what the GUI last did" is true by
construction because there is one sequence, and because the GUI's choices are
persisted through `model.Preferences` / `model.ProfileStore` and read back by
the CLI.

### The image on screen is the image on disk

There is one `render.Compositor`, called by the window and by the CLI. Forking
it is the thing this design refuses. This is why the selection map is *not*
themed: `gui.MapPanel` draws through the same compositor that writes the PNG, so
theming the map would either fork the renderer or change the CLI's output to
match a setting in a window it never opens. Chrome over the map (the hint pill,
the letterboxing) follows the theme; the map does not.

### `BoundingBox` is the unit of transfer, not just of view

NOMADS's `filter_*.pl` cuts the GRIB server-side and Overpass takes a `bbox`, so
the chosen rectangle decides what is *downloaded*, not only what is drawn. That
is why it threads through every layer of the application.

It may cross the antimeridian, and says so by having an east edge numerically
west of its west edge (`170,-10,-170,10` is twenty degrees of Pacific). Consumers
do not special-case this: longitude is measured with `eastwardFrom`, which wraps,
and `widthDegrees` accounts for it. The two callers that cannot — Overpass's
`bbox` and NOMADS's `leftlon`/`rightlon` both insist on west < east — call
`halves()` and issue one request per side, with `Grid.join` stitching the decoded
fields back together. See `docs/how-it-works.md`.

### Everything draws through one projection

A `model.MapProjection` is built once per render from the box and the output
size; coastline, boundaries, labels, the GRIB field and the graticule all use
that same instance, which is what keeps them in register. `GribLayer`,
`IsolineLayer` and `WindBarbLayer` walk *output pixels* and sample the grid
through `toLatLon`, so they are projection-agnostic and need no resampling code
of their own.

Both projections make x linear in longitude, and two places rely on it:
`VectorLayers.pathOf`, which unwraps a polyline so a way on the far side of the
world is not drawn across the picture, and `MapPanel.paintSelection`.

**`MapPanel` composites on a worker thread, not in `paintComponent`.** Three
counters carry the state: `renderGeneration` (what the map should show, bumped
by any view, size or feature change), `shownGeneration` (what `baseImage` was
drawn for) and `renderingGeneration` (what the worker is busy with). Painting
starts a render when the first two disagree and the third is idle, so a
superseded result is dropped rather than cancelled.

Two consequences worth knowing before touching it: the frame on screen may be
a view behind, so **everything that maps pixels to ground must use
`viewProjection()`** — the current view — and not `baseProjection`, which
belongs to the frame; and a stale frame is drawn stretched to where its ground
now falls, which is why a pan looks like a map catching up rather than a jump.

`pathOf` also **drops points closer than `MIN_STEP_PX` to the last one kept**.
OSM coastline is surveyed, not drawn for a screen: 13° of South-East Asia is
1.2 M points, over a thousand per pixel column, and the selection map renders
on the EDT on every pan. Don't remove the thinning without re-measuring — it is
worth 3× on that data, and the map is also *more* legible for it.

### Tides are a second, smaller pipeline

`tide/` does not go through `MapService`, and should not: a tide belongs to a
point, the map to a rectangle. It keeps the shape though — `TideSource` behind
the client the way `GribSource` is, `TideData` as the boundary between "Storm
Glass" and "picture" the way `Grid` is, and `TideChart` returning a
`BufferedImage` so that what is on screen and what is copied are the same
pixels. Like the map, the tide chart is deliberately unthemed.

**The Storm Glass daily quota is the constraint everything there answers to.**
The free tier allows ten requests a day and one chart costs two (the curve and
the turning points are separate endpoints). So `StormglassClient` asks for a
ten days at once, caches for 24 hours, and rounds coordinates to three
decimals first so the cache key stops moving. Before changing anything about
when it fetches, read `docs/tides.md`.

`util.Json` exists because of this: the tide response is an array of objects
with a nested `meta.station`, which the regex field-grab in `InstagramClient`
cannot read. No dependency was added, for the same reason there is no
third-party GRIB decoder.

The place is picked on the map before the window opens: `MapPanel.pickPoint`
arms a one-shot click (not a `Mode` — picking is a step, not a state to get
stuck in), `osm.NearestPlace` names it from whatever features are loaded, and
`MainWindow.tidePointMessage` asks before a request is spent. That message is
package-private and static so the words can be asserted without a window.

### Sources are interfaces, and failures are soft

`osm.OsmSource`, `grib.GribSource` and `grib.GribReader` separate where data
comes from, and downloading from decoding. `grib.Grib2Scanner` is the built-in
decoder (template 3.0 grids, simple packing — what the NOMADS filter returns);
`grib/netcdf/` is a stub behind the `-Pnetcdf` profile and is excluded from the
default build, so the project compiles with no third-party GRIB dependency.

The base map is context and the GRIB field is the point, so base-map failures
degrade rather than abort:

- Above `OverpassClient.MAX_SERVABLE_SPAN` (20°) Overpass is not asked at all —
  the query is refused *before* it is sent, because an impossible box costs the
  full retry budget across every instance to arrive at the answer we would have
  chosen instantly.
- Any Overpass failure falls back to the bundled Natural Earth outline
  (`osm.WorldBaseMap`) and gazetteer (`osm.WorldGazetteer`), built by
  `tools/make-*.py` into `src/main/resources/basemap/*.bin`.
- `BaseMapLoader` has **two** paths and they ask for different things
  deliberately: panning fetches place names only (a coastline query on every
  pan is what earned this client 429s), while **Load detail** is an explicit
  one-off and fetches whatever `MapService.featureKindsFor` says the chart
  needs. When real coastline arrives the bundled outline is dropped rather
  than drawn under it.
- **The selection map must show what a chart of the same area would show.**
  That means `BaseMapLoader.features()` mirrors `MapService.fetchFeatures`:
  the bundled outline *plus* the gazetteer's marine and country names, plus
  its cities where OSM has supplied none. Above `MAX_SERVABLE_SPAN` both fall
  back to the bundle. Every bug reported against **Load detail** so far has
  been the two drifting apart.
- A GRIB variable that is absent is reported through `Progress.stage` rather
  than thrown, because an absent field renders identically to a flat one.

Missing GRIB *runs* are different again: a run that has rolled off the archive
answers 404 and is skipped so the rest of a series survives, while any other
IOException stops the run.

### Caching

`util.Cache` keys on the request (the full NOMADS URL, the Overpass query text —
never the endpoint, since every instance serves the same database). A published
GRIB run is immutable so it never expires; OSM has a 28-day TTL; tides have a
24-hour one, which is a ceiling on requests rather than a guess at freshness.

An Overpass response is cached **twice**: the XML as it arrived, and the
features parsed out of it (`osm.FeatureStore`, `.features.bin` beside the
`.osm.xml`). The second is a derivation — 108 MB of XML is 10 MB of it, and
2,900 ms of parsing is 180 ms of reading — and is thrown away and rebuilt
whenever it is older than the XML or written by a different `VERSION`. Bump
that constant when the parser changes what it produces.

**The cache answers two different questions and they need different keys.**
`Cache.pathFor` hashes the request, which answers "have I sent this query
before" — useless across a restart, because the rectangle in the query comes
from the window size and one pixel of difference is a total miss. So each
extract carries a `FeatureStore.Header` (area, kinds, place-query detail) and
`OverpassClient.cachedCovering` scans those to answer "have I already got this
ground". Use it, not `fetch`, when the question is the second one.
`Header.answers` must keep checking the place-query detail: Overpass returns
fewer `place=` values for a wide box, so a wide extract reused close in loses
every village.

Two rectangles are in play whenever `BaseMapLoader` fetches and they are not
interchangeable — getting them the wrong way round is what made this feature
appear not to work at all:

| | |
|---|---|
| the **margined box** | what gets *downloaded*, so a small pan needs no refetch |
| the **view** | what must be *covered*, and what decides how fine the `place=` query is |

So `OsmSource.fetch(bbox, drawnAs, kinds)` takes both, and `cachedCovering` is
asked about the view. Deciding the place detail from the margin under-fetches
against what the renderer draws; asking coverage about the margin matches only
when the window has not grown.

The cache is **bounded by size, not policy** — `Preferences.cacheBudgetBytes`,
2 GB by default — and `Cache.evictInBackground` is called at startup, after a
download and after a Load detail. Eviction is oldest-*fetched* first, not LRU:
touching a file on read would record the access but also reset the clock the
TTLs are measured against, making a busy entry immortal. An `.osm.xml` and its
`.features.bin` are evicted as a pair.

Overpass reports a server-side timeout with **HTTP 200** and a well-formed OSM
document containing only a `<remark>`. `OverpassClient.rejectErrorDocument`
exists because caching one of those made an area render with no base map for
four weeks, with nothing in the logs.

## Conventions

**Comments carry the reasoning, at length.** Javadoc here explains why a thing is
the way it is and what went wrong before — see `NomadsClient`, `MapPanel`,
`VectorLayers.pathOf`. Match that register: a change that fixes a real failure
should say what the failure looked like. The same voice runs through `CHANGES.md`
and the commit messages (imperative subject, prose body explaining the why).

**`docs/*.md` are the in-app help**, copied into the jar at build time and shown
by **Help → User's Guide** (F1). One source, so they cannot drift. Update them
with behaviour changes; `docs/not-done-yet.md` is the honest list of gaps and
should shrink as they are closed.

**`RenderSpec.copy()` is field-by-field**, which is a liability: a field added
and not copied works from the CLI and silently does nothing in the window.
`CopyCompletenessTest` walks the declared fields to catch that — it requires
every field to differ both between the two copies *and* from a freshly built
object, so a field left at its default cannot pass untested.

**Rendering tests count pixels.** Several tests draw a layer into a
`BufferedImage` and assert on colours or column counts; they turn antialiasing
off where a colour has to be exact. Swing tests must size and paint **on the
EDT** (`SwingUtilities.invokeAndWait`, in two steps) — `setSize` posts a resize
event, and `MapPanel` discards its cached render when that arrives, so painting
from the test thread races it and intermittently produces a blank panel.

**A new `docs/*.md` page must be added to `HelpWindow`'s page list too**, or it
ships in the jar and is unreachable from Help.

**Version numbers are derived from `VNUM` at the top of the Makefile.**
`make version-bump` moves the Makefile, the pom *and* the prose in `README.md`,
`docs/build-and-run.md` and `docs/packaging.md` together; `make version-docs`
does the prose alone and fails if any three-part number in those files is not the
current version. Never hand-edit a version in those files, and do not add a file
that quotes some other version (rclone, a JDK) to `VERSIONED_DOCS`.
