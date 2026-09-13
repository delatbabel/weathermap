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
  Meta's API fetches every image rather than accepting an upload. See
  [docs/instagram.md](docs/instagram.md).

**Licensing**

- Released under the GNU General Public License, version 3 or later. The text
  ships in every package and is readable from **Help → License**.

**Packaging**

- Installable packages for Debian/Ubuntu, RHEL/CentOS/Alma, Windows and macOS,
  built from one `Makefile`.
