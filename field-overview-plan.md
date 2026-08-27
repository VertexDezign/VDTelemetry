# Field overview app (issue #131)

Plan for an app that answers *"what is on my land, and what does it need next"* — every field as a
row you can sort and filter, with the work each one is asking for turned into a task in one tap.

Status: **planned, nothing built**. Written 2026-08-25 against `main` @ `d5b5d2a` (mod `VERSION 20`),
on branch `131-field-overview-app`.

Scope decided with the user before writing this:

- **In:** the field list + map, owned/unowned filtering, crop + status per field, per-field task
  attachment and suggestion, next-crop hints from CropRotation and the crop calendar, farmland price
  on unowned fields, and — the one real new mechanism — **per-field status derived from the ground
  raster** rather than from a single sampled point.
- **Out:** Precision Farming's per-farmland economics (`FarmlandStatistic`: yield, seed, fertilizer,
  fuel, helper cost, subsidies per field). It is real data and it is tempting, but the user rarely
  uses PF, and on a multiplayer client it only arrives one farmland at a time via
  `RequestFarmlandStatisticsEvent` — whose receive handler force-opens PF's own dialog. Not worth the
  MP asterisk for a feature that would sit dark on most sessions. Noted in `FUTURE.md` instead.
- **Out:** vanilla fertiliser, lime and weed *state*. The user plays with Precision Farming, which
  replaces the base soil model outright — and the mod already agrees: `FieldInfoExporter` withholds
  `sprayLevelPercent`, `needsLime` and `yieldBonusPercent` whenever PF is active, mirroring the game's
  own panel, which hides those three lines under PF. So in the saves this app is being built for,
  two of them are *already absent from the channel* and the third (`weed`, still exported) is equally
  uninteresting. No status rows for them, and no suggestions driven off them — instead, those tasks
  are created by hand, scheduled ahead (see *Scheduling: N months out*).
- **Out:** a VDT-owned `fieldId → rotation plan` store. `FUTURE.md` → "Assigning a CropRotation plan
  to a field" already scopes it; this app is the consumer that would justify it, but round 1 gets the
  same answer from data that already exists (see *Next crop*), so the persistence question stays
  parked.

---

## Where this stands

Most of this issue is a **join over channels that already ship**. That is the reason it is worth
doing now: one new derived channel, one small mod addition, and the rest is app work.

| What the issue asks for | What already carries it |
| --- | --- |
| All fields on a map, with size | `map.json` — `polygon`, `areaHa`, `ownerFarmId`, per-farm colours |
| Current crop and field status | `fieldInfo.json` — crop, growth token, stage `n/max`, yield bonus, fertilized %, weeds, needs plow/lime/roll |
| Owned vs unowned filter | `MapField.ownerFarmId` (null = unowned) |
| Crop-rotation integration | `cropRotation.json` (plans, per-slot and per-option yield previews) **and** `FieldCropRotation` already inlined per field in `fieldInfo.json` |
| Recommendation for next planting | `cropCalendar.json` — per-crop sow/harvest periods, plus `today` |
| Smart task creation | `ClientMessage.CreateTask` + `TaskInput` + `TaskFormDialog` (already carries period and recurrence) |
| Relevant tasks per field | **nothing** — see *Tasks ↔ fields* |
| Contracts on a field | `MissionsData` — `Mission.fieldId` is already the same farmland id `MapField.id` uses |

Two structural facts worth writing down, because they make the joins safe:

