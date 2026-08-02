# ISOBUS panel mockups

Renders the ISOBUS panel (issue #58) as a PNG per machine, **driven by the committed captures in
`examples/json/telemetry/`** rather than by invented data. It exists because the sandbox cannot run
the app — Gradle only runs on the host, there is no browser — so this is the only way to look at a
layout before writing Compose for it.

```bash
python3 tools/isobus-mockup/isobus_mock.py     # writes tools/isobus-mockup/mock/*.png
```

`preview.png` is the whole set as of the design review that settled the layout.

## Renderer constraints, learned the hard way

This ImageMagick has **no `rsvg-convert` delegate**, so SVG goes through its internal MSVG renderer,
which fails silently in three separate ways. Every one of these cost a render cycle:

1. **Strokes are dropped.** Only fills survive. Every outline here is a filled shape — see
   `outline()`, which draws a ring as two rectangles. (`ClusterIcons.kt` follows the same rule for the
   same reason, so shapes drawn this way port straight to Compose.)
2. **`transform` is ignored.** A `transform="rotate(...)"` element vanishes. Anything rotated must be
   emitted as literal coordinates — see the spreader vanes in `solid_spreader()`.
3. **`<text>` fails outright** ("unable to read font"), because there is no fontconfig link. Text is
   therefore *not* in the SVG at all: shapes are rasterised first, then labels are composited with
   `-annotate`, which talks to freetype directly and works. `-annotate` has no `text-anchor`, so
   centring and right-alignment measure the string first (`measure()`).

`montage` hits the same font problem when it tries to caption tiles; pass `-label ""`.

## What the layout is

Settled with the user against two real terminals — a Jaltest ISOBUS UT (sprayer) and a Strautmann
Touch 800 (manure spreader):

- the machine drawn as a **flat schematic**, with the level **overlaid on the body** in a boxed
  figure, the way both references do it;
- **working figures in the band's corners** — speed, rate, worked width, sections;
- a **footer row per material**, because a combination machine carries more than one;
- the silhouette chosen by **aspect priority** (sowing → plough → tillage → sprayer kind), never by
  the implement's `type` string, which is modder-defined and unenumerable. A fertilizing seed drill
  draws as a seed drill and names both materials.
