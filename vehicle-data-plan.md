# Vehicle telemetry — the data we don't export yet

Written 2026-07-25 on `main`, from a review of the vehicle collectors against the FS25 game source
(`fs25-modding-skill/references/lua-source`). Four gaps, in the order they're worth doing.

**Status (2026-07-25):** all four open, nothing started. §1 is the only one that fixes a *wrong number*
currently on screen; the rest add data that isn't there at all.

Every game-source claim below is cited by `file:line` against the bundled source, which is real
extracted FS25 Lua. What is **not** available in the sandbox is base-game **vehicle XML** — so anything
that depends on the value a specific machine puts in an attribute is marked as needing an in-game
check rather than asserted.

## Guiding principle: export first, UI later

A **UI redesign is coming** (scope undecided, possibly large). This plan therefore deliberately stops
at the data layer: get the telemetry exported and modelled correctly, and let the redesign decide how
to show it.

So for every item below:

- **Do now** — mod collectors, the Lua model, `Model.kt`, fixtures and tests.
- **App: compatibility only** — the minimum to keep the current dashboard correct and compiling. A
  contract change that breaks a panel gets repaired, not improved.
- **Defer to the redesign** — every new widget, panel or visualisation. Noted per item so the redesign
  inherits the list rather than rediscovering it.

The practical consequence: **§2 and §3 become pure mod + shared work** with no app changes at all, and
§1 is the only item that forces the app to move — because widening a field type would otherwise break
it. That also argues for pulling more of §4 forward (see there), since a redesign is much easier to
scope against data that already exists than against data someone has to promise.

## Where this stands

`VehicleExporter.collect` (`vdTelemetry/src/collect/VehicleExporter.lua:57`) builds the vehicle header,
delegates to `VDT.Motor` / `VDT.Lights` / `VDT.SupportSystems`, applies the shared aspects
(`aspects/Aspects.lua:14` → `isTurnedOn`, `foldable`, `lowered`, `fillUnits`, `pipe`, `cover`,
`wearable`), then recurses the implement tree. The mod currently reads these game specs, and only
these:

```
attacherJoints  cover  drivable  enterable  fillUnit  foldable  lights
motorized  turnOnVehicle  washable  wearable   (+ the AI/steering ones)
```

`VDTelemetry.VERSION` is `1` (`VDTelemetry.lua:111`), matching `version: 1` in the fixtures.

---

## 1. Fill units: read the game's *display* values, not the raw ones

**The bug:** for balers (and anything else using consumables) the app reports **less than the machine
actually holds** — it counts the spare rolls in storage but not the partially-used roll currently
mounted and being consumed.

### What the game does

In FS25, net / twine / wrap are the `Consumable` spec on top of an ordinary fill unit, and **the unit
is slots, not litres**:

- `capacity = numStorageSlots + numConsumingSlots` — `Consumable.lua:152`
- the fill unit's raw `fillLevel` counts **only the spare/storage slots**. A freshly spawned machine
  gets `addFillUnitFillLevel(..., type.numStorageSlots, ...)` and, *separately*,
  `type.consumingFillLevel = 1` — `Consumable.lua:214-215`
- `type.consumingFillLevel` is the mounted roll's own level, clamped to `0..1` — `Consumable.lua:540`

Every update the game computes the real total and publishes it through the fill unit's **display**
field (`Consumable.lua:645-646`):

```lua
local totalFillLevel = math.min(fillLevel + type.consumingFillLevel * type.numConsumingSlots,
                                self:getFillUnitCapacity(type.fillUnitIndex))
self:setFillUnitFillLevelToDisplay(type.fillUnitIndex, totalFillLevel, true)
```

`getFillLevelInformation` — the source for the game's own fill bars — then prefers
`fillLevelToDisplay` over `fillLevel` (`FillUnit.lua:1948-1950`), `capacityToDisplay` over `capacity`
(`:1952-1953`), and `fillTypeToDisplay` over `fillType` (`:1944-1945`).

**`aspects/FillUnit.lua:46` reads `fillUnit.fillLevel`.** That is the raw spare-slot count. Hence the
undercount. `Consumable.lua:153` mirrors the capacity into `capacityToDisplay` too, so `capacity`
should come from the same place for consistency.

### The catch: our model can't represent the answer

`aspects/FillUnit.lua:61` does `value = math.floor(fillLevel)` and `Model.kt` declares
`FillUnit.value: Int` (`Vehicle.kt:118`). A half-used roll is `1.5` — **flooring it throws away exactly
the information that's missing.** Switching to `fillLevelToDisplay` alone changes nothing on screen.

