# Architecture diagrams

Four views, authored as [Archify](https://github.com/mkpaz) specifications and
exported to SVG so GitLab can render them.

| Diagram | Level | What it answers |
|---|---|---|
| [System context](c4-context.md) | C4 L1 | What the application talks to, and what it does without a network |
| [Containers](c4-container.md) | C4 L2 | The two front ends, the one pipeline, and where state lives |
| [Chart pipeline](pipeline.md) | Data flow | How an area and a selection become a PNG |
| [Data structures](data-structures.md) | Data structure | The model types and how they relate |

## How these are made

Archify renders one self-contained interactive HTML file per diagram. GitLab
shows neither JavaScript nor inline HTML, so each is exported to a standalone
SVG with its styles inlined, plus a Markdown page carrying the prose that the
viewer would otherwise hold in its cards.

```
docs/architecture/
├── src/      the Archify JSON — the only thing to edit, and the source of truth
├── html/     Archify's interactive viewer — generated, not committed
├── svg/      standalone SVG, what GitLab renders
└── *.md      one page per diagram
```

`html/` is in `.gitignore`: each viewer is about 800 KB, every one of them is
reproducible from `src/`, and GitLab renders neither their JavaScript nor their
inline styles. Regenerate them locally when you want the interactive version.

To change a diagram, edit the JSON under `src/`, then:

```bash
cd ~/.claude/skills/archify
node bin/archify.mjs validate architecture <src>.json --quality showcase --json
node bin/archify.mjs deliver  architecture <src>.json <html>.html --quality showcase --json

cd -
tools/archify-export.py docs/architecture/html/<name>.html docs/architecture
```

Never edit the SVG or the Markdown by hand — both are generated, and the next
export will overwrite them. `tools/archify-export.py` explains what it lifts out
of the HTML and why.

The interactive versions are worth generating locally: they carry search, focus,
relationship tracing and curated views that a static SVG cannot.
