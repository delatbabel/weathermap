# Changes

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
