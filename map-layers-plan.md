# Ground-layer overlay — follow-ups (per-layer files + layer visibility)

Two deferred improvements to the `mapLayers` ground overlay, written 2026-07-20 on branch
`map-layers-revive` for a later session. Both become load-bearing if the Precision Farming layers are
ever exported — see "Why this matters more once Precision Farming lands" below.

**Status (2026-07-25, branch `map-layers-split`):**

- §1 per-layer file split — **done and working in-game**, together with the "sweep only what's
  subscribed" item that was filed alongside it. See "How it landed" below.
- §2 show/hide individual sub-values within a layer — **declined for now** (2026-07-25); kept below as
  the record of what it would cost, including the soil priority-collapse problem that gates it.

## Where this stands (recap)

The overlay is three raster planes — **crops**, **growth**, **soil** — grid-sampled at `GRID_SIZE`
(512²). *(Before the split: the mod wrote one file, `mapLayers.json`, ~**1.3 MB** minified, holding
all three planes.)* Each plane is an array of right-trimmed hex-string rows (2 hex chars per cell,
`""` for an all-zero/off-field row). The server (`MapLayerRenderer.kt` / `Server.kt`) decodes rows +
legend into a PNG per layer; **only legends cross the WebSocket** (`MapLayersInfo`), the app fetches
the raster as a PNG from `/api/map-layer/{id}?v=…`, and the version is `contentVersion` (64-bit
FNV-1a, memoized per instance — content-derived, so any change refetches). The app shows **one layer
at a time** (single-select in the map filter popover).

Cadence: full sweep on `PERIOD_CHANGED` / `DAY_CHANGED`; between sweeps, cells around active vehicles
are re-sampled every 4 s and patched in place; in multiplayer only, a stratified staleness audit
(256 cells / 10 s idle) arms a resweep when the world stops matching the model. Recent perf work on
this branch: `8786cd1` per-cell memo, `dc9049d` off-field skip + event cadence, `ef7d158` vehicle
patching, `6fb7819` PF gating, `c726a01` skip-unchanged patch writes + faster `encodeString`.

**The remaining pain:** during *active farming*, cells change every patch, so `c726a01`'s
skip-unchanged doesn't help — the mod re-encodes and rewrites the full 1.3 MB every 4 s, which shows up
as `Json.lua` high in the in-game script profiler.

**Validated:** ~3 h on a solo dedicated server (2026-07-24) with acceptable frame cost at the current
three layers.

### Why this matters more once Precision Farming lands

PF would add roughly **five more planes** (its soil/nutrient value maps), taking the channel from 3 to
~8 layers. Nothing in the current shape scales to that:

| | today (3 layers) | with PF (~8 layers) |
|---|---|---|
| file size | ~1.3 MB | **~3.5 MB**, one write |
| `encodeRow` + compare per patch | 3 × touched rows | 8 × touched rows |
| density reads per cell | ground + fruit + weed/stone/plow/lime/spray | **+5 PF map reads** |
| sweep cost | the current budget | ~1.5–2× per cell, same cell count |

The per-layer split below stops being an optimisation and becomes the precondition: at 8 layers a
single-file rewrite on every patch is not viable, and neither is sweeping planes the app isn't showing.
So when PF layers are picked up, do **1** first, and add a third item alongside it:

- **Sweep only the layers something is subscribed to.** The app shows one layer at a time; with 8
  planes, classifying and encoding the other 7 every sweep is most of the cost. Needs the app to tell
  the server which layer is selected and the server to tell the mod (the command channel already goes
  that direction), with the caveat that switching layers then costs a sweep before the raster appears
  — probably "sweep the selected layer eagerly, the rest lazily" rather than a hard filter.

PF layer support was its own piece of work, taken up right after the split on this same branch and
**done** (2026-07-25) — see "How it landed" below. This section is kept as written, because it is the
reasoning that made the split a precondition rather than an optimisation, and the estimate above is
worth comparing against what the planes actually cost.

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

### How it landed (2026-07-25)

Five commits on `map-layers-split`, mod first:

