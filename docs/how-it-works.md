# How it works

> The [system context](architecture/c4-context.md) and
> [container](architecture/c4-container.md) diagrams show the same thing as a picture.



## The rectangle is not just what gets drawn

NOMADS publishes whole model output as single files — a GFS 0.25° forecast hour
is around 500 MB. The `filter_*.pl` CGI in front of it cuts a file down to the
requested variables, levels and geographic subregion **server-side**.

Measured: 2m temperature over the UK, one forecast hour, is **2,359 bytes**.

That is why `BoundingBox` threads through the whole application — the selected
rectangle is what gets transferred, not only what gets rendered.

## The base map is vector, not tiles

Coastline, administrative boundaries and populated places are fetched from the
Overpass API as OSM features and drawn as separate Java2D layers. Two reasons:

- **Control.** The GRIB field is drawn translucently over the base map, so the
  base map has to be styled to sit underneath it. Baked tiles cannot be.
- **Policy.** The openstreetmap.org tile servers explicitly forbid automated
  bulk downloading, so they are not a legal source for a
  "download-a-region" feature. Overpass is designed for exactly this query.

## Names come from a bundle, shapes from OSM

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

## One renderer, two front ends

`MapService` is the whole job: fetch, download, decode, composite, write.
`MainWindow` runs it on a `SwingWorker`; `WeatherMapCli` runs it and exits.
There is no second renderer and no "preview quality", so what is displayed and
what is saved cannot drift apart.

## Persistence

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

Named profiles sit beside it, one file each under `~/.weathermap/profiles`, in
the same format — see [Series, profiles and chart times](series-and-profiles.md).

`instagram.properties` is kept separate from all of them, because it holds a
bearer token rather than a preference: the file is made owner-readable where the
filesystem allows, and the token is never logged or put in an error message. See
[Posting to Instagram](instagram.md).

**A trap worth knowing about.** The window runs on *copies* of the selection and
the render settings while the command-line tool does not, so anything
`GribSelection.copy()` or `RenderSpec.copy()` forgets is a setting that
demonstrably works from the terminal and silently does nothing in the window.
That has happened twice — to the backward leg of a series, and to the chart time
zone. `CopyCompletenessTest` now compares every declared field after a copy, so
adding a field and forgetting to copy it fails a test rather than shipping.

All of it fails soft: a missing or unreadable file yields defaults, and one bad
value is dropped individually rather than discarding the file.

## Caching

`~/.weathermap/cache` holds both sides, with different expiry policies because
they differ in kind:

| | Cached for | Why |
|---|---|---|
| OSM features | 28 days | Slow to fetch, rate-limited, and change slowly |
| GRIB subsets | Until evicted | A published run is immutable — it will never change |
