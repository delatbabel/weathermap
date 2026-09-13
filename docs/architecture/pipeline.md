# Weather Map — Chart Pipeline Diagram

![Weather Map — Chart Pipeline Diagram](svg/pipeline.svg)

**One renderer**

- The image written to disk is the image on screen
- The CLI and the window differ only in what they do with it

**Fail-soft at every fetch**

- No Overpass answer falls back to the bundled outline
- A run that has rolled off the archive is skipped, not fatal
- A variable that decoded nothing is reported, not silent

**Each chart names its own run**

- Future charts come from the newest run at a long lead
- Past charts come from the run of their own time, often an analysis

---

Generated from `dataflow` source by Archify, then exported with `tools/archify-export.py`. Edit the JSON under `docs/architecture/src/`, re-deliver, and re-export — never edit the SVG by hand.
