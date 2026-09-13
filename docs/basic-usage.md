# Basic usage

The window has a map on the left, the controls on the right, a toolbar across
the top and a status line along the bottom. The whole job is: choose an area,
choose what to fetch, download, read the chart.

## 1. Choose an area

The map starts on the last area you used, or a default box the first time.

| To do this | Do that |
|---|---|
| Pan | Drag, or right-drag while in *Select area* mode |
| Zoom | Wheel, or double-click to zoom in |
| Select an area | Shift-drag, or press **Select area** and drag |
| Type an area | Fill in North / South / West / East and press **Apply** |
| Frame the selection | **Zoom to area** |
| Fetch detail now | **Load detail** |

The selection map shows the base map only — never the weather — because a
translucent field over a coastline makes choosing an area harder, not easier.

Below about 8° of view the application fetches OpenStreetMap coastline and place
names for what you are looking at. Above that it draws a bundled world outline,
which is coarse but instant and never blank.

## 2. Choose what to fetch

On the right, under **GRIB data**:

- **Model** — GFS 0.25° is the safe choice. NAM and HRRR need the optional
  NetCDF-Java build.
- **Forecast hours** — `0,6,12-24`; ranges work. Ignored while a series is set.
- **Series** — a chart every *n* hours, from so many hours back to so many
  ahead. See [Series and profiles](series-and-profiles.md).
- **Variables** and **Levels** — Ctrl-click for more than one.

**Not every variable exists at every level.** Wind is published at 10 m,
precipitation at the surface, temperature at 2 m. Choosing a combination that
cannot work is caught before the download: the application offers to add the
levels the selection actually needs, because NOMADS filters every variable by
the same set of levels and one impossible pairing empties the whole request.

Accumulated precipitation does not exist at forecast hour 0 — nothing has
accumulated yet — and the application says so rather than rendering a chart that
silently has no rain on it.

## 3. Download and read

**Download and composite** fetches and draws. Progress runs along the bottom;
**Cancel** stops it.

Charts land in `~/.weathermap/maps` — **File → Set output folder…** to change it.

What you get, bottom to top: land and sea, the scalar fields as colour, the
graticule, coastline, boundaries, place names, isobars, and **wind barbs on
top**. The chart is read for the wind, so nothing is allowed to bury it.

Reading a barb: the staff points *into* the wind — the direction it is coming
from. A short tick is 5 knots, a long tick 10, a filled triangle 50, and they
add up. Calm is an open circle.

Isobars are the brown lines, every 4 hPa, with **H** in blue and **L** in red on
the pressure centres. No H or L means no centre lies inside the chart — the
application logs that rather than leaving you guessing.

## 4. Step through a series

With more than one chart, **◀** and **▶** on the toolbar move through them one at
a time, as do the left and right arrow keys while the map has focus. The counter
between them shows where you are; the status line names the valid time.

**Copy** — or Ctrl-Shift-C — puts the chart on screen on the clipboard as an
image, ready to paste into a message.

## If something goes wrong

| What you see | What it means |
|---|---|
| *Map detail unavailable* | Overpass did not answer. The chart still renders from the bundled outline. |
| *matched no GRIB records* | The variable and level combination does not exist for that model. |
| *No data for …* | The field is genuinely absent — commonly precipitation at hour 0. |
| *Area wider than 20°* | Too large for Overpass; the bundled world map is used instead. |
| *… charts skipped* | Their model runs have aged out of NOMADS' ten-day archive. |

## Post a series to Instagram

**Share → Post to Instagram…** publishes the chart on screen and the ones after
it as a carousel — four by default, up to ten.

The setup is not what you would expect, and it is worth reading
[Posting to Instagram](instagram.md) once before the first attempt: Instagram
has no password-based posting, so the credentials are an account ID and an
access token, and the API fetches every image from a public URL rather than
accepting an upload — so the application needs a folder it can write that you
also serve over HTTP.

## Help without leaving the window

**Help → User's Guide** (F1) is this page and the ones beside it; **Help →
Developer's Guide** is the rest of the documentation, including the architecture
diagrams. Both are the same Markdown the repository holds, so neither can go
stale against the other. **Help → License** shows the full GPL text, and
**Help → About** names the application, its licence and the data it draws on.

## The command line

The same jar, with `--cli`, repeats your last selection — or a saved profile —
against the newest model run. That is what belongs in a crontab. See
[Build and run](build-and-run.md).