- **A field and a farmland are 1:1, enforced by the game.** `FieldManager:loadMapData` logs an error
  and drops the field if two fields land on one farmland, or if a field's polygon touches a second
  farmland. So `MapField.id` = farmland id = the displayed field number, and every channel keyed by
  either meets on the same integer. (The converse is not true: a farmland with no farmable ground has
  no field and never appears in `map.json`. It also can't be shown in a buy list — see *Open questions*.)
- **Everything above already works on a multiplayer client**, which is the bar every part of this
  plan has to clear.

---

## The one data gap: field status is a single pixel

`FieldInfoExporter.collectField` constructs a fresh `FieldState` and calls `update()` **at the field
centre** — `getCenterOfFieldWorldPosition`, one point. That is exactly right for what it was built
for: the map popup mirrors the game's own `PlayerHUDUpdater:showFieldInfo`, which is also a
single-point read under the cursor.

It is the wrong shape for an overview. In a list, that value becomes the field's *headline*, and a
half-harvested field reports whatever its centre happens to be — "Ready to harvest" for a field
that is 70 % cut, or "Cut" for one where only the middle has been taken. The same goes for a field
mid-sowing, or one where the plough got two thirds of the way.

Three ways to fix it. The third is the one this plan takes.

### 1. `field:getFieldState()` — no

The game keeps a persistent `FieldState` per field and refreshes it in `FieldManager:update`. That
function opens with:

```lua
function FieldManager:update(dt)
	if g_server == nil then
		return
	end
```

So the cached per-field state is maintained **on the host only**. On a joined client it is whatever
`Field.new` left behind. Reading it would look correct in singleplayer and be silently empty in
multiplayer — the same class of trap as `mp-client-placeable-ids` and the mod-environment isolation
one. The current point-sampling approach is the MP-safe one precisely *because* it reads the density
maps, which are synchronised. Keep it.

### 2. `FieldGetInfoTask` — no

This is the game's own answer to the question: it runs a `DensityMapMultiModifier` over the field's
`getDensityMapPolygon()` and returns **pixel counts per fruit and growth stage** across the whole
field. It is how field contracts are scored. But `enqueue()` goes to `g_fieldManager:addFieldUpdateTask`,
drained by the same server-only `update` loop. Host only, and it competes with the game's own field
updates for a shared budget. Same verdict.

### 3. Histogram the raster the mod already sweeps — yes

`MapLayersExporter` already sweeps a 512² `growth` plane **client-side, from the density maps**, and
classifies every cell into a semantic vocabulary (`cultivated`, `stubble tillage`, `seedbed`,
`plowed`, an 8-step growing gradient, `topping`, `harvest ready`, `cut`, `withered`). The server
already parses that plane and decodes it to an `IntArray` (`MapLayerData.decodeCells`). And
`map.json` carries every field's polygon in the *same normalized `[0,1]` frame with the same world
origin*.

So the whole question is a polygon rasterisation and a counting pass, in Kotlin, on the server:

> **F12 — 4.8 ha · 62 % harvest-ready, 31 % cut, 7 % growing**

No new Lua, no new engine reads, no cost to the game, and it works on a multiplayer client because
the plane it reads was swept on that client. This is the mechanism the rest of the app hangs off.

---

## Field status from the raster

### The field index grid

Build, once per `(map.json content, gridSize)`, an `IntArray(gridSize * gridSize)` mapping each cell
to the field id whose polygon contains that cell's centre, or 0 for none. Then each histogram pass is
a single walk over the raster:

```
for cell in 0 until gridSize*gridSize:
    field = index[cell]; if (field == 0) continue
    counts[field][raster[cell]]++
```

262 144 cells per pass, one array read and one increment each; the index rebuild is bounded by the
same number. Both are sub-10 ms and neither is on the telemetry tick — this recomputes only when a
`growth` sweep lands (every few seconds at most) or the map changes (rarely). Memoize on
`(raster.contentVersion, indexVersion)` exactly the way `MapLayerRenderer` memoizes its PNG.

**Do not reuse `CoverageRecorder.fill`.** It takes the *outermost* crossings on each scanline, which
fills the convex hull — correct for a `WorkSweep` polygon, wrong for a field. Real fields are
concave routinely (L-shapes, fields wrapped around a wood or a farmyard), and hull-filling one would
claim its neighbours' cells and quietly corrupt both fields' numbers. The index grid needs a proper
even-odd fill: collect every crossing on the row, sort them, fill between consecutive pairs. Keep
`fill`'s two good habits — test each row and column at the cell *centre*, and treat edges as
half-open so a vertex on the scanline is counted once.

Overlap is impossible by the game's own 1:1 rule, so last-writer-wins on a contested cell is a
non-issue; still, assert it in the test rather than assume it.

### Semantic kinds, not hardcoded wire values

The growth plane's wire values (`GROWTH_CUT = 22`, `GROWTH_HARVEST = 21`, `GROWTH_GRADIENT_BASE + i`,
…) are the **mod's own vocabulary**, documented as such in `MapLayersExporter.lua` and deliberately
not the game's enum. Today nothing outside the mod depends on them: the server renders cells to
colours through the legend and never asks what a value *means*.

Hardcoding `22 == cut` in Kotlin would turn an internal vocabulary into a cross-subsystem contract by
accident — the kind of coupling that breaks silently when someone inserts a value. Instead:

