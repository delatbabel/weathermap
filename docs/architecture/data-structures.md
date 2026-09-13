# Weather Map — Data Structures Diagram

![Weather Map — Data Structures Diagram](svg/data-structures.svg)

**What describes a request**

- Profile = BoundingBox + GribSelection, nothing about rendering
- A series resolves to one ChartRequest per chart, each naming its own run

**What comes back**

- Grid is one decoded field: variable, level, valid time, and a float array
- Feature is one OSM or gazetteer thing: points plus the tags that survived

**What draws it**

- RenderSpec says which layers and how strongly
- Every Layer draws through the MapProjection, so the stack is projection-agnostic

---

Generated from `architecture` source by Archify, then exported with `tools/archify-export.py`. Edit the JSON under `docs/architecture/src/`, re-deliver, and re-export — never edit the SVG by hand.
