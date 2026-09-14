# Rendering

> The [data-structure diagram](architecture/data-structures.md) shows how
> `Grid`, `Feature`, `Layer` and `MapProjection` fit together.

The layer stack, the field treatments, and the three things drawn on top
of them: barbs, isobars and pressure centres.


```text
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
| Precipitation | Drawn as authored — the ramp carries its own weight | 0.78 |
| Cloud, humidity | A grey veil | 0.38 |

`RenderSpec.gribOpacity` (`--opacity`) scales all of them at once.

### The precipitation ramp

White where nothing is falling, then **yellow → green → blue → indigo →
purple** as it gets heavier, with brightness falling the whole way — 1.00 at the
first wet stop to 0.41 at the last — and the lightest rain also the least
saturated.

| mm | colour | brightness | saturation |
|---|---|---|---|
| 0 | transparent | — | — |
| 0.2 | pale yellow | 1.00 | 0.31 |
| 1 | gold | 0.99 | 0.71 |
| 4 | green | 0.77 | 0.63 |
| 12 | blue | 0.71 | 0.86 |
| 30 | indigo | 0.52 | 0.71 |
| 75 | purple | 0.41 | 0.90 |

The stops are weighted toward the low end because 0.5, 2 and 10 mm are what
anyone reading a forecast cares about; a linear ramp to 75 renders all of them
at the same colour.

**The lightness matters more than the hue.** The previous ramp ran green → teal
→ blue → purple with every colour between 0.40 and 0.66 brightness. It had a hue
progression and almost no lightness one, so it read as a single dark stain that
changed shade slightly — drizzle and a downpour separated only by the attribute
the eye judges worst, while the one it judges best carried nothing. The
gradient now means the map survives a greyscale print and a colour-blind reader,
neither of which is true of hue alone.

This ramp is the one field drawn **exactly as authored**. The others are toned
on the way to the map — temperature washed out, cloud greyed — and precipitation
used to have a uniform brightness scale applied too, which is precisely what
flattens a lightness gradient. Tests assert the ordering and that nothing
re-tones it.

### Naming the water

Some water has more than one name, and which one is right depends on who is
reading. The bundled gazetteer calls one body of water the South China Sea; in
Vietnam it is the **East Sea**, and a chart published there carrying the other
name is not a chart anyone will publish. The same is true of several others, in
both directions.

The application does not choose. It ships with no renames at all and prints
whatever the publisher sets, because the publisher knows their audience and is
answerable for what goes out:

```bash
java -jar weathermap.jar --cli --rename "South China Sea=East Sea" --save
```

`--save` keeps it, so a scheduled run needs no flag. It may be given more than
once, it is added to whatever is already stored rather than replacing it, and
`--rename "Name="` takes one away. Renames live in `preferences.properties` as
numbered pairs — a properties key ends at its first unescaped space, so the name
goes in the value where spaces are ordinary and the file stays editable by hand.

Nothing here touches the data. Only the text drawn over it changes, and only for
names that match exactly.

### The legend

The bar samples the ramp **at its own stops** and labels every one of them that
will fit. It used to sample at eight even intervals and label only the two ends,
which for precipitation meant four of the seven stops fell inside the first
interval: the legend drew a straight blend from transparent to the 10.7 mm
colour, so the yellow and green half never appeared and the legend disagreed
with the map beside it.

Where the stops sit is the ramp's own `legendScale`:

- **Linear** for a quantity that is linear. A temperature legend that is not
  linear in kelvin is simply wrong about where freezing falls.
- **Logarithmic** for rainfall, which is read across orders of magnitude. The
  difference between 0.2 and 4 mm matters as much as between 30 and 75, and on a
  linear bar everything below 12 mm shares a sixth of the width — all indigo and
  purple, the original complaint arrived at honestly. Positions come from
  `log1p`, which takes zero to zero without a special case, and whose implied
  one-millimetre offset is about where a rainfall scale should bend.

A derived ramp — fitted to the data, or toned — is the same quantity and keeps
the same scale. Tests check the positions are strictly increasing and span 0 to
1, because `LinearGradientPaint` throws otherwise, and that is not a bad legend
but no chart at all.

## Wind barbs

The top layer. `WindBarbLayer` draws the standard notation: the staff points
**into** the wind, a short tick is 5 knots, a long tick 10, a filled triangle 50,
and calm is an open circle. Speed is rounded to the nearest 5 before
decomposition, since the notation cannot express anything finer.

Feathers sit on the side toward low pressure, which Buys Ballot's law mirrors at
the equator — decided per station, so a chart spanning it is right on both sides.

Wind is one selectable variable that fetches `UGRD` and `VGRD` together;
selecting a single component would download data that cannot be drawn.

## Isobars

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

## Highs and lows

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