- **`MapLayerLegendEntry` gains `val kind: String? = null`**, and the mod emits it per legend entry:
  `cultivated`, `stubble`, `seedbed`, `plowed`, `growing`, `topping`, `harvest`, `cut`, `withered`
  for the growth plane; `crop` for the crops plane; the soil plane's `weed`, `stone`, `needsPlowing`,
  `needsLime`, `fertilized` come free with the same mechanism. (`kind`, not `class` — `class` is a
  Kotlin keyword, and the wire key would need backticks or a `@SerialName` the model has no other
  use for.)
- **A string on the wire, an enum at the point of use.** `LayerKind.of(kind)` resolves the token for
  an exhaustive `when`; the raw string is what gets grouped and displayed. An enum on the wire would
  be actively worse here: the parser runs `coerceInputValues = true`, so an unrecognised enumerator
  is not an error but a silent substitution of the default — a kind a newer mod adds would arrive as
  null with the token gone, unloggable and uncountable. (It would also need a `@SerialName` per
  member, since the tokens are camelCase.)
- The server groups by `kind`, so an unknown or absent kind simply lands in an `unknown` bucket
  instead of being misread. Old mod + new server degrades to "all unknown", which the app renders as
  "no breakdown", not as a wrong breakdown.
- `mapLayers` goes to **VERSION 3**. It is an additive, tolerant-decode key, so nothing else moves.

This also means the *labels* stay out of it — they are localized, and matching on them would work on
a German client and not an English one.

### Round 1 uses the `growth` plane only

The `crops` plane would add "which crop, where" for a mixed field. It is a second subscription, a
second histogram, and its values are fruit-type indices whose meaning the app would have to resolve —
and `fieldInfo.crop` already names the dominant crop from the same source. Defer it; the mechanism
generalises to it for free once round 1 is in.

### Subscription gating

