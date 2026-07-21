# Ground-layer overlay — follow-ups (per-layer files + layer visibility)

Two deferred improvements to the `mapLayers` ground overlay, written 2026-07-20 on branch
`map-layers-revive` for a later session. **Status: proposed, not started.**

## Where this stands (recap)

The overlay is three raster planes — **crops**, **growth**, **soil** — grid-sampled at `GRID_SIZE`
(512²). The mod (`vdTelemetry/src/collect/MapLayersExporter.lua`) writes one file, `mapLayers.json`
(~**1.3 MB**, minified single line), holding all three planes as arrays of right-trimmed hex-string
rows (2 hex chars per cell, `""` for an all-zero/off-field row). The server
(`MapLayerRenderer.kt` / `Server.kt`) decodes rows + legend into a PNG per layer; **only legends cross
the WebSocket** (`MapLayersInfo`), the app fetches the raster as a PNG from `/api/map-layer/{id}?v=…`,
and the version is `MapLayersData.hashCode()` (content-derived, so any change refetches). The app
shows **one layer at a time** (single-select in the map filter popover).

Cadence: full sweep on `PERIOD_CHANGED` / `DAY_CHANGED`; between sweeps, cells around active vehicles
are re-sampled every 4 s and patched in place. Recent perf work on this branch:
`8786cd1` per-cell memo, `dc9049d` off-field skip + event cadence, `ef7d158` vehicle patching,
`6fb7819` PF gating, `c726a01` skip-unchanged patch writes + faster `encodeString`.

**The remaining pain:** during *active farming*, cells change every patch, so `c726a01`'s
skip-unchanged doesn't help — the mod re-encodes and rewrites the full 1.3 MB every 4 s, which shows up
as `Json.lua` high in the in-game script profiler.

---

## 1. Split the raster into per-layer files

Write **three files** (e.g. `mapLayersCrops.json` / `…Growth.json` / `…Soil.json`, flat like the
telemetry dir — or a `mapLayers/` subdir) instead of one, and track dirty **per layer** so a patch
only rewrites the layers that actually changed.

### Why it helps

A field operation only touches some of the three planes:

| Operation | crops | growth | soil |
|-----------|:-----:|:------:|:----:|
| Cultivate |   –   |   ✎    |  ✎   |
| Plow      |   –   |   ✎    |  ✎   |
| Fertilize |   –   |   –    |  ✎   |
| Sow       |   ✎   |   ✎    |  –   |
| Harvest   |   ✎   |   ✎    |  –   |

So per operation you rewrite ~1–2 of 3 files instead of all three (~⅓–⅔ mod-side saving during active
work). The **crops** layer — likely the largest — changes *only* on sow/harvest, so every
cultivate/plow/fertilize/roll pass stops rewriting it.

Second, free benefit: the app shows one layer at a time, but today *any* layer change bumps the
whole-file hash and the app refetches its displayed layer even when that layer didn't change.
**Per-file versions** fix that — refetch only when the displayed layer actually changed.

### Scope (full-stack, moderate)

- **mod**: three files; per-layer dirty (patch marks only changed layers; a full sweep marks all).
  `finishSweep` / `runPatch` in `MapLayersExporter.lua`.
- **shared** (`Model.kt` / `VdtParser.kt`): a per-layer data shape + parse.
- **server** (`Server.kt` / `MapLayerRenderer.kt`): watch three files, render/broadcast per layer.
- **app**: map layer id → file (the `/api/map-layer/{id}` fetch is already per-layer).
- **fixtures** (`examples/json/mapLayers/…`) + tests on both sides.

### Design note

Keep the protocol simple: still send a **single** `MapLayers` broadcast, but give each layer its **own**
version (hash of just that layer) and legend — per-layer refetch without three message types.

### First, though

`c726a01` just landed; measure the profiler in-game before committing to the split. If work happens in
short bursts between driving, it may already be tolerable. If active farming still pins `Json.lua`, do
the split.

---

## 2. Show / hide individual layers in the app

Want: toggle sub-values within a layer — e.g. hide **weeds** but keep **needs-plowing** — not just the
existing whole-layer single-select.

Key architecture fact: **the app has the legends but not the raster cell values** (those live only in
the server-rendered PNG). So filtering isn't purely "kotlin-only" today — it needs either a server
render-filter (app passes the enabled legend values, server renders the rest transparent) **or** a
switch to client-side rendering (raster rows reach the app, which draws + filters the bitmap itself).
Client-render is a bigger shift away from the deliberate "PNG server-side, legends-only over WS" design.

### crops / growth — feasible

Each cell holds exactly one value, and each value has a distinct legend color. Hiding a value = those
cells transparent. Cleanest path: **server render-filter** — `/api/map-layer/{id}?hide=…` (or send the
enabled set), no data-model change. Moderate (server + app), not literally app-only.

### soil — the priority-collapse problem

`classifySoil` returns **one** value per cell by priority (weeds > stones > needs-plow > needs-lime >
fertilized), mirroring the game. So a cell that is *both* weedy and needs plowing stores only "weeds" —
the "needs-plow" underneath was **never captured**. Hiding weeds there can't reveal plow; the data
isn't there. Independent soil sub-toggles therefore need the soil data **de-collapsed**:

- **(a) Skip it** — soil stays single-value; only crops/growth get sub-toggles. Cheapest.
- **(b) Promote soil sub-states to their own layers** (weeds / stones / needs-plow / needs-lime /
  fertilized), each an independent single-value raster classified without the priority collapse. The
  app can then toggle/stack them. This is the "correct" version and **pairs naturally with the
  file-split above**, but multiplies soil planes (they're sparse — weeds/stones/lime often mostly
  empty — so the size hit may be modest). Bigger change: mod classification, wire model, render
  stacking order in the app.
- **(c) App-driven classification filter** — app tells the mod which soil sub-states to consider;
  `classifySoil` skips disabled ones and re-sweeps. Rejected: it's global (not per-viewer) and needs
  app→mod signaling + a re-sweep per toggle.

### Recommendation

- **crops / growth sub-toggles** via a server render-filter — a nice, self-contained enhancement; do it
  if the value is there. Consider.
- **soil independent toggles** — real data-model work (option b). Do it **only if** independent soil
  visibility is actually wanted; otherwise skip. If pursued, fold the soil-sub-rasters into the
  file-split (§1) so it's one coherent re-model of the layer set rather than two passes.

---

## Suggested sequencing

1. Measure `c726a01` in-game (profiler). If still heavy →
2. §1 per-layer file split (biggest, most certain win for the write cost).
3. §2 crops/growth sub-toggles (server render-filter) — optional polish.
4. §2 soil sub-layers (option b) — only if independent soil visibility is wanted; combine with §1's
   re-model.