1. **`mapLayers/<plane>.json` per plane, plus `mapLayers/index.json`** (the catalogue: which planes
   this map offers, with labels from the game's own overlay selector). Each plane is its own export
   channel with its own dirty flag, so a patch republishes only the planes whose rows actually moved —
   fertilizing rewrites soil alone. The per-plane channels are `hidden` (a new `ExportChannels` flag)
   so the settings UI still offers "mapLayers" once and they follow its toggle via `isEnabled()`;
   `subDirs()` reports the folders registered channels name, so the entry point creates
   `telemetry/mapLayers/` without knowing which channel wanted it.
2. **Subscription gating.** The mod sweeps, patches and audits only the planes the terminal says it is
   showing (the absolute `setMapLayers` command). An unsubscribed plane costs nothing — not even its
   engine reads: crops stops at the fruit plane, growth's second density read is skipped, and soil's
   weed/stone/plow/lime/spray reads only happen when soil is wanted. **Nothing subscribed = no sweep
   at all.** Subscribing arms a resweep at once, and a dropped plane keeps its last file, so switching
   back paints immediately and the fresh raster lands a few seconds later.
3. **Kotlin model + server.** `MapLayerData` is one plane's file, `MapLayersCatalog` is `index.json`,
   and `MapLayersInfo` combines them — so the app is offered every plane, including unswept ones (null
   version). Each plane versions independently, which is the half the user sees: the overlay on screen
   refetches only when *it* changed. `TelemetryWatcher.registerRest()` takes the whole `mapLayers/`
   directory as a keyed map, so **adding a PF plane needs no server change at all**.
4. **App + server subscription wiring.** The map panel reports what it shows, the server unions that
   across connected dashboards (`MapLayerSubscriptions`) and writes the union to `commands.xml`.
   `SetMapLayers` is the first session-scoped client message: held per WebSocket session, dropped when
   the socket closes. Handled: reconnects (the repository restates it at the top of each session) and
   two map widgets on one page (the app reports the union over live panels).
5. **Reconciliation, because the command channel is lossy.** The mod deletes `commands.xml` at every
   map load, so a subscription sent while the game was at a menu or loading is thrown away unread —
   and since the dashboards' desire never changed, a publish-on-change server would never say it
   again. The overlay simply never appeared. So the mod reports what it is actually sweeping
   (`active` per entry in `index.json`) and the server compares that against its union on every
   catalogue write, restating the command when they disagree. Level-triggered, so it also covers a
   server restart under a running game (mod sweeping for dashboards that are gone) without a separate
   startup write. Only planes the catalogue offers are ever asked for — otherwise a stale persisted
   layer id would be a mismatch the mod could never resolve, restated forever.

**Validated in-game (2026-07-25, singleplayer):** the end-to-end chain works — folder creation, the
catalogue, the subscription round-trip through `commands.xml` (including the reconciliation fix, on the
scenario that first exposed the gap: opening the app with a layer already selected), per-plane writes,
and the app's per-plane fetch. **All eight planes render**, the five Precision Farming ones included —
notably yield and seed rate, the two PF exposes no point read for, which are read out of their
bit-vector maps the way PF's own modifiers do.

**Multiplayer: validated (2026-07-25).** **Colorblind mode: validated (2026-07-25, SP).**

**Profiler (2026-07-25):** `Json.lua` is no longer among the top entries — the write cost the split
existed to remove is gone. What remains visible is VDTelemetry's own tick at **0.5–0.6%** of script
time, which is the per-frame scheduler itself rather than any one channel. The split had quietly made
that worse (nine registered channels where there was one, all walked every frame by `tick` and
`writeDirty`), so `0b391c7` trims the idle path: skip channels with neither cadence nor tick, bail out
of `writeDirty` while nothing is queued, and throttle the offered-layer recheck to 5 s. Worth
re-reading the profiler after that, but the mod has to run *something* every frame, so this entry
never goes away entirely.

Consequences for the PF work: a new plane is an entry in `VDT.MapLayers.LAYERS` plus its
classification in `classifyCell` (under the `wanted` gate), a fixture, and nothing else — no file,
dirty, legend, watcher, route or app changes.

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

1. ~~Measure `c726a01` in-game (profiler)~~ → §1 was done regardless, as the precondition for PF.
2. ~~§1 per-layer file split~~ — **done** (see "How it landed"), with subscription gating alongside it.
3. ~~In-game validation of the split + gating~~ — **done** (2026-07-25), singleplayer, multiplayer and
   the profiler read included; see "Validated in-game" above.
4. ~~Precision Farming planes~~ — **done and working in-game** (2026-07-25): PF's five menu-visible value maps (soil type,
   pH, nitrogen, yield, seed rate) are exported as further planes. It cost one integration file and an
   `ipairs` over `LAYERS` in the sweep, exactly as this plan predicted — no file, dirty, legend,
   watcher, route or app changes. Soil/pH/nitrogen use PF's documented point reads; yield and seed
   rate have none, so they read channel 0 of the bit-vector map the way PF's own modifiers do.
5. ~~§2 crops/growth sub-toggles~~ — **not doing this for now** (2026-07-25).
6. ~~§2 soil sub-layers~~ — same; independent soil visibility isn't wanted, so the priority-collapse
   re-model it would need stays unbuilt. §2 below is kept as the record of what it would take.
