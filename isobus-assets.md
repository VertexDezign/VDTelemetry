# ISOBUS machine art — asset spec

What the ISOBUS panel (issue #58) draws, and the rules the app relies on. Rewritten 2026-08-02
alongside the art itself; supersedes both the own-drawn vector machines in `tools/isobus-mockup/`
(those only ever existed to settle the layout, and the layout is what survived) and the first PNG
draft of this spec.

The panel is otherwise unchanged: machine centred, level overlaid on its body, working figures in the
corners, one footer row per material.

## The art is SVG, and it is generated

The machines live in `tools/isobus-art/` as **Python that emits SVG**, not as hand-drawn files. Run
`python3 tools/isobus-art/slurry.py && python3 tools/isobus-art/machines.py` to regenerate, then
`manifest.py` to re-measure the anchors. Editing a machine means editing ~40 lines of geometry.

Two things fall out of that which are worth keeping:

- **Body and fill cannot drift apart.** Both layers come out of one geometry pass with a `layer`
  flag; the cavity path is literally the same numbers in both files. There is no separate step that
  could misalign them.
- **The preview is not the deliverable.** This sandbox has no SVG rasteriser — ImageMagick's internal
  SVG reader silently drops strokes and transforms — so `kit.py` also emits ImageMagick MVG and
  renders a PNG next to each SVG for review. Both backends read the same op list, so the preview
  cannot diverge from the shipped file. **The PNGs in `out/` are review artefacts. Do not ship them.**

Compose Resources on 1.11.1 generates `Res.drawable.<name>` for `.svg` (verified against this build),
so the app loads them exactly like the old `mb_trac.png`. Whole set is ~110 KB, against ~1.5 MB for
the PNG draft, so the old ≤150 KB-per-file budget no longer binds.

> **Not yet verified:** that the SVGs *decode* at runtime in the wasm app. Accessor generation is
> confirmed; nothing has been rendered in a browser. Check before building the panel against them.

## Three layers, not one image

The machine has live state, and a slurry rig has interchangeable kit on the back. Three separate
things compose:

| Layer | What | Where it comes from |
| --- | --- | --- |
| machine | the implement itself | art — `_body` plus optional `_fill` |
| attachment | what a slurry tanker is pulling behind it | art, optional |
| sections | working width, split into blocks | **the app** — no art at all |

### Fill level → two layers

A tank empties as you work, so it cannot be baked in. Each machine that carries material ships a
**body** (tank empty) and a **fill** (the material shape alone). The app draws the body, then the
fill clipped bottom-up to the level.

Every vessel keeps a **generous margin of bodywork around its cavity** — a walkway spine and a skirt
on the tanker, ribs on the sprayer, a rim and a lower band elsewhere. The fill covers the cavity
exactly, so anything drawn inside it disappears at 100%, and the machine still has to read as that
machine with a full tank.

### The attachment is a separate image on the same canvas

The tanker draws alone. The attachment is its own file, drawn at the position where it hangs off the
tanker's rear, so the app overlays it with **no positioning maths** — the same trick that keeps the
fill registered.

Which attachment comes from the telemetry, not from the art:

```
Kaweco Profi II  (manureBarrel)   spraying.kind=SLURRY_TANKER   ← the tank
  └ Bomech Multi Profi 21/15      spraying.externalSource=true  ← dribble bar
Vredo VT5536     (self-propelled barrel)
  └ SKY Methys HDS                spraying.externalSource=true  ← injector
                                  tillage.kind=CULTIVATOR
```

The child implement with `spraying.externalSource == true` **is** the attachment. If it also carries
`tillage`, it is an injector; otherwise a dribble bar. **No such child means the bar is built into
the tanker and invisible to us — draw nothing.** That case is normal, not an error.

### Sections are drawn, never baked

Section count varies per machine (9 on the Rogator, 10 on the AgriSpread), so the app draws them as a
**plan-view strip below the machine**: you are looking down at the working width, divided into
blocks. This replaces the old `boomRect` anchor, which is gone — there is no longer any machine whose
art carries its sections. The rear-view sprayer's boom sits directly above where the strip lands, so
the two read together rather than competing.

## One canvas, one scale

**Every image is 1600 × 1000**, every machine drawn to the same scale on a common ground line, not
scaled to fill its own canvas. A 22 000 l tanker must look bigger than a 5-furrow plough, because the
panel switches between them in place.

- ground line at **y = 880** — machines with wheels stand on it; mounted implements hang above it at
  working height, and tillage points reach it
- keep a **60 px** margin left and right
- draw **side-on, facing left**, hitch/drawbar at the **left edge** — matching `mb_trac.png`, which
  the Lighting panel already uses and which the cluster glyphs were mirrored to match
- exception: **sprayer and solid spreader are drawn from behind**, following both reference terminals

A horizontal spinning disc seen side-on or from behind is **edge-on** — a flattened ellipse with its
vanes standing up off the plate (`kit.spinner`). Drawn face-on as a circle it reads as a wheel, which
is the one thing a spreader disc must not look like.

The rear-view sprayer carries **no running gear**. From behind, a boom-mounted tank shows no wheels,
and inventing them read as wrong.

## Style

Match `mb_trac.png` — the house style, already on screen in the Lighting panel. The palette in
`kit.py` is **sampled out of that file**, not invented:

| | | |
| --- | --- | --- |
| `#1B1E1B` outline | `#CFCC50` bodywork | `#7D7F4B` secondary |
| `#49483D` tyre | `#4A4942` frame | `#CAAF39` wheel rim |
| `#6E6E60` steel wear parts | `#C9C8B6` empty vessel | `#BBD9D6` glass |

Materials: `#8A6A3C` slurry, `#7A5636` muck, `#BFA164` granules, `#5A8AA6` liquid, `#B07C2E` seed —
each kept clear of `#CFCC50`, or the fill reads as bodywork.

Flat vector, heavy dark outline, no gradients, no shading, no perspective. Transparent background, no
ground shadow (a baked shadow breaks on the black display-mode root). Must read on both a light panel
(`#F0F0F2`) and pure black.

## Do not include

- no fill level or material in the tank on the body layer — that is the fill layer's job
- no text, numbers, units or badges — every figure is drawn by the app
- no section blocks or spray plumes — the app draws those from live data
- no tractor — the panel is about the implement
- no background, frame, ground, or drop shadow

## Files

In `VDTerminal/app/src/commonMain/composeResources/drawable/`. Compose generates
`Res.drawable.<name>` from the filename, so the names are load-bearing.

| File | Notes |
| --- | --- |
| `isobus_slurry_tanker_body/_fill.svg` | barrel, tandem axle, ladder, hitch eye — no bar |
| `isobus_att_dribble_bar.svg` | distributor drum, hose bundle, trailing shoes |
| `isobus_att_injector.svg` | short toolbar of disc coulters |
| `isobus_manure_spreader_body/_fill.svg` | box body, vertical beaters, edge-on spinners |
| `isobus_solid_fertilizer_body/_fill.svg` | **rear view** — hopper onto twin edge-on spinners |
| `isobus_sprayer_body/_fill.svg` | **rear view** — tank over the boom truss, no wheels |
| `isobus_seed_drill_body/_fill.svg` | hopper on a frame with raked disc coulters |
| `isobus_plough_left.svg` / `_right.svg` | bodies turned each way — no fill layer |
| `isobus_cultivator.svg` | raked tines + packer discs — no fill layer |
| `isobus_power_harrow.svg` | rotor housing, tine pairs, packer roller |
| `isobus_subsoiler.svg` | five heavy legs, depth wheel |

`LIQUID_FERTILIZER` and `SPRAYER` share the sprayer art — one is a tank of fertilizer and the other a
tank of herbicide, and the machine is the same.

## The manifest

`tools/isobus-art/manifest.py` reads each fill layer's **alpha bounding box** and writes
`out/MachineArt.kt.snippet`. The anchors are therefore measured off the delivered images, and moving
a tank in the geometry moves them with it — they are never hand-transcribed.

| Anchor | Meaning |
| --- | --- |
| `fillClipTop` | y fraction the fill reaches at 100%, so a part-full tank clips to the tank |
| `fillClipBottom` | y fraction of the tank floor — the other end of that clip |
| `readoutAt` | fractional point for the boxed level figure, the cavity's centre |

The snippet is ready to paste once `IsoBusPanel` exists; it is deliberately not a source file yet,
because nothing references it.

## Sequencing

1. ~~Generate the art.~~ Done — `tools/isobus-art/`.
2. ~~Drop the files into `composeResources/drawable/`; check they load.~~ Accessors verified;
   **runtime decode still unverified.**
3. ~~Measure the anchors.~~ Done — `manifest.py`, regenerated with the art.
4. Build `IsoBusPanel` against them, plus the plan-view section strip. The layout, data mapping and
   footer are already settled by `tools/isobus-mockup/`, so this is composition rather than design.
