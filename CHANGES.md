# Changes

## 1.1.0

**Tide charts**

- **Tide → Tide chart…** draws a day of tide for a point: the curve, the high
  and low waters marked on it, and their times and heights on cards
  underneath. The figures are drawn on the chart rather than beside it in the
  window, so a chart that gets shared still carries its own numbers. Arrows
  page through ten days; **Copy** and **Save PNG…** take it away.
- **The place is chosen on the map, by clicking it.** Scroll and zoom to the
  harbour, click, and confirm: the click is named from the map data —
  "Vũng Tàu (town) — 3.1 km away" — before anything is fetched. A latitude and
  a longitude are exact and unreadable, and a name and a distance can be
  checked at a glance. The question also says whether pressing the button
  costs two of the day's requests or none, and whether the name came from OSM
  or from the bundled gazetteer, because those two mean different things and
  read identically. Panning and zooming keep working while the map waits;
  Esc abandons it.
- The data comes from [Storm Glass](https://stormglass.io). The curve and the
  turning points are two endpoints and both are needed: picking the maxima out
  of hourly samples would put every high water on the hour and understate it.
  The line is drawn through both sets, so the marks sit where the printed times
  say they do.
- **The daily request limit shaped the design.** The free tier allows ten
  requests a day and one chart costs two, so ten days are fetched at once and
  kept for 24 hours; a second look at the same place costs nothing, and the
  status line says whether anything was spent. Coordinates are rounded to about
  a hundred metres first, so nudging the map selection does not buy the same
  ten days twice. A spent quota is reported as what it is rather than as
  "Payment Required".
- Tides are astronomical only — pressure, wind and surge are not in them, which
  the documentation says plainly.

**A Preferences screen**

- **File → Preferences** collects the Storm Glass API key, the chart time zone,
  the output size and the theme. The zone was behind the Chart menu, the theme
  behind Appearance, the size nowhere at all. Nothing is applied until Save.
- The Appearance menu stays, and the two are kept in step. Changing theme on a
  whim should not be two clicks deeper than it was.
- The preferences file now holds a credential, so it is written readable by its
  owner only.

**Load detail fetches what the chart would fetch**

- The button asked Overpass for place names while its tooltip promised a
  coastline, so on a view that already had its names, pressing it changed
  nothing and looked broken. It now asks for the coastline, the boundaries and
  the names — whichever of them the enabled layers mean, decided by the same
  code the chart uses, so the two cannot drift again.
- The real coastline replaces the bundled world outline rather than being
  drawn over it. Both at once is not a richer map, it is two coastlines a few
  kilometres apart.
- The automatic fetch while panning is unchanged and still asks for names
  alone: a coastline query repeated on every pan is what got this client
  answered with 429s. A button press is a different thing — it happens once,
  when someone has decided they want it.
- The status line now says what arrived, by kind, rather than a single total.
- **The parsed features are cached too, not only the bytes.** The response was
  kept for four weeks and then turned back into features from scratch every
  time: about three seconds for 108 MB, on every press and again after every
  restart. They are now written beside it in a compact form — 108 MB of XML
  becomes 10 MB, and 2,900 ms of parsing becomes about 180 ms of reading. The
  XML stays as the canonical copy, so a format or parser change throws the
  derivation away and rebuilds it rather than being stuck with it.
- **The selection map now carries the names the chart carries.** Above 8° it
  had none at all — no seas, no countries, no cities — while a chart of the
  same area had all three from the same bundled gazetteer. The map you choose
  an area on was emptier than the thing it is for. Bundled cities still give
  way to OSM ones where those have been loaded, because both sets at once
  puts two dots on some of them.
- **Above 20° it falls back to the bundled world map instead of refusing.**
  Overpass will not answer an area that wide, so the button did nothing —
  no detail, and, on that path, nothing in the log either — on exactly the
  wide areas a saved profile recalls. Meanwhile **Download and composite**
  quietly fell back to the bundle and drew a complete chart of the same
  place, so the two disagreed and the map was the poorer of them. It now does
  the same thing, in the same words, and drops any detail loaded for
  somewhere else.
- **Zoom out, press it again, and it now actually asks.** The size check was
  on the view while the query went out 70% wider — the margin fetched around
  it so a later nudge needs no refetch — so any view past about 12° passed
  the 20° check and then sent Overpass something it will not serve. The
  margin is now the first thing given up, and the check is on the box that
  actually goes out.
- A cache hit is logged at INFO. It was logged at FINE while a fetch logged
  at INFO, so being served instantly from disk looked exactly like the button
  having done nothing. Load detail now says in the log what it asked for and
  what came back, whichever way it was answered.
- Between 8° and 20° the status no longer says only "zoom in below 8° for
  place names". Nothing loads by itself up there, but the button works, and
  the old wording read as though it did not.
- Above about 6° it asks first. Thirteen degrees of South-East Asia came back
  as **108 MB** and a minute or two of waiting, which unannounced reads as the
  button having done nothing. The question says so, and says the answer is
  kept for four weeks.
- **Sub-pixel points are dropped when drawing.** That 108 MB is 1.2 million
  coastline points, and across nine hundred pixels it is over a thousand
  points per pixel column — every one of them a `lineTo` handed to the
  rasteriser to draw on top of the last. Measured on that response, a render
  went from 430 ms to 130 ms and the first one from 1.8 s to 0.36 s. Nothing
  is lost that could have been seen, and the map is legible rather than a dark
  smear where the coast is.
- **The selection map is drawn on a worker thread.** It used to be composited
  inside `paintComponent`, so every pan, zoom and resize froze the window for
  as long as the render took — a tenth of a second with detail loaded, and
  half a second before the thinning above. Painting now costs about 2 ms
  whatever is loaded.
- While a render is behind, the last finished frame is stretched to where its
  ground falls in the view as it is now, which makes a pan or a zoom look like
  a map catching up rather than a stutter. Drawing the old frame square at the
  origin instead — the obvious thing — showed the map jumping back and then
  forward, which is worse than the freeze it replaced.
- Mouse hit-testing no longer needs a render to have happened. It used to ask
  the last frame's projection where a click had landed, which would have been
  a view behind for as long as a render took; it asks the current view
  instead, and so does every overlay.
- Zooming out after loading detail for a small area no longer leaves that area
  drawn on an empty world. The bundled outline steps aside only while the view
  is inside what was loaded; past it, both are drawn and the disagreement that
  would be obvious close up is by then well under a pixel.

**The download cache is bounded**

- `Cache.evictTo` existed and was never called, so the cache grew without a
  ceiling — a few months of ordinary use reached **943 MB**, most of it single
  Overpass responses of a hundred megabytes and more. It is now wired to a
  budget under **File → Preferences → Cache limit**, 2 GB by default and 0 for
  no limit, and runs at startup, after a download and after a **Load detail**,
  on a background thread. Walking a thousand files takes single-digit
  milliseconds. The command-line tool trims before it exits, which is where an
  unattended hourly run would otherwise grow one unwatched.
- Oldest *fetched* first, not least recently used, and deliberately so:
  recording a read would mean touching the file, and the modification time is
  also what the 28-day and 24-hour expiries are measured against. Touching on
  read would make a busy entry immortal and disable expiry altogether.
- An OSM response and the features parsed from it are evicted together, since
  taking one without the other either frees a tenth of what was wanted or
  strands a 10 MB derivation of a response that has gone. A `.part` file is a
  download in progress and is neither counted nor deleted.
- The Preferences screen shows what the cache holds right now, because "the
  oldest go when it passes 2048 MB" reads very differently beside a line
  saying it is already at 943.

**A JSON reader**

- `util.Json`, a few hundred lines, because reading an array of objects with a
  regular expression is not reading it. No new dependency, for the same reason
  there is no third-party GRIB decoder.

**An area may cross the antimeridian**

- A rectangle can now run through 180°, so the Pacific can be chosen in one
  piece instead of in two halves composited by hand afterwards. A box says it
  crosses by having an east edge numerically west of its west edge —
  `170,-10,-170,10` is twenty degrees of ocean, not three hundred and forty.
- The selection map pans through the seam rather than stopping at it, and a
  drag reads its west edge from whichever corner was further left on screen
  rather than from whichever longitude is the smaller number. Those two answers
  agree everywhere except here, where the second selects the whole world except
  the part the pointer covered.
- NOMADS and Overpass both insist on `leftlon` west of `rightlon`, so a
  crossing area is fetched as one piece per side of the seam — two subregion
  requests, whose fields are joined back into one before anything is drawn, and
  one Overpass query carrying a filter for each side. An area that does not
  cross still makes exactly the requests it always did.
- Meridians are drawn and labelled across the seam, and 180° is labelled as
  itself rather than as 180°W.
- A global GRIB field decoded straight from NOMADS is no longer read inside
  out. Its first longitude is 0 and its last 359.75, and taking the smaller and
  larger of the pair described the quarter-degree slice on the far side of the
  prime meridian rather than the whole world.

## 1.0.0

First versioned release. The application composites NOAA GFS forecast fields
over an OpenStreetMap base map, as a desktop window and as a command-line tool
that repeats a stored selection or a named profile.

**Charts**

- Wind barbs on the top layer, drawn to the marine convention: the staff points
  into the wind, a short tick is 5 knots, a long tick 10, a filled triangle 50,
  and the feathers mirror across the equator.
- Isobars every 4 hPa with **H** and **L** on the pressure centres, found by
  marching squares over a smoothed field and filtered so a bump on a slope is
  not called a system.
- Scalar fields carry their own weight rather than sharing one opacity:
  temperature a pale wash, precipitation darker and heavier.
- Precipitation runs white, yellow, green, blue, indigo, purple as it gets
  heavier, darkening the whole way. The ramp it replaced varied in hue but
  hardly at all in lightness, so it read as one dark stain and drizzle was told
  from a downpour by the attribute the eye judges worst.
- Place names are drawn at twice the size, with a city outranking a sea of equal
  rank rather than every sea being drawn first — which is what used to leave no
  room at all for Ho Chi Minh City.
- `--rename "South China Sea=East Sea"` prints a name of the publisher's
  choosing over the map data's own. Some water has two names and which is right
  depends on the audience, so the application ships with none and takes what it
  is given.
- The legend samples the ramp at its own stops and labels each one, on a
  logarithmic scale for rainfall. It used to sample evenly and label only the
  ends, which hid four of precipitation's seven stops and left the legend
  showing colours the map no longer used.
- A bundled Natural Earth gazetteer names the seas, the countries and the
  cities, so a chart of an area Overpass cannot serve still says where it is.
- Large, legible **valid at** and **chart created** labels in a time zone the
  user chooses.

**Data**

- One built-in GRIB2 decoder, no third-party library: the NOMADS filter
  endpoints re-encode every subset as a simply packed lat/lon grid.
- Series of charts every *n* hours, from up to eight days in the past to as far
  ahead as the model reaches. Past charts come from the run of their own time,
  so their lead is short and often zero.
- Named profiles for an area and a data selection, savable from the window and
  usable from the command line with `--profile`.
- The command line can post: `--post` publishes the charts as a carousel using
  the account and caption stored by the desktop application, with `--caption`,
  `--caption-file` and `--count` to override. Everything it can check it checks
  before downloading anything, so a scheduled run that cannot succeed fails at
  the start rather than after the work.
- `--quiet` prints nothing at all, rather than nearly nothing: the same lines go
  to `~/.weathermap/weathermap.log` instead, along with the framework logging
  that used to reach stderr regardless. Failures go to the log too, so a
  crontab entry produces no mail at all; the exit status still reports them.
- `--start WHEN` begins a series at a named moment — `'tomorrow 06:00'`, the
  same expressions a caption parameter takes — rather than at now, which is what
  a chart posted in the evening about tomorrow needs.

**The application**

- FlatLaf light, dark and system themes, switchable without a restart.
- The window remembers its size, position and both divider positions.
- A series is steppable with the toolbar arrows or the arrow keys, and the
  chart on screen copies to the clipboard as an image.
- The documentation is in the application: **Help → User's Guide** (F1) and
  **Help → Developer's Guide**.

**Sharing**

- **Share → Post to Instagram…** publishes the chart on screen and the ones
  after it as a carousel, default four, in chronological order. Credentials are
  an account ID and an access token — Instagram has no supported password
  posting — and the images are written to a folder you serve publicly, because
  Meta's API fetches every image rather than accepting an upload. Proven against
  Meta's servers with a real account. See [docs/instagram.md](docs/instagram.md).
- Both of Meta's login paths are supported, and the API host follows the token
  rather than being fixed: a token from one is refused by the other, and the
  refusal reads like a bad token instead of a wrong address.
- A container is polled until Meta reports it `FINISHED` before the carousel is
  published. An ID returned is not an image fetched, and publishing early is
  refused with "Media ID is not available" — in the account's own language.
- The caption is kept with the account, so a daily post starts from the previous
  day's text. Saved before the post is attempted, so a failure does not take it.
- Captions can carry parameters: `${tomorrow:'+%A %e %B %Y'}` becomes
  "Tuesday 15 September 2026" when the post is made, so one stored caption is
  right every day. Both halves are `date(1)`'s own notation — its `--date` and
  its `+FORMAT` — implemented here rather than shelled out to, since `--date` is
  a GNU extension and packages are built for macOS and Windows too. The dialog
  previews what the caption will say and refuses to post one it cannot work out.
- A finished post says so in a dialog, with a link to it. Posting takes minutes,
  and a line in the status bar is not where anyone is looking by then.
- [Hosting the images](docs/image-hosting.md) sets up Cloudflare R2 behind an
  rclone mount, so writing a chart publishes it, with the R2 quirks that break
  a working configuration written down.

**Licensing**

- Released under the GNU General Public License, version 3 or later. The text
  ships in every package and is readable from **Help → License**.

**Packaging**

- Installable packages for Debian/Ubuntu, RHEL/CentOS/Alma, Windows and macOS,
  built from one `Makefile`.
