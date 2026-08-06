# Future work

Everything planned, deferred or still open, in one place. Collected 2026-08-06 from the plan documents
as they were retired.

**What this file is.** A plan describes one feature and is deleted once that feature is built — the
reasoning moves into the code as it is written, which is where it is actually read. What a plan cannot
hand to the code is the work it *didn't* do: the deferred item, the open call, the check nobody ran.
That is what lands here. Each entry says what the work is and why it was left, not how to do it.

Retired plans, if the full reasoning is wanted (`git show <sha>:<file>`):

| plan | last content at | covered |
| --- | --- | --- |
| `gps-course-plan.md` | `8e4cda3` (2026-08-02, the commit that removed it) | issue #43 — GPS course, section view, coverage |
| `farm-page-plan.md` | `3caa3e2` (2026-07-12) | the farm page, TaskList + CropRotation channels |
| `vehicle-data-plan.md` | `afe2585` (2026-07-25) | the vehicle export gaps, §1–§4 |
| `map-layers-plan.md` | `df511b3` (2026-07-25) | ground-layer overlays, per-plane split |
| `mission-plan.md` | `91e9fca` (2026-08-06) | issue #17 — contracts |

Still live: **`isobus-plan.md`** (issue #58). Its mod side is built; its app side is not, so the plan
stays as the working spec. It is indexed below rather than duplicated.

---

## The vehicle page never caught up with the export

The four rounds of `vehicle-data-plan.md` deliberately stopped at the data layer, on the rule *export
first, UI later*, and left every rendering decision to a redesign that was coming. The redesign
happened — display modes, the pillar cluster, pages, widget instances — **and did not consume that
inbox.** Checked 2026-08-06: `VDTerminal/app` contains no reference to `pipe`, `cover`, `discharge`,
`tipping`, `baleCounter`, `workMode`, `schema`, `selection` or `controlGroup`. All of it is exported,
none of it is drawn.

- **The rig diagram** — the one that matters most, because it is a UI limitation caused by a data gap
  that has since been closed. `RigSlotPanel` still finds its slots by string-matching `position ==
  "FRONT"` / `"BACK"`, so anything nested or sideways is unrepresentable: `examples/json/nested_trailers.json`
  is a committed fixture the app cannot draw. Mod version 4 exports what a real diagram needs —
  `schema` (the engine's own `schemaOverlay`: name, offsets, and per joint `x`/`y`/`rotation`/`invertX`
  plus the lifted offsets) and `jointDescIndex` on each implement, which is the link that turns flat
  per-object data into a drawable tree. The layout arithmetic was kept **out** of the mod on purpose so
  the diagram can change without a mod release; `InputHelpDisplay:collectVehicleSchemaDisplayOverlays`
  is the algorithm to mirror, depth cap of 5 included.
- **Selection and control groups.** `selection.selected` is the engine's own per-object flag, so walking
  the tree finds the selected node without touching the root's ordered `selectableObjects`;
  `selection.controlGroup` is `{current, name, names}` off `spec_cylindered`. The game's own HUD prints
  only the group *number* — we have the names from the XML, so the dashboard can do better than the game.
- **Pipe and cover indicators**, on the vehicle and its implements. Both are objects now — `cover
  {state, index, count}`, `pipe {state, current, target, numStates}` — so a panel can say *which* cover
  of several is open and how far along a multi-state pipe is, rather than in-or-out.
- **The stepped fill bar.** `fillUnit.display == STEP` marks consumables, where capacity is a slot
  count: the game draws one segment per slot with the part-used roll's fraction inside the next one,
  and labels it `"2 / 2"` (a `ceil`, not a percentage). `components/FillUnitsDisplay.kt` carries the
  note and renders a continuous bar.
- **The work aspects.** `discharge`, `tipping`, `harvest`, `workMode`, `workWidth`, `baleCounter`.
  `discharge.reason` is the pick of them — the engine's own verdict on why unloading is refused
  (`NO_FREE_CAPACITY`, `NO_ACCESS_LAND`, …), the same code behind its on-screen warning, and nothing a
  dashboard could work out for itself.

### Two open calls on the mod side

- **`showOnHud` vs `showOnInfoHud`.** `aspects/FillUnit.lua` filters on `showOnInfoHud`, which is the
  *info-box* flag; the *fill-bar* flag a dashboard is mirroring is `showOnHud`. They are independent XML
  attributes, so today we drop units the game draws a bar for and show units it doesn't. Not taken,
  deliberately: the existing skip was added for a real symptom — a forage/carrot harvester's
  pass-through output showing up — and if those units carry `showOnHud="true"` the switch brings them
  straight back. It buys nothing observable today, so it waits on the in-game check below. If it is
  ever taken, keep our tolerance for zero-capacity units (mods that ship `capacity = 0`); that
  divergence from the game's `0 < capacity` gate is on purpose.
- **`Consumable` as a first-class block.** The fill unit already reports the right *level*. A dedicated
  block off `spec_consumable.types[]` would add which variation is loaded, `showWarning`,
  `allowRefillDialog` and the storage/consuming split — which is what a *"you're on your last roll"*
  alert needs, and nothing else. Do it only if that alert is wanted.

### Making pipe and cover controllable

An ordinary direct-call control, if it is wanted. `Pipe:setPipeState(state)` and
`Cover:setCoverState(state)` each take an **absolute** state and each own their multiplayer event
(`SetPipeStateEvent`, `SetCoverStateEvent`), which is exactly what the lossy command channel needs —
same shape as `LightControl` and `MotorControl`, which call the engine setters directly. Two quirks
worth knowing: `setPipeState` clamps to `numStates`, and `setCoverState` silently no-ops unless the
vehicle `hasCovers` and the state is within `0..#covers`.

This is **not** a departure from how `ImplementControl` works. That control routes lower/fold/activate
through FS25_additionalInputs' `vdAI*` functions because additionalInputs **already owns** that
spec-aware logic — attacher-joint lowering, fold-to-middle, `requiresPower`, the implement chain — and
hand-rolling it per spec was fragile (a self-propelled foldable like the Krone BigM reports "lowered"
via the Foldable fold-middle state, not Attachable, so a hand-rolled `setLoweredAll` no-ops on it). The
rule is to use what is in vdAI, **not** to extend vdAI with functions only VDTelemetry needs. There is
no pipe or cover function there, so calling the engine directly is the normal path, not an exception.

---

## ISOBUS (#58)

`isobus-plan.md` is still the spec; this is the index. Round 1's four aspects — `sowing`, `spraying`,
`plow`, `tillage` — are built, tested and captured against real machines. **The app side is not
started**: there is no `IsoBusApp`, no `IsoBusPanel`, no widget.

- **Round 1, app side.** The app, the panel (sections rendered by aspect presence, in the plan's order)
  and a widget with a slot `ConfigOption` — `FRONT` / `REAR` / `VEHICLE`, mirroring
  `RigSlotWidget.SLOT_KEY` — so a combination rig can carry a seeder tile and a sprayer tile at once.
  The one piece of refactoring it should do first: extract the section-shutoff bar and the work-area
  readout out of `RigSlotPanel` instead of reimplementing them.
- **Round 2 classes**, in rough value order: baler + wrapper (bale in progress, bale type, auto-drop —
  `Baler.lua` carries all of it), trailer / forage wagon, mixer wagon (the game ships a
  `MixerWagonHUDExtension` whose mixing-ratio bar is worth copying), then harvesters, as the issue
  suggests.
- **Round 2 controls.** Seed index (`setSeedIndex` / `changeSeedIndex` already send `SetSeedIndexEvent`),
  plough rotation (`setRotationMax` / `setRotationCenter`, both take `noEventSend` and own their event),
  the sprayer's doubled-amount toggle. None of them needs an MP event of our own.
- **A derived l/ha rate**, or not at all. `getSprayerUsage` scales by the machine's *speed limit* rather
  than its actual speed — that is how the game holds consumption per hectare constant — so what we
  export is `nominalUsagePerMin`, a rating rather than live draw. Turning it into a per-hectare figure
  means reasoning about what the engine is actually holding constant, checked against
  `processSprayerArea`. Precision Farming already publishes a true rate when it is installed.
- **Known gap:** `SaltSpreader` is a different specialization (WorkArea + TurnOnVehicle, no `Sprayer`),
  so road-salt equipment gets no aspect at all. Winter/road kit rather than a field implement — it needs
  its own collector if it ever matters.
- **Open:** whether the tillage aspect's `limitToField` is worth keeping. `Cultivator` registers no
  read/write stream, so on a client the field is whatever `onLoad` left behind. A call to make once it
  has been seen on a client.

---

## Missions (#17)

Built and validated in game. Four things were left.

- **Command outcomes have nowhere to go — deliberately, for now.** The engine answers every action with
  a state (`MissionStartState` has 8 values, five of them ordinary refusals a user should see) and the
  mod can only log it. The call (2026-08-05) was to skip the reply path and revisit if it bites. It is
  mitigated rather than absent: the app greys accept at the farm's contract cap and hides the buttons
  without the `manageContracts` right, so the two likeliest refusals are prevented rather than reported,
  and the channel's next write is event-driven off `MISSION_STATUS_CHANGED` and lands within a tick. If
  it does bite, the options are a `lastCommand` block on the channel (cheap, fits the existing one-way
  plumbing) or a real mod→app reply channel (bigger — and every other control would then use it).
- **Mission vehicles.** Accepting with equipment spawns machines at the shop and `mission.vehicles` is on
  the object. Showing them, and where they are, is the natural round 2.
- **A time-out alert.** A running contract about to expire is an `AlertRule` candidate: `minutesLeft` is
  already on the wire, and `AlertEngine` / `KeyedAlertRule` already exist (`TasksApp` is the precedent).
- **How much detail belongs on a driving screen.** The panel is a menu; the widget is what someone
  glances at while working. The split wants the same review the ISOBUS layout got.
- **Out of scope, explicitly:** contracting (`MANAGE_CONTRACTING`). Working *for* another farm is a
  separate permission and a separate feature.

---

## Map layers

Both remaining items were declined on 2026-07-25. They are kept as the record of what they would cost,
not as a queue.

- **Sub-value toggles inside a layer** — hide weeds, keep needs-plowing. The app has the legends but
  **not** the raster cell values; those exist only in the server-rendered PNG. So this is not app-only
  work. For **crops** and **growth**, where each cell holds exactly one value with its own legend
  colour, it is feasible as a server render-filter (`/api/map-layer/{id}?hide=…`, enabled values drawn
  and the rest transparent) with no data-model change. The alternative — raster rows to the app, which
  draws and filters the bitmap itself — is a big shift away from the deliberate "PNG server-side,
  legends-only over the WebSocket" design.
- **Independent soil toggles need the data de-collapsed first.** `classifySoil` returns *one* value per
  cell by priority (weeds > stones > needs-plow > needs-lime > fertilized), mirroring the game, so a cell
  that is both weedy and needs plowing stores only "weeds" — the plough state underneath was never
  captured, and hiding weeds cannot reveal it. Doing it properly means promoting each soil sub-state to
  its own single-value plane: mod classification, wire model, and stacking order in the app. Only if
  independent soil visibility is actually wanted; if it is picked up, fold it into one coherent re-model
  of the layer set rather than two passes.
- **The per-frame tick.** The per-plane split left VDTelemetry's own tick at 0.5–0.6% of script time —
  the scheduler itself, not any one channel. `0b391c7` trimmed the idle path (skip channels with neither
  cadence nor tick, bail out of `writeDirty` while nothing is queued, throttle the offered-layer recheck
  to 5 s), and it is worth re-reading the profiler after that. The mod has to run *something* every
  frame, so this entry never goes away entirely.

---

## Channel cadence and profiles

The scheduler, per-channel `enabled`/`intervalMs`, the profile presets and the Kotlin cadence
measurement all shipped. One thing was left, and it is a trap rather than a feature:

- **There is no app→mod config push.** Per-channel config is read from the settings XML **at load
  only**, and the mod rewrites that XML on any in-game change — so an app that edits it live gets
  clobbered. Wiring per-channel tuning into the app needs a push channel of its own, the way the
  command channel works. Checked 2026-08-06: nothing in `shared` carries channel config.
- Related, if that is built: a per-channel `intervalOverride` is **ignored unless the profile is
  `custom`**, so an app doing the tuning has to stamp `profile = custom` as it goes.

---

## VDT-owned data

Every write path today drives a *mod's* own state through its own multiplayer events; VDTelemetry
persists nothing of its own. The first real case for changing that:

### Assigning a CropRotation plan to a field

CropRotation's planner stores plans as a flat list and carries **nothing that ties a plan to a field** —
there is no notion of "field 7 follows the *Heavy Soil* rotation"; you read the plan and apply it by
hand. A VDT-owned map of `fieldId → rotation index` (plus, plausibly, the current position in the
rotation, so the app can say *what to plant next*) is exactly the kind of data VDT can own without
fighting the mod. Field ids come from `g_fieldManager`; joining our map against `cropRotations[index]`
gives a per-field "assigned rotation" view, and against the field's current fruit a "next crop" hint.
The write side is a VDT command against a VDT store, **not** a mutation of the mod.

Four things to work out when it is picked up:

- **Persistence location and lifetime.** This is *savegame* state, not client settings — two savegames
  must not share a field→rotation map. Keying by savegame id inside `modSettings/<modName>/`, versus
  writing into the savegame directory the way both target mods do (both hook off
  `FSBaseMission.saveSavegame`), is an open choice. The latter matches the neighbours and gets save/load
  timing for free.
- **Reading it back is XML, not JSON.** The FS25 Lua sandbox restricts `io.open` to write mode — which is
  why the command channel is XML in the first place (see `CommandChannel.lua`). Any store the mod has to
  *read* has the same constraint, whatever we emit for telemetry.
- **Referential integrity.** Plans are referenced by `index`, and `addDeleteCropRotations` deletes by
  clearing the slot, so an assignment can dangle. Resolve dangling references on load and treat a
  missing plan as "unassigned" rather than an error.
- **Multiplayer.** Unlike task and rotation edits, this state has no mod event to ride on. Either scope
  it to the local client — simplest, and consistent with telemetry being client-side only — or build a
  VDT sync event. Start local.

### CropRotation position / field data

Sampling the crop-history density map at the player's position
(`historyStateManager.historyStates[i].map:getState`, `YieldCalculator:potentialYieldAtPosition`,
`:getYieldMultiplier`), or sweeping `g_fieldManager:getFields()` for a per-field table. Both need a
timer, because the position changes as you drive, and both need in-game profiling before they are
trusted. The channel registry already supports it: a channel whose `markDirty()` is driven by a position
bucket rather than by a message.

---

## In-game checks nobody has run

Each one is cheap to do while playing and settles something above.

- Does a base-game baler set `uiDisplayType="STEP"` on its consumable fill unit? It is visible in the
  exported JSON as `display`, so this is just a matter of looking. Decides whether the stepped bar is
  worth building.
- Does a multi-state pipe report sensibly — an auger wagon should give `pipe.numStates > 2`? Read the
  JSON; nothing renders it.
- Does a multi-cover vehicle really report `cover.state > 1`? Parked for want of such a vehicle, and low
  risk: a single-cover vehicle only ever has state 0 or 1, where the old and new mappers agree exactly,
  so the only changed behaviour is `state >= 2`, which used to return `UNKNOWN` and was wrong by
  construction. The tell that you are looking at one is the action prompt reading **"Next cover"** rather
  than "Open/Close cover" — `Cover:updateActionText` uses that string only while `0 < state < #covers`.
- Does `schema` come out populated on a real rig, and does `jointDescIndex` line up with the parent's
  `attacherJoint` list? The one thing the synthetic tests cannot confirm.
- Does `controlGroup` populate on a front loader or crane, with sensible `names`? They come from vehicle
  XML and may be unresolved i18n keys on some mods. Same question for `workMode.name`.
- Does `discharge.reason` read `NO_FREE_CAPACITY` when the game refuses to unload? Back a trailer up to
  a full silo. This is the highest-value single check of the work aspects.
- Do any fill units in normal use differ between `showOnHud` and `showOnInfoHud` — in particular, does a
  forage/carrot harvester's pass-through output carry `showOnHud="true"`? This gates the filter switch
  above.

## Captures wanted as fixtures

The schema, selection and work aspects are all tested synthetically, because none of the committed
captures contains a machine that has them.

- **A tipping trailer** and **a baler.** Between them they cover `tipping`, `discharge`, `baleCounter`,
  the `STEP` consumable bar, and they would give `jointDescIndex` its first real chain.
- The rule these follow: fixtures are **real game captures, never hand-authored**. A hand-written file
  claiming to be a capture was rejected before, and fill-type names live in `fillTypes.xml`, which is not
  readable from here — inventing them would put made-up game data in `examples/json`.

---

## Accepted limitations — not bugs, and not worth re-deriving

- **A 2-slot crop rotation's dropdown preview can be slightly off.** The 2-deep history window wraps
  modulo the rotation length, so in a 2-slot rotation "two back" lands on the slot itself, and the
  preview reads that self-reference from the slot's *stored* crop rather than the hovered candidate.
  Verified in-game and left as-is: rotations that short carry almost no history.
- **Crop names follow the game's language; the app localizes nothing.** The mod hands over strings the
  game has already localized — crop names, mission subtitles, the game's own contract detail rows —
  and anything the app writes itself is English. A real localization story would be its own piece of work.
- **A half-upgraded install fails the parse.** `ignoreUnknownKeys` covers a newer mod against an older
  terminal but not the reverse: a v2 mod emitting `"pipe": "RETRACTED"` against a v3+ terminal is a
  string where an object is expected, which `coerceInputValues` does not rescue — it fails the whole
  parse. Mod and terminal ship from the same repo, so this only bites a mixed install. A union
  deserializer would fix it if it ever proves a real support burden.
- **`CROP_ROTATIONS_CHANGED` never reserves its id.** `FS25_CropRotation` sets it by *counting* existing
  `MessageType` entries instead of calling `nextMessageTypeId()`, so depending on mod load order another
  mod can be handed the same id. Harmless for us — the CropRotation channel is poll-driven and nothing
  subscribes to that message — but do not build anything load-bearing on it.
- **Third-party internals are pinned, not stable.** Both optional integrations read another mod's
  internals, so each header pins the version it was written against — FS25_TaskList `1.2.0.1`,
  FS25_CropRotation `1.0.1.0` — and fails soft: guard every field read, `pcall` the yield maths, treat a
  missing field as "no data", because a throw in a collector takes the whole telemetry write down with
  it. The risk does not go away; a mod update can still empty a panel, and the pin is what tells the next
  reader where to look.