So `value` has to become a float (or gain a companion field). That is a mod↔Kotlin contract change:
Lua model + `Model.kt` + `VDTelemetry.VERSION` + the `examples/json/` fixtures, together.

Knock-on effects in the app — **all compatibility repairs, no new UI** — in
`components/FillUnitsDisplay.kt` unless noted:

- `:17` the skip-empty guard `fu.value == 0` needs a tolerance, not equality
- `:22` `fu.value.toFloat() / fu.capacity` still works
- `:26` `"${fu.value}${fu.unit}"` would print `1.5` — needs formatting driven by `uiPrecision`
- `panels/Implements.kt:86` `mergeFillUnits` uses `g.sumOf { it.value }`; Kotlin's `sumOf` has no Float
  overload, so that becomes `map { it.value }.sum()`

That is the whole app-side obligation: the existing continuous bars keep working and start showing the
right number. Nothing here is a redesign decision.

### The segmented bar is real and exported

`fillUnit.uiDisplayTypeId` comes from the XML attribute `#uiDisplayType`, `BAR` (default) or `STEP`
(`FillUnit.lua:1434`). `FillLevelsDisplay.lua:157-175` renders `STEP` exactly as described in the
game: `capacity` discrete segments, whole ones filled, and the **fractional remainder drawn inside
segment `floor(fillLevel)+1`** — that fraction *is* `consumingFillLevel`. The text label is
`"%d / %d"` with `math.ceil(fillLevel)` (`:241-242`), so a machine with one spare and a half-used roll
reads **"2 / 2"**, not "1 / 2". Segment count is clamped by `MAX_NUM_STEPS`.

**Export `uiDisplayTypeId` now, render it later.** Drawing the stepped bar is a redesign decision;
having the flag in the model is what stops the redesign from being blocked on another mod release.
*Needs an in-game check:* that base-game balers actually set `uiDisplayType="STEP"` on the consumable
unit — the renderer exists and consumables are its obvious consumer, but the XML isn't readable here.

### Scope

- **mod** (`aspects/FillUnit.lua`): prefer `fillLevelToDisplay ?? fillLevel`,
  `capacityToDisplay ?? capacity`, and `fillTypeToDisplay` when it isn't `UNKNOWN`; stop flooring;
  carry `uiPrecision` and `uiDisplayTypeId`.
- **shared** (`Model.kt`): `value` → float, add `precision` + a `FillDisplayType` enum (`BAR`/`STEP`).
- **app**: the four compatibility repairs listed above. Nothing else.
- **fixtures + tests**: regenerate `examples/json/*`, extend `VdtModelTest`. Bump `VDTelemetry.VERSION`.

**Deferred to the redesign:** the stepped/segmented bar renderer, and the `"2 / 2"`-style label the
game uses for `STEP` units instead of a percentage.

### Free wins that ride along

Reading the display values also picks up `parentUnitOnHud` / `childUnitOnHud`
(`FillUnit.lua:1955-1963`), which is how the game folds a combine's buffer unit into the main tank
(`Combine.lua:263`) — something `mergeFillUnits` currently approximates app-side by grouping on fill
type.

### Secondary, related, *not* the baler bug

`aspects/FillUnit.lua:36` filters on `showOnInfoHud`. That is the **info-box** flag
(`FillUnit.lua:2032`, and the game additionally requires `fillLevel > 0` there). The **fill-bar** flag
— the one a dashboard is mirroring — is `showOnHud` (`FillUnit.lua:1939`, which instead requires
`capacity > 0`). They're independent attributes, so today we drop units the game shows a bar for and
show units it doesn't.

This is a genuine divergence but it is **not** what causes the consumable undercount, and the crop
fill unit on balers is confirmed working, so nothing observed currently depends on it. Worth switching
to `showOnHud` in the same pass, with the caveat that our deliberate tolerance for zero-capacity units
(`aspects/FillUnit.lua:56-58`, for mods that ship `capacity = 0`) diverges from the game's
`0 < capacity` gate and should stay as-is.

---

## 2. Pipe and cover: fix two lossy mappers

Both are already collected (`aspects/Pipe.lua`, `aspects/Cover.lua`), already in `Model.kt`
(`Vehicle.kt:21-22` and `:212-213`), and already in the fixtures (`combine.json` →
`"pipe": "RETRACTED"`). **They are simply not rendered anywhere in the app** — the status row in
`panels/Implements.kt:216-256` shows foldable / turned-on / lowered and stops there.

