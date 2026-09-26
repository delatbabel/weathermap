# What is not done yet


Ordered by how much they matter.

| Gap | Where | Notes |
|---|---|---|
| Barb station spacing is a fixed pixel grid | `WindBarbLayer` | Never finer than the data, but it does not thin toward the poles under Mercator, where stations crowd together in latitude. |
| Land/sea fill is flat | `VectorLayers.LandSeaLayer` | Deriving land needs the OSM convention that land is left of a coastline way, plus stitching open segments into closed rings against the box edges. Today the coastline stroke does the work, which reads correctly for a coastal box and wrongly for an inland one. |
| `NetcdfGribReader` | `grib/netcdf/` | Stub. |
| Accumulated fields are labelled with the start of their period | `Grib2Scanner.validTime` | Product template 4.8 stores the interval end further into the template; an `APCP` message for f006 still reports 00Z. `Compositor.validTimeOf` works around it for the chart caption by taking the latest time across the fields, but the `Grid` itself is still wrong. |
| Overpass queries are not tiled | `OverpassClient` | A very large box exceeds the server limit. Splitting needs coastline segments stitched across tile seams. |
| OSM relations are ignored | `OsmXmlParser` | Multipolygon boundaries render as their member ways — right for strokes, wrong for fills. |
| Download progress is end-only | `Http.download` | `BodyHandlers.ofFile` gives no intermediate callbacks; needs a counting `BodySubscriber`. |
| Variable/level availability is only partly validated | `GribCatalog.STANDARD_LEVELS` | The common levels are known and an impossible pair is now repaired by the CLI and queried by the UI, but the full set is only discoverable from each model's filter form, so an unknown pairing is still allowed through and fails at download time. |
| Pressure-centre search radius is in pixels | `IsolineLayer.CENTRE_RADIUS_PX` | So the test means about a degree on a chart of Britain and five and a half on one of the Southern Ocean, and the same system can be marked at one zoom and not another. A geographic radius was tried: it fixes a genuine miss and loses a different centre, so it is a trade rather than an improvement and wants a proper survey across chart scales. |
| A post cannot be scheduled or repeated | `instagram/` | Publishing works end to end against Meta, but every post is a button press. A daily chart at a fixed hour needs the CLI to publish, which it cannot yet do — only the window can post. |
| A failed carousel leaves its containers behind | `InstagramClient` | If the carousel is rejected after some image containers have been created, those containers stay on Meta's side until they expire in 24 hours. They are harmless and uncountable against the daily quota, but they are litter. |
| Packages are not signed | `dist/` | An unknown-publisher warning on Windows, and a right-click-to-open on macOS, until they are signed and notarised. See [Packaging](packaging.md). |
| Isoline segments are not stitched into paths | `IsolineLayer` | Marching squares emits unordered segments, which draw correctly and label adequately. Stitching would buy smoothing and a gap in the line under each label. |
| A marine label point can land on land | `tools/make-gazetteer.py` | The centroid-then-chord rule holds for convex and crescent shapes; a many-armed one such as "Inner Seas off the West Coast of Scotland" can still put its name over an island. A pole of inaccessibility would fix it. |
| A tide chart is one point at a time | `tide/` | No station picker and no way to compare two places side by side. The nearest-station answer is taken as given, so a point with a better gauge just out of reach cannot be nudged towards it except by moving the coordinates by hand. |
| Tides are not in the command-line tool | `cli/` | Only the window draws them, so a daily tide chart cannot be produced unattended the way a weather chart can. |
| Tide requests are not counted locally | `StormglassClient` | The quota shown is whatever the last response said. Nothing warns before the second-to-last request, and a cached day reports a count that may be from yesterday. |
| Elevation | — | Left out by decision. OSM has `ele` tags on peaks but no terrain model; shaded relief needs SRTM or Copernicus DEM as a separate source. |
