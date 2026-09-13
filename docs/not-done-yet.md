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
| Antimeridian | `BoundingBox` | Refused rather than split. Supporting it means two boxes and compositing the halves. |
| Download progress is end-only | `Http.download` | `BodyHandlers.ofFile` gives no intermediate callbacks; needs a counting `BodySubscriber`. |
| Variable/level availability is only partly validated | `GribCatalog.STANDARD_LEVELS` | The common levels are known and an impossible pair is now repaired by the CLI and queried by the UI, but the full set is only discoverable from each model's filter form, so an unknown pairing is still allowed through and fails at download time. |
| Isoline segments are not stitched into paths | `IsolineLayer` | Marching squares emits unordered segments, which draw correctly and label adequately. Stitching would buy smoothing and a gap in the line under each label. |
| A marine label point can land on land | `tools/make-gazetteer.py` | The centroid-then-chord rule holds for convex and crescent shapes; a many-armed one such as "Inner Seas off the West Coast of Scotland" can still put its name over an island. A pole of inaccessibility would fix it. |
| Cache eviction is never called | `Cache.evictTo` | Implemented but unwired; needs a configured budget. |
| Elevation | — | Left out by decision. OSM has `ele` tags on peaks but no terrain model; shaded relief needs SRTM or Copernicus DEM as a separate source. |
