# ISOBUS machine art — asset spec

What to generate for the ISOBUS panel (issue #58), and the rules the app relies on. Written
2026-08-02 after the design review settled the layout; supersedes the own-drawn vector machines in
`tools/isobus-mockup/` (those were only ever there to settle the layout, and the layout is what
survived).

The panel is otherwise unchanged: machine centred, level overlaid on its body, working figures in the
corners, one footer row per material. Only the machine itself becomes a PNG.

## Why the app can't just use one image per machine

The machine has live state. Two decisions, both taken with the user:

- **Fill level → two layers.** A tank empties as you work, so it cannot be baked in. Each machine
  that carries material ships **a body** (tank empty) and **a fill** (the material shape alone). The
  app draws the body, then the fill clipped bottom-up to the level. This is why it beats a plain
  rectangle: a round barrel and a tapered hopper get their true shape for free.
- **Discrete state → baked where it is genuinely discrete.** The plough ships **two** images, one per
  turned side. Boom sections do **not**: the count varies per machine (9 on the Rogator, 10 on the
  AgriSpread), so the app draws section blocks and spray over the boom in the art.

## One canvas, one scale

**Every image is 1600 × 1000 px**, and every machine is drawn **to the same scale on a common ground
line**, not scaled to fill its own canvas. A 22 000 l tanker must look bigger than a 5-furrow plough,
because the panel switches between them in place and a per-image fit would make them jump around.

- ground line at **y = 880** (machines stand on it; wheels touch it)
- keep a **60 px** margin left and right of the widest machine
- draw **side-on, facing left**, hitch/drawbar at the **left edge** — matching `mb_trac.png`, which
  the Lighting panel already uses and which the cluster glyphs were mirrored to match
- exception: **sprayer and solid spreader are drawn from behind** (boom spread across the frame,
  discs below the hopper), because that is the only view where sections and spread pattern mean
  anything — this follows both reference terminals

## Style

Match `VDTerminal/app/src/commonMain/composeResources/drawable/mb_trac.png` — that is the house
style and it is already on screen in the Lighting panel:

- flat vector illustration, **heavy dark outline** (`#2B2B2B`-ish), no gradients, no shading, no
  perspective
- muted fills; greens/yellows for bodywork, dark grey for tyres and frame
- **transparent background**, no ground shadow (a baked shadow breaks on the black display-mode root)
- must read on **both** a light panel (`#F0F0F2`) and pure black — so nothing relies on being white,
  and outlines stay dark rather than becoming the only value

## Do not include

- **no fill level, no material in the tank** on the body layer — that is the fill layer's job
- **no text, numbers, units or badges** — every figure is drawn by the app and would be unreadable
  baked at panel size anyway
- **no boom section blocks or spray plumes** — the app draws those from live data
- **no tractor** — the panel is about the implement; the tractor is the Lighting panel's subject
- no background, frame, ground, or drop shadow

## Files

Lowercase with underscores, in `VDTerminal/app/src/commonMain/composeResources/drawable/`. Compose
generates `Res.drawable.<name>` from the filename, so the names are load-bearing.

| File | Notes |
| --- | --- |
| `isobus_slurry_tanker_body.png` | barrel + tandem axle + dribble bar, tank empty |
| `isobus_slurry_tanker_fill.png` | the liquid inside the barrel, alone |
| `isobus_manure_spreader_body.png` | box body, drawbar, vertical beaters + discs at the rear |
| `isobus_manure_spreader_fill.png` | the muck heap in the body, alone |
| `isobus_solid_fertilizer_body.png` | **rear view** — hopper narrowing onto twin discs, hopper empty |
| `isobus_solid_fertilizer_fill.png` | the granules in the hopper, alone |
| `isobus_sprayer_body.png` | **rear view** — tank over the boom centre, boom truss included, tank empty |
| `isobus_sprayer_fill.png` | the liquid in the tank, alone |
| `isobus_seed_drill_body.png` | hopper on a frame with a row of disc coulters, hopper empty |
| `isobus_seed_drill_fill.png` | the seed in the hopper, alone |
| `isobus_plough_left.png` | bodies turned left — no fill layer |
| `isobus_plough_right.png` | bodies turned right — no fill layer |
| `isobus_cultivator.png` | tine ranks + packer discs — no fill layer |

Optional, and the app falls back to `isobus_cultivator.png` until they exist:
`isobus_power_harrow.png`, `isobus_subsoiler.png`.

`LIQUID_FERTILIZER` and `SPRAYER` share the sprayer art — one is a tank of fertilizer and the other a
tank of herbicide, and the machine is the same.

## The fill layer has to register exactly

The two layers are composited at the same size and offset, so **they must share the canvas and the
registration**. The reliable way to get that is not to draw the fill twice:

1. draw the machine with the tank **full**;
2. save that as the fill layer, erasing everything that is not the material;
3. erase the material from the original and save that as the body.

Then any misalignment is impossible by construction. If they are generated independently they will
drift by a few pixels and the level will look wrong at the tank edges.

The fill layer may be a flat silhouette — it is drawn behind the body's outline, so only its shape
matters, not its detail.

## Weight

The wasm bundle ships every one of these. **≤150 KB each after optimisation** (`pngquant --quality
65-85`, then `oxipng -o4`); flat illustration with few colours compresses hard, so this is not tight.

For scale: `mb_trac.png` is currently **1.9 MB** for one flat illustration, which is roughly ten times
what it needs to be — worth running through the same optimisation while we are here.

## The manifest the app needs

The app cannot guess where the boom is inside the image, so each machine carries a few **fractional**
anchors (0–1 of image width/height) in Kotlin beside the drawable reference:

| Anchor | For | Meaning |
| --- | --- | --- |
| `boomRect` | sprayer, seed drill, slurry tanker | where section blocks and spray are drawn, over the boom/bar in the art |
| `readoutAt` | machines with a level | fractional point for the boxed level figure, on the body |
| `fillClipTop` | machines with a level | the y fraction the fill layer reaches when 100% full, so a part-full tank clips against the tank rather than the canvas |

These get filled in once the art exists — they are measured off the delivered images, not guessed
ahead of them.

## Prompt sketch

A starting point per machine; the constant half is what keeps the set looking like one system.

> Flat vector illustration of a **\<machine\>**, side view facing left, drawn in a simple icon style
> with a heavy dark outline and flat muted colours — no gradients, no shading, no perspective, no
> background. Transparent background. Farm machinery in muted green and yellow with dark grey tyres
> and frame. The whole machine sits on an invisible ground line with its wheels touching it. No text,
> no numbers, no logos, no shadow.

Swap "side view facing left" for "rear view, symmetrical, seen from directly behind" on the sprayer
and the solid spreader.

## Sequencing

1. The user generates the art in separate sessions, against this spec.
2. Drop the files into `composeResources/drawable/`; optimise; check they load.
3. Measure the anchors off the real images and fill in the Kotlin manifest.
4. Build `IsoBusPanel` against them — the layout, the data mapping and the footer are already settled
   by `tools/isobus-mockup/`, so this is composition rather than design.

Until the art lands, the panel can be built and reviewed against the mockup's own shapes; the image
swap is a one-line change per machine if the manifest is in place first.