Surfacing them is the redesign's call. What belongs here is the export fidelity, because both mappers
silently lose real states today — and a redesign built on lossy data would bake the loss in:

### `mapCoverState` (`ValueMapper.lua:149`) is wrong for multi-cover vehicles

In `Cover.lua`, `spec.state` is `0` = closed and `1..#spec.covers` = **which** cover is open
(`Cover.lua:228`; `:128` sets `spec.state = #spec.covers`). Our mapper returns `OPEN` only for
`state == 1` and `UNKNOWN` for everything else — so a trailer with two or three tarp sections reports
`UNKNOWN` whenever any cover but the first is open.

Fix: `0` → `CLOSED`, anything else → `OPEN`, and carry the index plus `#spec.covers` if the app wants
to show *which*.

### `mapPipeState` (`ValueMapper.lua:137`) collapses multi-state pipes

`spec.numStates` is read from XML and is frequently `> 2` (`Pipe.lua:166`). `currentState` is `0`
**only while moving**, and `targetState` is where it's heading (`Pipe.lua:604-605`). So `EXTENDED`
conflates "half out" with "fully out", and `MOVING` doesn't say which direction.

Fix: keep the derived label for compatibility, add `currentState` / `targetState` / `numStates`.

### Scope

Small, and **mod + shared only**. Mod: two mapper functions plus the extra fields. Shared: widen
`PipeState.kt` / `CoverType.kt` into small data classes (keep the existing string as one field so the
current decode stays valid). Fixtures + tests. **No app changes** — nothing renders these today, so
nothing breaks.

**Deferred to the redesign:** status indicators for pipe and cover, on both the vehicle and its
implements — and, if the richer fields are used, showing *which* cover of several is open and how far
along a multi-state pipe is.

### Out of scope unless asked

Making them *controllable*. Every existing command routes through FS25_additionalInputs' `vdAI*`
functions rather than reimplementing spec logic — see the rationale at the top of
`src/command/ImplementControl.lua`, which is explicit that hand-rolling per-spec control was fragile.
`Pipe:setPipeState` and `Cover:setCoverState` are simple enough to call directly, but doing so would
be the first exception to that rule. Decide deliberately.

---

## 3. Vehicle layout and selected group

Nothing exported today. Two separate things, both reachable from the root vehicle.

### Selection

- `rootVehicle.selectableObjects` — the ordered list (`Vehicle.lua:2136-2147`). Note it is only
  populated **on the root vehicle** (`updateSelectableObjects` early-returns otherwise).
- `rootVehicle.currentSelection` — `{ object, index, subIndex }` (`Vehicle.lua:797`).
- per object: `getIsSelected()` (`Vehicle.lua:2280`).

Do **not** reimplement eligibility. Base `Vehicle:getCanBeSelected()` returns `VehicleDebug.state ~= 0`
(`Vehicle.lua:2155`) and specializations override it (e.g. `Baler.lua:1811`), so `selectableObjects` is
the only authoritative answer.

### The group

The "selected group" is the Cylindered sub-selection: `spec_cylindered.controlGroupNames` (i18n names
straight from the XML) and `currentControlGroupIndex`, with `controlGroupMapping[subIndex] → groupIndex`
(`Cylindered.lua:444-467`, rebuilt live in `updateControlGroups` at `:1539-1566`). Note the game's own
HUD only renders the group *number* (`Cylindered.lua:2849`) — the names are available to us, so the
dashboard can do better than the game here.

### The layout

`vehicle.schemaOverlay` (a `VehicleSchemaOverlayData`) gives `schemaName` — `VEHICLE` / `HARVESTER` /
`TRUCK` / `CAR` / `LOADER` / `IMPLEMENT` / `TRAILER` / `COMBINE_HEADER` / `FRONTLOADER` / `MOTORBIKE` —
plus `offsetX/Y` and `attacherJoints[]` with `x`, `y`, `rotation`, `invertX`, `liftedOffsetX/Y`
(`VehicleSchemaOverlayData.lua`).

`InputHelpDisplay:collectVehicleSchemaDisplayOverlays` (`InputHelpDisplay.lua:536-592`) is the exact
algorithm to mirror: walk `getAttachedImplements()`, index
`parent.schemaOverlay.attacherJoints[implement.jointDescIndex]`, and per node emit `selected`,
`turnedOn` (`getUseTurnedOnSchema()`) and `additionalText`. It caps recursion at
`MAX_SCHEMA_COLLECTION_DEPTH`; ours should too.

### Why this one matters most for the redesign