The mod only sweeps a plane something is subscribed to (`MapLayerSubscriptions` hands it the union
across dashboards; `reconcile` restates it when the mod's catalogue disagrees). So the field app must
**hold a `growth` subscription while it is open**, or its histogram is of a raster nobody is
refreshing.

The app-side union lives in `liveLayerSelections` — a private module-level map inside `MapPanel.kt`,
keyed by panel instance. Lift it into `state/LayerSubscriptions.kt` unchanged (same keying, same
"only touched from the composition" note) so both the map panel and the field app can register. The
server side needs nothing: it already unions per session and per dashboard.

Consequence to design for: opening the field app makes the mod start sweeping, and a full sweep is
4–9 s. The first histogram after a cold open is *late*, not wrong. The UI should say "sampling…"
rather than show a zeroed breakdown.

### Wire shape

Derived server-side, like the coverage layer — so it is a `ServerMessage` variant with no channel
file behind it, and its `null` means "no raster yet", never "mod not installed":

```kotlin
@Serializable
data class FieldStatusData(
  val layerId: String = "",          // "growth" in round 1
  val haPerCell: Float = 0f,         // (terrainSize / gridSize)^2 / 10000
  val fields: List<FieldStatus> = emptyList(),
)

@Serializable
data class FieldStatus(
  val id: Int = 0,                   // farmland id — MapField.id / FieldInfoEntry.id
  val cells: Int = 0,                // cells inside the polygon carrying a value; 0 => nothing sampled
  val blank: Int = 0,                // cells inside the polygon the plane recorded nothing for
  val slices: List<FieldStatusSlice> = emptyList(),  // descending by cells
)

@Serializable
data class FieldStatusSlice(val kind: String = "", val cells: Int = 0)
```

`blank` is the one addition to the shape sketched above, and it earns its place: a field polygon is
the farmland border while the raster carries *ground state*, so meadow, a track, a yard or ground the
plane has no word for sits inside the polygon carrying value 0 — which the planes use for "nothing
here" and never list in a legend. Counting those into the denominator would dilute every percentage
by however much of the title deed isn't farmed. Kept as its own number rather than dropped, because
`cells == 0 && blank > 0` is "the field is there and the plane says nothing about it", which is a
different thing to say than "too few cells to trust".

with `fraction`/`ha` as derived (non-serialized) accessors on `FieldStatus`, the way `PfNozzles`
does it. Put the histogram itself in **`shared`** as a pure function over
`(MapData, MapLayerData) -> FieldStatusData` — then `:shared:jvmTest` can cover it against the
`examples/json/` fixtures, and the server module holds only the caching and the broadcast.

**Fixture needed:** `examples/json/mapLayers/growth.json` is a `gridSize: 8` capture — 77 fields over
64 cells proves nothing. A real 512² `growth` capture taken on the same save as
`examples/json/map/vanilla.json` (both are `terrainSize: 2048`, so they co-register) is what makes
the test meaningful. Add it to `FUTURE.md` → "Captures wanted as fixtures" if it isn't taken with
this work.

### What this does *not* fix

State it in the code, not just here:

- **Small fields are noisy.** At 512² on a 2 km map a cell is ~4 m square, so a 1 ha field is ~600
  cells and a 0.3 ha corner is ~180. Percentages on a small field are coarse. Suppress the breakdown
  below some cell count and fall back to `fieldInfo`'s point sample rather than print a confident
  "83 %" derived from forty cells.
- **It is a snapshot of the last sweep**, not live. Between sweeps the mod patches only cells near
  active vehicles (`PATCH_RADIUS_M = 32`, every 4 s), which happens to be exactly where the work is —
  so a field being worked *does* update — but a field harvested by a helper across the map updates on
  the next full sweep.
- **It says nothing about fertiliser, lime or weeds** — and by decision, neither does the app. Those
  live on the `soil` plane, and the vanilla readings PF supersedes are out of scope entirely (see the
  scope list). If a PF-aware soil view is ever wanted, the mechanism here generalises to the PF value
  planes, which `MapLayersExporter` already registers as ordinary layers.

---

## Farmland price on unowned fields

Small mod change, big payoff for the "unowned" filter: it turns a list of things you don't own into a
buy planner sortable by price, price/ha and area.

`Farmland` carries `price` (a fixed `#price` from the map XML, or `getPricePerHa() * areaInHa *
priceFactor`), and `FarmlandManager:loadFarmlandData` runs on host **and** client — so this is
MP-safe, unlike almost everything else on the farmland object.

In `MapExporter`'s field loop, beside the existing `getAreaHa` read:

```lua
local okFarmland, farmland = pcall(field.getFarmland, field)
if okFarmland and type(farmland) == "table" and type(farmland.price) == "number" then
  entry.price = math.floor(farmland.price + 0.5)
end
```

`MapFieldModel` and `MapField` gain `price: Int?` (null = unknown, never 0). `map.json` is
event-driven and price is static per farmland, so this rides the existing dirty triggers with no
cadence change. Bump the map channel's own version.

**Host-only sibling, deliberately skipped:** `field:getPlannedFruitTypeIndex()` is the crop the NPC
will offer as a sow contract on an unowned field — but `FieldManager:updateField` (which sets it)
returns early for owned fields and runs only on the server, so on a client it is `UNKNOWN`. Not worth
a value that is right in singleplayer and blank in multiplayer.

---

## Tasks ↔ fields

FS25_TaskList has **no field column** — a task is a group, a detail string, a priority, an effort and
a recurrence. So the issue's `F<fieldnumber> - <tasktype> - <additionalInfo>` convention isn't a
nicety, it is the *only* join available. Which means it should be treated as a format the app parses,
not a habit the user maintains.

- **The parse:** `^F(\d+)\s*-\s*([^-]+?)\s*(?:-\s*(.*))?$` against `Task.detail`. A task that doesn't
  match is simply not attached to a field — it still shows in the Tasks app as it does today. The
  join is lossy by construction (rename the detail and the link is gone); that is acceptable, and it
  is the cost of not owning a store.
- **A fixed task-type vocabulary**, so the middle group is total rather than free text: `Sow`,
  `Fertilize`, `Lime`, `Plow`, `Cultivate`, `Roll`, `Harvest`, `Weed`, `Spray`, `Mulch`, `Stones`.
  The app writes these; it *reads* anything, falling back to "Other" for a hand-typed one.
  (The issue's example says "Saw" — it's "Sow".)
- **Watch the budget.** `MAX_DETAIL = 45`, mirroring the mod's `Task.MAX_DETAIL_LENGTH`. `F45 - Fertilize - `
  is already 18 characters, so the free-text tail is short. The form must count against the *whole*
  composed string, not just the tail, or the mod will truncate what the app thought it saved.
- **Not a VDT store.** `FUTURE.md` → "VDT-owned data" has the design (and the constraint that the FS25
  Lua sandbox only opens files for *writing*, so anything the mod must read back is XML). The prefix
  costs nothing, survives edits made in the mod's own in-game UI, and is legible to a human reading
  the task list in-game. Revisit only if the prefix proves too lossy in practice.

---

## Suggestions

The point of the app is that a field's row already knows what it needs. Each suggestion is a chip
that opens `TaskFormDialog` **prefilled** — detail, and where the calendar can say so, the period —
so accepting one is a tap and editing one is still possible.

| Condition (all from data that ships today) | Suggested task |
| --- | --- |
| `FieldInfoEntry.needsPlowing` | `F<n> - Plow` |
| `needsRolling` | `F<n> - Roll` |
| growth `readyToHarvest`, or the raster shows a harvest-ready share above a threshold | `F<n> - Harvest` |
| bare field (no crop) | `F<n> - Sow - <crop>`, dated to the first legal period |
| raster shows a large `withered` share | `F<n> - Cultivate` (the crop is lost; the honest suggestion is to clear it) |

`Fertilize`, `Lime`, `Weed` and `Spray` are **not** suggested. Under PF the vanilla flags that would
drive them are withheld by the mod (`sprayLevelPercent`, `needsLime`) or meaningless (`weed`), and a
suggester that fires only on non-PF saves is a rule with two behaviours and one test. They stay in the
task vocabulary and are created by hand from the field's "add task" chip, which is where the offset
scheduling below earns its place: crop care is planned *forward* — "lime this one in three months" —
not triggered by a reading.

Two rules for the suggester:

- **A suggestion is suppressed while a matching open task exists** for that field and type. Otherwise
  every visit to the app re-offers work already on the list.
- **`TaskInput` carries `month` and `recurMode`**, so a sow suggestion can carry *when*, not only
  *what* — the first period in the crop's `plant` set at or after `cropCalendar.today.period`.

**The guard that matters:** when `CropCalendarData.growthMode != SEASONAL`, the game answers "yes" to
every period for every crop, so `plant`/`harvest` are all twelve and mean nothing. `isSeasonal`
already exists for exactly this. In that mode the app offers the sow task with **no month** and says
so, rather than inventing a best month from data that carries none.

### Scheduling: N months out

The manual side needs one control the mod's own wizard doesn't offer: **"+N months from now"**, so a
crop-care task can be dropped on a field and dated forward without the user working out which absolute
month that is.

The arithmetic is a fixed shift, which makes this cheap: `TaskForm.periodToMonth` is `period + 2`
(wrapping at 12) — the inverse of the mod's `convertMonthNumberToPeriod` — so an offset in months is
the *same integer* as an offset in periods. "Now + N" is `periodToMonth(cropCalendar.today.period + N)`,
wrapped. `recurMode` stays `0` (Once) for a one-off, or `3` (Every N months) when the user wants it to
come back — which is the natural shape for lime.

- Present it as offset chips (`Now`, `+1`, `+2`, `+3`, `+6`) that write `TaskInput.month`, with the
  existing absolute-month dropdown still there underneath. The chip is the fast path, not a
  replacement for the field.
- Show the resolved month beside the chip (`+3 → Aug`), because the task list itself only ever
  displays the absolute month — an offset that isn't echoed back is a number the user has to trust.
- **Requires the `cropCalendar` channel** for `today.period`; nothing else in the model carries the
  current period (`Environment.date` is a preformatted string). When it is absent, hide the chips and
  leave the absolute dropdown — a "+3 months" from an unknown *now* is worse than no shortcut.
- **`isSeasonal` does not gate this.** The growth mode only decides whether the crop *windows* mean
  anything; a task's period is plain game time and is well-defined in every mode. The guard belongs on
  the sow suggestion, not here.

### Next crop, without new persistence

CropRotation stores plans as a flat list with **no link from a plan to a field** — which is why
`FUTURE.md` proposes a VDT-owned map. Round 1 gets most of the value without it:

`FieldCropRotation` already gives this field's `lastCrop` and `prevCrop` (per field, from the mod's
own history maps). Match that ordered pair against every `CropRotationPlan.sequence`; the
best-matching plan's *next* slot is the suggested crop, and that slot's `yieldPercent` is the number
to show beside it. No store, no write side, no savegame question — and where nothing matches, fall
back to ranking the calendar's currently-sowable crops by `CropRotationSlot.cropYields` for this
field's history, which is the same answer the mod's own planner would give.

