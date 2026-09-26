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

## The rectangle may cross the antimeridian

`170,-10,-170,10` is twenty degrees of Pacific, not three hundred and forty:
an east edge numerically west of the west edge is how a box says it runs
through 180°. There is no other way to write it with two numbers in
-180..180, so the inverted pair is the notation rather than an error.

Nothing downstream special-cases it. Longitude is measured with
`BoundingBox.eastwardFrom`, which wraps, so both projections put the seam
somewhere in the middle of the image and every layer — coastline, labels,
isobars, barbs — draws across it without knowing it is there. One image, one
`Compositor`, exactly as for anywhere else.

The two places that cannot be told this are the wire protocols. Overpass's
`bbox` filter and NOMADS's `leftlon`/`rightlon` both insist on west < east,
so those callers ask `BoundingBox.halves()` for the box in one or two pieces:

- **Overpass** puts a filter for each piece in the same union, so it stays one
  request, one cache entry, one parse.
- **NOMADS** makes one subregion request per piece, and `Grid.join` stitches
  the decoded fields back into one before anything is drawn. Two requests
  rather than one whole-band request: the band would be several times the bytes
  for the same chart, off a service that asks to be used sparingly. The halves
  meet at 180° and both include it, so the repeated column is dropped rather
  than drawn twice.

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
filesystem allows, and the token is never logged or put in an error message. It
also keeps the last caption, so a daily post starts from the previous day's text
rather than an empty box. See [Posting to Instagram](instagram.md).

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
| OSM responses | 28 days | Slow to fetch, rate-limited, and change slowly |
| OSM features | with the response | The parsed form beside it, so 108 MB of XML is not re-read into features on every press — about 180 ms instead of 2,900 |
| GRIB subsets | Until evicted | A published run is immutable — it will never change |
| Tide predictions | 24 hours | Not slow, but *rationed*: Storm Glass counts every request against a daily quota |

The parsed features are a **derivation**, not a second copy: the XML stays
canonical, and anything wrong with the derivation — a different format
version, a short file, one older than the response it came from — throws it
away and rebuilds it rather than being trusted.

### The budget

Nothing in there cannot be fetched again, so the cache is bounded by size
rather than by policy: **File → Preferences → Cache limit**, 2 GB by default,
0 for no limit. The oldest entries go first when it is passed — checked at
startup, after a download and after a **Load detail**, on a background thread.

Oldest *fetched*, not least recently used, and that is a deliberate second
best. Real LRU wants the time an entry was last read, and the obvious way to
record it — touch the file on a cache hit — cannot be done here, because the
modification time is also what the TTL is measured against. Touching on read
would make a busy entry immortal and quietly disable expiry. Oldest-first is at
least aligned with that: the entry nearest its own expiry is the one to go.

An OSM response and the features parsed from it are evicted together, since
they are written moments apart and taking one without the other either frees a
tenth of what was wanted or strands a derivation of a response that has gone. A
`.part` file is a download in progress and is neither counted nor touched.