`panels/Implements.kt` is currently hardcoded to two columns, found by string-matching
`position == "FRONT"` / `"BACK"` (`:96-97`, via `findImplement` at `:58-64`), where `position` comes from
FS25_additionalInputs' `vdAIGetAttacherJointPosition` (`VehicleExporter.lua:25-27`). Anything nested or
sideways is unrepresentable — `nested_trailers.json` exists as a fixture but the panel can't draw it.

That is a **UI limitation caused by a data gap**, which makes this the item the redesign most depends
on. Exporting the schema up front means the redesign can consider a real rig diagram; not exporting it
means the redesign re-implements front/back columns and the constraint outlives the rewrite.

### Scope

**Mod + shared only.** `VehicleExporter.collect` already walks the implement tree, so this is mostly
adding `jointDescIndex`, the schema name and the selection flags per node, plus a small
selection/control-group block on the vehicle. Fixtures + tests. The current panel ignores unknown
fields, so nothing breaks.

Keep the layout *arithmetic* (offsets, rotation, `invertX` composition) out of the mod — export the
raw joint data and let the app place it, as `InputHelpDisplay` does. That keeps the maths where the
redesign can change it without a mod release.

**Deferred to the redesign:** the rig diagram itself, selection highlighting, the control-group
readout, and whatever becomes of the front/back columns.

---

## 4. Backlog — other specs worth exporting

Not investigated in depth; listed in the order I'd rank them.

**Consider pulling the top of this list forward, before the redesign.** Each of these is
mod + shared only under the principle above, and a redesign scoped against data that already exists is
a much better redesign than one scoped against a list of maybes. `Dischargeable` in particular is
core vehicle state that any new dashboard would want on day one.

- **`Dischargeable`** — the biggest remaining gap. `DISCHARGE_STATE_OFF` / `_OBJECT` / `_GROUND` plus
  the `DISCHARGE_REASON_*` codes for *why* unloading is blocked (no free capacity, filltype
  unsupported, no land access, …) — `Dischargeable.lua:3-11`. Covers every trailer, combine and auger
  wagon, and pairs naturally with pipe state from §2.
- **`Trailer`** — tip state and tip side.
- **`Combine`** — threshing active, chopper vs swath, straw output.
- **`Consumable` as a first-class block** — §1 fixes the *number* via the fill unit. A dedicated block
  off `spec_consumable.types[]` would add which variation is loaded, `showWarning`, `allowRefillDialog`
  and the storage/consuming split, which is what a "you're on your last roll" alert needs. Do it only
  if that alert is wanted; §1 is enough for correct levels.
- **`BaleCounter`** — total and session bale counts.
- **`WorkMode` / `VariableWorkWidth`** — current mode name, live working width.
- **`Motorized`** fuel *consumption rate* (we export levels, not usage).
- **`spec_washable`** — it's in the mod's spec list but dirt currently arrives via `Wearable`; worth
  confirming which source is actually being read.

---

## Suggested sequencing

Ordered so the app is touched once, early, and then left alone until the redesign.

1. **§1 fill-unit display values.** The only item fixing a wrong number already on screen, and the
   only one that touches the app. Land it as one change — the float widening is what makes the fix
   visible, so splitting it ships a no-op. Bump `VDTelemetry.VERSION` and regenerate fixtures.
2. **§2 pipe + cover mappers** and **§3 layout + selected group**. Both are mod + shared only, they
   don't interact, and either order works — §3 is the bigger one and the one the redesign leans on
   hardest, so start it first if only one gets done.
3. **§4 as far as appetite allows, `Dischargeable` first** — still before the redesign, for the reason
   given in §4.
4. **Then the UI redesign**, against a data layer that is already correct and already complete enough
   to design against. Each item above leaves a "deferred to the redesign" note; together those are the
   redesign's inbox.

One consequence worth accepting up front: between step 1 and the redesign, the dashboard shows *more
correct* data but not *more* data — pipe, cover, layout and selection will be in the JSON and visible
in `examples/json/` well before anything renders them. That's the intended trade, not a regression.

### In-game checks this plan depends on

- Does a base-game baler set `uiDisplayType="STEP"` on its consumable fill unit? (§1 — decides whether
  the stepped bar is worth building.)
- Do any fill units in normal use differ between `showOnHud` and `showOnInfoHud`? (§1 secondary —
  decides whether that switch changes anything observable.)
- Multi-cover trailer: confirm `spec.state` really does exceed `1`. (§2 — confirms the mapper bug is
  reachable, not just theoretically wrong.)