If that heuristic proves annoying in play, *then* the VDT-owned store earns its complexity.

---

## App shape

A **separate app**, not a mode of the Map app. The map already has the per-field popup; what the
overview adds is sorting and filtering *across* fields, which a popup can't do. The two cross-link:
`MapFocus.request(x, z)` already exists for exactly this hand-off (the fleet list uses it), so a row
tap can put the field on the map, and the map popup grows a "show in Fields" affordance.

- `FieldsApp : VdtApp`, `id = "fields"`, registered in `AppRegistry` after `MapApp`. `isAvailable()`
  is true whenever `mapData` has fields — this is core data, not an optional mod, so it does not use
  the null-channel convention.
- **Full page:** a filter/sort bar over a virtualized list. Row: field number, name, area, crop,
  status (word + the raster breakdown as a thin stacked bar), attached-task count, and the
  suggestion chips. Expanding a row shows the `fieldInfo` block — crop, growth, stage, plow and roll,
  **minus** the fertiliser/lime/weed rows the map popup still draws — plus the rotation section, any
  contract on this field, and the task list filtered to `F<n>`. That divergence is deliberate: the
  popup mirrors the game's own FELDINFO panel and should keep doing so, while this app is a working
  view for a PF save.
- **Filters:** owned / unowned / all; needs-work; crop; "ready to harvest". **Sorts:** number, area,
  crop, status, price (unowned).
- **Header summary** — this is what makes it an overview rather than a list: total ha owned, ha by
  crop, "43 ha ready to harvest", count of fields needing work. Derived, cheap, and it is the line a
  player actually reads first.
- **Widget:** `FieldsWidget` — "fields needing attention", a count plus the top three, sized like the
  other list tiles. Same app/widget split every other app follows.
- **Alerts:** a `KeyedAlertRule` keyed per field id for **withered** — that one is money already
  lost — and optionally one for harvest-ready. This needs `AlertInputs` (today
  `telemetry` + `taskList` only) to gain `fieldInfo` and the derived status, and `Main.kt` to pass
  them; small, but it is a shared type, so do it deliberately.

### Design rules that bite here

- **Status must not be hue alone.** The stacked bar is the natural way to draw a breakdown and the
  natural way to get it wrong. Every state needs a word or a shape too, and the bar's segments need
  labels or a legend — not just a colour each. The same rule already constrains the map's field tint.
- **Marks are `Icon`s.** No `▲ ▼ ✕ →` in the sort headers or the suggestion chips; wasm has no font
  fallback and they render as tofu. Inside a sentence, host the icon in an `InlineTextContent` so the
  line still ellipsizes as one `Text`.
- **The chips are the controls.** Same call as the universal machine screen: a suggestion chip that
  opens a prefilled dialog reads better than a row of buttons, and it degrades to a plain label when
  the task already exists.

---

## Open questions

- **Threshold for "too few cells to trust".** Needs a real capture to pick; a guess now would be a
  magic number defended by nothing.
- **Does the raster status or the point sample win the headline?** The raster is more truthful about a
  partly-worked field; the point sample is what the game's own HUD shows for that field. Current
  intent: raster leads when it has enough cells, point sample is the fallback and the tie-breaker for
  the fields the raster can't resolve. Worth looking at in play before committing.
- **Farmlands with no field.** They can be bought and never appear in `map.json` (which iterates
  `g_fieldManager.fields`). A complete buy list would need a farmland sweep in `MapExporter` — a
  different channel shape, and arguably a different feature. Parked.
- **Does the growth plane's `withered` class survive a full sweep on a client?** The classification is
  ours and runs client-side, so it should — but "should" is what the in-game checks list is for.

---

## Sequencing

Each step is independently useful and independently testable.

1. ~~**`kind` on the legend + `mapLayers` VERSION 3.**~~ **Done.** `LEGEND_KIND` in
   `MapLayersExporter.lua` emits a kind per legend entry on the growth, crops and soil planes (and
   deliberately none on the PF planes, where a value is a measurement rather than a state);
   `MapLayerLegendEntry.kind` decodes it as a string, `LayerKind` resolves it for callers that want
   an exhaustive `when`, and `contentVersion` mixes it so a legend that changed only there
   invalidates anything keyed on the version. Fixtures and specs updated; nothing consumes it yet.
2. ~~**The histogram, in `shared`.**~~ **Done.** `FieldIndexGrid.of(map, gridSize)` rasterizes the
   field polygons by even-odd scanline into a cell→field-id grid, `histogram(layer)` walks the
   decoded raster once and counts per field and per `kind`, and `fieldStatus(map, layer)` is the
   convenience form for a caller with no reason to hold the grid. `FieldStatusTest` covers it:
   a U-shaped field with a second field in its notch (the case a hull fill silently gets wrong),
   cell-centre claiming at both edges, the gradient collapsing into one `growing` slice, the unknown
   bucket taking both an unlisted value and a `kind`-less legend entry, the gridSize/terrainSize
   refusals, and — against `map/vanilla.json` at 512² — zero overlaps across all 77 fields with the
   claimed area within 6 % of the `areaHa` the mod exported.
3. ~~**Server plumbing.**~~ **Done.** `FieldStatusPublisher` holds two caches — the index grid keyed
   on `(map, gridSize)`, the histogram on the growth raster's `contentVersion` — driven by a
   `combine(mapState, mapLayerState)` on the app scope into a `fieldStatusState` flow, and broadcast
   per session as `ServerMessage.FieldStatus` beside the existing `mapLayersJob`. The two caches are
   split because the map moves when a farmland is bought and the raster on every sweep; the version
   key is what makes a *soil* sweep — which re-emits the same keyed map — cost a string compare.
   Returning the identical instance is load-bearing rather than tidy: the `MutableStateFlow` drops an
   equal value, so an unchanged breakdown broadcasts nothing. `FieldStatusPublisherTest` covers all
   four transitions, and `TelemetryRepository.fieldStatus` is the app-side flow (unconsumed until
   step 6).
4. ~~**Subscription lift.**~~ **Done.** `liveLayerSelections` / `layerUnion` are now
   `state/LayerSubscriptions.union(subscriber, selection)` — same keying, same "only touched from the
   composition" note, same call sites in `MapPanel`. The doc says what a subscriber is now that they
   are not all map panels: the field overview counts a plane it never draws.
5. ~~**`price` in `map.json`.**~~ **Done.** `MapExporter` reads it off `field:getFarmland()` (the
   Field has no price of its own) and rounds it to whole currency; map channel **VERSION 2**;
   `MapFieldModel.price` and `MapField.price: Int?` follow, null meaning unknown and never free.
   No fixture refresh: the three committed captures predate the key and are real captures, so the
   shape is pinned by inline JSON in `MapDataModelTest` and a re-capture is now asked for in
   `FUTURE.md` -> "Captures wanted as fixtures".
6. ~~**The app: list, filters, sorts, summary.**~~ **Done.** `FieldsApp` (registered after `MapApp`,
   available whenever `map.json` has fields) holds the `growth` subscription for as long as it is
   composed and hands a row's position to `MapFocus`; `FieldsModel.kt` is the join and the rules
   (`FieldRow`, the views, the sorts, the summary, `MIN_STATUS_CELLS`), `FieldsPanel.kt` the
   master/detail. The headline follows the plan's stated intent — the raster leads when it has enough
   cells, the point sample is the fallback — and the panel *says which*, since a reader deciding
   whether to drive out there should know whether they are being told about the whole field or its
   middle. Still open: `MIN_STATUS_CELLS` is a provisional 100 (~0.3 ha at 512²), which is exactly the
   open question below.
7. ~~**Task attachment.**~~ **Done.** `FieldTasks.kt` holds the format — the `F<n> - <Type> - <note>`
   regex, the eleven-word vocabulary plus `Other` for a detail typed in-game, `composeTaskDetail`
   budgeting the *whole* string against the mod's 45-character cap, and `taskMonth` reusing the edit
   form's own period arithmetic. Rows carry a task count (not the task names — a row is scanned), and
   the detail lists them with what each is due. `fieldWork` now speaks the same vocabulary, so the
   suggester and the parser cannot drift apart.
8. **Suggestions + prefilled create.** The write side, last, once the read side has been looked at in
   a real save.
9. **Widget + alerts.**

In-game validation, singleplayer **and** as a multiplayer client, before the branch merges — the
raster path is the whole reason this design was chosen over the two server-only ones, and that claim
is untested until a joined client has drawn a correct breakdown.
