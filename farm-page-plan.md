# Farm page + per-mod telemetry channels

Plan for a second dashboard page covering everything that isn't the current vehicle, and the
mod/server/app plumbing needed to feed it from two optional third-party mods
(**FS25_CropRotation**, **FS25_TaskList**).

Status: **done** (2026-07-12) — all four steps plus both optional-mod channels, read and write. What
remains is listed under [Deferred](#deferred) and was never in scope for this plan. Written 2026-07-09
against `main` @ `c1809e5`.

Progress (2026-07-10):
- **Step 1** (`requiresVehicle`) — done.
- **Step 2** (pages + navigation) — done.
- **Dedicated-server MessageCenter — verified in-game** (temporary probe). **TaskList works**
  (message fires client-side *and* `g_currentMission.taskList` is readable). **CropRotation is *not*
  blocked after all** — the probe's `nil` was a misread of mod-environment isolation, not server-only
  data; the planner is reachable client-side via `FS25_CropRotation.g_cropRotationPlanner` (source
  re-diagnosis 2026-07-10). See the CropRotation note below and [Open risks](#open-risks).
- **Step 3** (channels + multi-file server + read-only TaskList) — done. CropRotation stays a
  farm-page placeholder pending a client-side read redesign (its planner data is server-only).
- **Step 4** (TaskList write paths) — done. B2 (CommandWriter XML escaping) and B3 (Protocol
  invariant restated for the non-idempotent task actions) landed; complete / delete / create / edit
  wired app → command channel → the mod's own MP wrappers (`src/command/TaskListControl.lua`), with a
  task create/edit form in the panel.
- **CropRotation read channel** (2026-07-11) — done, mirroring the TaskList read path now that the
  "blocked" call is retracted (env-global isolation, not server-only data). Mod:
  `src/integrations/CropRotation.lua` self-detects via `FS25_CropRotation.g_cropRotationPlanner` and
  serializes the local farm's plans (crop + catch-crop display names resolved inline from
  `g_cropRotation:getPossibleCropStates()` / `:getPossibleCatchCropStates()`, plus the per-slot
  **yield-bonus %** the game shows). The planner only stores `yieldValue` while its GUI is open, so we
  recompute it exactly as the GUI does — build the preceding history window and call the mod's own
  `YieldCalculator:getYieldMultiplier` (pure client-side maths). The app colours it green/red around
  100 %, which is the whole point of previewing a rotation before committing it in-game. Shared
  `CropRotationData` model + `ServerMessage.CropRotation`; server watches `cropRotation.json`; app
  `CropRotationPanel` renders the sequences read-only (replaces the farm-page placeholder). Fixtures
  `examples/json/cropRotation/*.json`, `:shared:jvmTest` decode tests, and `spec/CropRotation_spec.lua`
  collector + change-detection tests. **Verified in-game (singleplayer):** existing plans show on
  entry; live add/remove/edit now show too (see the poll note below). (Writes came next — see the CRUD
  entry above.)
- **CropRotation write path — full CRUD** (2026-07-11) — done, read + yield verified on the dedicated
  server first. The collector now also ships the selectable-crop catalog (`crops` / `catchCrops`,
  `{ state, name }`, minus `ignoreInPlanner`) so the app can render dropdowns. `src/command/
  CropRotationControl.lua` registers six `requiresVehicle = false` handlers driving the planner's own
  MP wrappers: `setRotationCrop` / `setRotationCatchCrop` (idempotent slot edits →
  updateCropSelection / updateCatchCropSelection), `addRotationSlot` / `removeRotationSlot`
  (addCropRotationSelection / removeCropRotationSelection, keeps ≥1 slot), `createRotation` /
  `deleteRotation` (addCropRotation on the local farm / removeCropRotation). Shared `ClientMessage`
  variants (+ B3 note extended: the add/remove/create/delete ones are non-idempotent actions, the two
  slot edits are absolute-state), `CommandWriter` render branches (name escaped), and an interactive
  `CropRotationPanel` (per-slot dropdowns, add/remove slot, create/delete plan; degrades to read-only
  when the mod ships no catalog). Tests: `spec/CropRotationControl_spec.lua`, extended
  `CropRotation_spec.lua` (catalog), `CommandWriterTest`, `CropRotationModelTest`. The poll-driven
  collector means every write is reflected back live (incl. singleplayer, where the wrappers mutate
  in place without a message). **This completes the farm-page plan** — all four steps plus both
  optional-mod channels, read + write.
- **CropRotation dropdown yield previews + compact layout** (2026-07-11). Each dropdown option shows
  the % that pick would produce, so the best crop is visible without trying each: the collector emits
  a per-slot `cropYields` / `catchYields` (one `{ state, yieldPercent }` per catalog option, crop
  options varying the main crop with the catch fixed and vice-versa, via the same YieldCalculator).
  Crop + catch crop now share one line; the catch-crop 0 state is labelled "No catch crop" in English
  (the mod's own crop names follow the game language — no app-side localization yet).
  - **Known limitation (accepted, not a bug):** the 2-deep history window wraps modulo the rotation
    length, so in a **2-slot** rotation "two back" lands on the slot itself. The dropdown preview reads
    that self-reference from the slot's *stored* crop, not the hovered candidate, so a 2-slot preview
    can be slightly off from the committed value. Verified in-game and deliberately left as-is —
    rotations that short carry almost no history, so it's not worth special-casing (2026-07-11).
- **CropRotation is poll-driven, not subscribe-driven** (2026-07-11). The plan below assumed
  `CROP_ROTATIONS_CHANGED` was a usable change signal like TaskList's messages. It isn't:
  FS25_CropRotation only publishes it from its *multiplayer event path*. In singleplayer
  `CropRotationPlanner:addCropRotation` / `:removeCropRotation` take the server branch and mutate
  `cropRotations` directly with no publish, and the per-slot edits (`updateCropSelection`, ...) mutate
  the slot in place before sending the event — so a subscribe-only channel missed every live change (a
  new plan only appeared after save+reload). The channel instead diffs a cheap allocation-free
  signature of the planner in its per-tick `tick()` and marks itself dirty when it moves; the file is
  still only written on an actual change. This also moots the `CROP_ROTATIONS_CHANGED` id-collision
  landmine, since nothing subscribes to it.
- **Farm-page follow-ups** (2026-07-12) — three fixes found by using the finished page. Two of them
  close gaps *in this plan*, not against it: the plan asserted the not-installed contract as though it
  were a property of the system, but nothing enforced either half of it.
  - **Player heading on foot** — done, see [What already works](#what-already-works). (Was listed under
    Deferred.) The player's yaw sits half a turn from the yaw a vehicle heading is built from, so it
    takes a π shift before the shared compass conversion; without it the marker points backwards.
  - **"Absence of the file = not installed" is now actually true end to end.** It was aspirational on
    *both* sides. Mod: nothing ever deleted a channel file, so uninstalling FS25_TaskList /
    FS25_CropRotation left its json behind and the app kept rendering last session's data — the mod now
    deletes the files of unavailable channels once at startup (`ExportChannels.unavailableFileNames()`,
    run on the first update, when every mod has loaded and `isAvailable()` is trustworthy). Server: the
    watcher did reset the flow to null on a delete, but both send jobs were guarded with
    `if (data != null)`, so the null never reached the app — and the "not installed" state wasn't even
    representable on the wire. `ServerMessage.TaskList`/`.CropRotation` now carry a nullable payload and
    the server broadcasts the null. (Telemetry keeps its guard: there a missing file means "keep the
    last good value".)
  - **The map image is cached across page switches.** Each page hosts its own `MapPanel`, so entering or
    leaving a vehicle disposed one and composed the other from scratch — the remembered bitmap went with
    it and the map flashed blank while it re-fetched. Decoded images now live in a module-level cache
    outside composition.

---

## Goal

Today the terminal shows a vehicle dashboard, and renders "No vehicle connected" when you're on
foot (`App.kt` `Dashboard`). Add a **farm page** that carries the map and non-vehicle apps.

- Auto-switch to the farm page when leaving a vehicle, back to the vehicle page when entering one.
- Manual switch via the Menu button in the header's top left.
- Farm page hosts a large map plus new panels: **TaskList** and **CropRotation (planner)**.
- Both new panels **read** the mods' live data and **write back** to it (complete/create tasks,
  edit rotation plans). Writes mutate the mods' own state via their multiplayer events — VDTelemetry
  persists nothing of its own.
- The new data changes rarely, so it gets its **own files** on their own cadence, separate from
  `vdTelemetry.json`.

### Scope decisions already taken

- **Writes mutate the mods' own data.** No VDT-owned persistence layer, no new savegame store. The
  mods own their data; we drive their existing event API.
- **CropRotation = planner only.** Mirror `g_cropRotationPlanner.cropRotations` (the saved rotation
  plans). We do *not* sample the crop-history density map at the player's position, and we do *not*
  sweep `g_fieldManager:getFields()`. This keeps the channel purely event-driven with no per-frame
  cost. Position/field sampling stays available later (see [Deferred](#deferred)).

---

## What already works

Three things need no new design.

**The auto-switch signal exists.** `data.vehicle == null` is already what `App.kt` renders as "No
vehicle connected". `VDTelemetry:setCurrentVehicle` / `:clearCurrentVehicle`
(`VDTelemetry.lua`) are called by **FS25_additionalInputs** — nothing in this repo calls them. So
enter/leave detection needs **no mod change**.

**The Menu button is already rendered.** `Header.kt` draws `Icons.Filled.Menu` with no
`clickable`. It only needs wiring to a hoisted callback.

**The map already works on foot.** `MapPanel` takes its marker from `environment.pda.player`, which
`EnvironmentExporter.lua` fills from `ingameMap.normalizedPlayerPosX/Z` — the HUD's *controlled
entity* position, which tracks the player when out of a vehicle. The only vehicle-coupled input was
`heading`, passed from `vehicle.gps.heading` in `App.kt`. **Done:** `environment.pda.player` now also
carries `heading`/`headingUnit`, read from `ingameMap.playerRotation` (the same HUD pass that yields
the normalized position) and converted with the same `ValueMapper` transform as the vehicle heading,
so the farm-page marker rotates with the player.

---

## Blockers to fix first

### B1. Every command is dropped when there is no vehicle

`VDTelemetry:onCommand` early-returns whenever `self.currentVehicle == nil`:

```lua
local vehicle = self.currentVehicle
if vehicle == nil then
  self.debugger:debug("no current vehicle; ignoring command %s", tostring(cmd.type))
  return
end
```

Nothing from a farm page — completing a task, editing a rotation — can ever execute.

This is **a live bug today**, independent of this feature: `SetGpsLinesVisible` is documented in
`Protocol.kt` as targeting "a global client setting rather than the current vehicle", and it is
silently swallowed whenever you are on foot.

**Fix:** let a `VDT.CommandRegistry` handler declare `requiresVehicle` (default `true`), and gate
on that instead of unconditionally. Small, self-contained, and everything else depends on it.

### B2. `CommandWriter` does no XML escaping

`CommandWriter.kt` justifies this today:

> Tokens are fixed enum values and ids are ints, so no XML escaping is needed here.

That holds until a command carries a task name or detail string. An `&` or `"` in user text then
produces a file the mod's `XMLFile.load` rejects — and because `CommandChannel.poll` treats an
unparsable file as "nothing to do this tick", the failure is **silent and permanent** until the ring
rotates that command out.

**Fix:** escape attribute values in `render()`. Must land before the first string-carrying command.

### B3. Creates are not idempotent

`Protocol.kt` states the channel's invariant: commands carry an **absolute target state, never a
toggle**, because the file channel is lossy — an idempotent set-to-state is self-correcting where a
dropped or doubled toggle would desync.

"Add task" cannot be expressed that way.

In practice we are fine: the monotonic `id` plus the mod's `lastCommandId` watermark gives
**at-most-once** delivery within a session, and `CommandWriter`'s session-reset logic (file gone →
ids restart at 1, ring dropped) keeps that true across restarts.

**Action:** restate the invariant in `Protocol.kt` so the next reader doesn't assume redelivery is
always safe. Optionally, give creates a **client-generated uuid** so the mod can dedup independently
of the id watermark.

---

## Architecture

### Mod: a channel registry, not a second timer

Both target mods publish change events:

| Mod | Change signal | Source |
|---|---|---|
| TaskList | `MessageType.ACTIVE_TASKS_UPDATED`, `MessageType.TASK_GROUPS_UPDATED` | `main.lua` |
| CropRotation | `MessageType.CROP_ROTATIONS_CHANGED` | `MessageTypeExtension.lua` |

Because CropRotation is scoped to the planner, **both new channels are purely event-driven** — no
polling, no position sampling, no per-frame cost.

So rather than bolting a second interval onto `update(dt)`, add a **`VDT.ExportChannels`** registry
alongside the existing `VDT.Integrations`. Each channel declares:

```lua
{ fileName, isAvailable(), collect() }   -- plus markDirty(), called from the messageCenter subscription
```

`update()` writes any channel that is dirty. The existing telemetry file becomes the one channel
that is dirty every tick.

Both mods self-detect exactly like `integrations/EnhancedVehicle.lua` does — TaskList via the shared
`g_currentMission.taskList ~= nil`, CropRotation via its **env global**
`FS25_CropRotation ~= nil and FS25_CropRotation.g_cropRotationPlanner ~= nil` (the bare
`g_cropRotationPlanner` is `nil` from our env; see the CropRotation note). They belong in
`src/integrations/`, **not** `collect/`. If the mod isn't installed the file is never written, and
**absence of the file is the app's "not installed" signal** — which means a file left over from a
session where the mod *was* installed is stale and must be deleted (the mod does this once at startup;
see the 2026-07-12 progress note, which is where both halves of this contract were finally enforced).

Each channel file carries its **own `version` field**, evolving independently of
`VDTelemetry.VERSION`.

Files land beside the existing one, under the `modSettings/<modName>/telemetry/` subfolder:

```
modSettings/FS25_vdTelemetry/
  telemetry/
    vdTelemetry.json      (existing, ~100 ms)
    taskList.json         (new, event-driven)
    cropRotation.json     (new, event-driven)
  commands/
    commands.xml          (existing)
```

### Server: generalize `TelemetrySource`

`TelemetrySource` is currently one path → one `StateFlow<VdtData?>`, watching `path.parent` and
filtering events by filename. Three files in one directory means either three `WatchService`s on the
same dir or **one directory watcher dispatching by filename** — do the latter.

`ServerMessage` then gains `TaskList` and `CropRotation` variants, each broadcast on connect and on
change.

> **Do not** hang these off `VdtData` as extra fields. They arrive from different files on different
> cadences; merging them into one model means rebroadcasting the slow data at 100 ms, throwing away
> the exact property we want. Adding sealed subtypes is safe because the server serves the wasm
> bundle — app and server always ship together.

**Roadmap note:** this revives the machinery from the once-shelved *fast/slow channel split* — but for
a different reason than it was shelved for. That was a *rate split of the same data* and wasn't worth
the complexity. This is genuinely *different data* with a naturally different cadence. (The plan
originally asked for a line in `ROADMAP.md` so the shelving rationale didn't read as contradicted;
that file has since been deleted — `4dd6af5` — so this paragraph is the only record.)

### App: pages

Hoist a `Page` enum into `App`. A `LaunchedEffect(vehicle != null)` flips it on each **transition**,
while leaving a manual pick sticky until the next enter/leave — keying on the *boolean* rather than
the vehicle object gives this for free, and the first composition sets the initial page correctly.

The header's Menu icon toggles `Page` manually.

---

## Mod-specific notes

### TaskList (`FS25_TaskList`)

Clean integration. Global: `g_currentMission.taskList`.

- **Read:** `.taskGroups` and `.activeTasks` (keyed `groupId .. "_" .. taskId`).
- **Write:** call the existing wrappers `TaskList:completeTask(groupId, taskId)`,
  `:deleteTask(groupId, taskId)`, `:addTask(groupId, task, isEdit)` in `main.lua`. Each already does
  the MP-correct `g_client:getServerConnection():sendEvent(...)`. **Do not touch `activeTasks`
  directly** — that would desync multiplayer.

⚠️ `Task:getTaskDescription()` reaches into `getHusbandries()` / `getProductions()`. Those are cached
but do a **full scan on first call**. Fine on an event-driven write; would not be fine per-frame.

### CropRotation (`FS25_CropRotation`)

> ✅ **NOT blocked — re-diagnosed 2026-07-10 from the mod source.** The earlier "blocked" call was a
> misread. The probe checked the **bare** globals `g_cropRotationPlanner` / `g_cropRotation` and got
> `nil`, but those aren't server-only — they're globals in **FS25_CropRotation's own Lua
> environment**, not the shared `_G`. This is the exact mod-environment isolation that hid
> `FS25_TaskList.Task` (see the TaskList write-path fix). Both are created on the client too
> (`g_cropRotationPlanner = CropRotationPlanner.new()` runs on load, both sides —
> `CropRotationPlanner.lua:225`), and are reachable from our mod as
> **`FS25_CropRotation.g_cropRotationPlanner`** and **`FS25_CropRotation.g_cropRotation`**.
>
> The client's data is populated and stays live:
> - **Initial:** `SyncCropRotationPlannerEvent:readStream` sets `g_cropRotationPlanner.cropRotations`
>   on the client — its `connection:getIsServer()` guard is true for a normal client
>   (`SyncCropRotationPlannerEvent.lua:43-46`).
> - **On edits:** the broadcast `CropRotationEntryEvent:run` (invoked from `readStream` on the client)
>   updates the client's `cropRotations` **and** publishes `CROP_ROTATIONS_CHANGED`
>   (`CropRotationEntryEvent.lua:80-92` → `CropRotationPlanner:updateCropRotation` /
>   `:addDeleteCropRotations`). So the message fires *after* the data is current — the same
>   collect-on-message pattern TaskList uses. The `argc=0` the probe saw is fine; we re-read the
>   planner rather than reading message args.
>
> The GUI frame (`InGameMenuCropRotationPlanner.lua:94-118`) reads exactly these globals and renders
> on the client — proof they're populated there. **So CropRotation is implementable the same way as
> TaskList** (env-global read + subscribe to `CROP_ROTATIONS_CHANGED` + `collect()`), with writes
> driving the mod's own wrappers. Verify the env-global handle in-game before building (as we did for
> TaskList), and note it keys off the exact mod name `FS25_CropRotation`.

The `CropRotationPlanner` *class* is file-local, but the **instance** is `g_cropRotationPlanner`
(reachable as `FS25_CropRotation.g_cropRotationPlanner`); likewise `FS25_CropRotation.g_cropRotation`.

- **Read:** `FS25_CropRotation.g_cropRotationPlanner.cropRotations` — a list of
  `{ name, farmId, index, rotations = [{ state, catchCropState, yieldValue }] }`. `state` /
  `catchCropState` are fruit-type indices (**0 = fallow / no catch crop**). Resolve display names via
  `FS25_CropRotation.g_cropRotation:getPossibleCropStates()` and `:getPossibleCatchCropStates()` —
  each a list of `{ cropIndex, name }` built on the client in `onLoadMapFinished`
  (`CropRotation.lua:389-433`), and they include the special 0 states with i18n labels. Prefer these
  over `cropByFruitTypeIndex(state)`, which returns `nil` for fallow and a crop object rather than a
  display name.
- **Write:** drive the planner's own wrappers on `FS25_CropRotation.g_cropRotationPlanner` — they run
  in the mod's env (so `CropRotationEntryEvent` and `g_cropRotation` resolve) and each does the
  MP-correct `sendOrBroadcastEvent`, exactly like the TaskList wrappers:
  `:addCropRotation(name, farmId)`, `:removeCropRotation(cr)`,
  `:addCropRotationSelection(cr)` / `:removeCropRotationSelection(cr)` (append / drop a rotation slot),
  `:updateCropSelection(cr, rotationIndex, cropIndex)`,
  `:updateCatchCropSelection(cr, rotationIndex, catchCropIndex)`. Get `cr` via
  `planner:getCropRotationWithIndex(index)`. (The raw global `CropRotationEntryEvent` is still there
  as a lower-level fallback.)

⚠️ **Landmine.** `MessageTypeExtension.lua` sets `MessageType.CROP_ROTATIONS_CHANGED` by *counting*
existing `MessageType` entries rather than calling `nextMessageTypeId()`, so it never **reserves**
the id. Depending on mod load order another mod can be handed the same id. Harmless for us — a
spurious wake just rewrites a file — but **do not build anything load-bearing on that message being
exclusively CropRotation's.**

---

## Slicing

Four changes, each verifiable in-game on its own. The risky structural work (step 3) happens with no
new write semantics riding on it.

### Step 1 — `requiresVehicle` fix (mod only)

Fixes [B1](#b1-every-command-is-dropped-when-there-is-no-vehicle), including the live
`setGpsLinesVisible` bug. Everything else depends on it.

- `CommandRegistry`: handlers may declare `requiresVehicle` (default `true`).
- `VDTelemetry:onCommand`: gate on the flag instead of unconditionally.
- `GpsControl`: mark `requiresVehicle = false`.
- Spec coverage in `spec/CommandChannel_spec.lua` / `spec/GpsControl_spec.lua`.

### Step 2 — Pages + navigation (app only, no mod change)

- `Page` enum, hoisted into `App`; `LaunchedEffect(vehicle != null)` auto-switch.
- `Header` Menu icon → `onToggPage` callback.
- Farm page: large `MapPanel` (initially `heading = 0`, later the player heading) + placeholder cells.
- Verifies auto-switch end-to-end immediately, with zero new plumbing.

### Step 3 — Channels + multi-file server + TaskList read-only

The big structural one. Read-only keeps XML escaping out of scope.

- Mod: `VDT.ExportChannels` registry; `update()` writes dirty channels; existing telemetry becomes a
  channel.
- Mod: `src/integrations/TaskList.lua` — self-detect, subscribe to the two message types, `collect()`.
- Shared: `TaskListData` model + `ServerMessage.TaskList` variant.
- Server: one directory watcher dispatching by filename; a source + `StateFlow` per channel;
  broadcast on connect and change.
- App: `TelemetryRepository` gains a `taskList` flow; TaskList panel renders it (and "not installed"
  when null).
- Fixtures: `examples/json/taskList/*.json` + `:shared:jvmTest` decode assertions.

### Step 4 — Write paths

Escaping and create-dedup land together here.

- [B2](#b2-commandwriter-does-no-xml-escaping): escape attribute values in `CommandWriter.render()`.
- [B3](#b3-creates-are-not-idempotent): restate the `Protocol.kt` invariant; optional uuid on creates.
- `ClientMessage` variants for task complete/create/edit/delete; command handlers with
  `requiresVehicle = false` calling the TaskList wrappers.
- Then the same for CropRotation: read channel (`src/integrations/CropRotation.lua`, planner only),
  then planner writes via `CropRotationEntryEvent`.

---

## Open risks

- **MessageCenter on a dedicated-server client.** ✅ **Verified 2026-07-10** (temporary probe on the
  client). **TaskList:** both `ACTIVE_TASKS_UPDATED` and `TASK_GROUPS_UPDATED` fire client-side and
  `g_currentMission.taskList` is fully readable — Step 3 is safe. **CropRotation:**
  `CROP_ROTATIONS_CHANGED` fires client-side; the data globals only *looked* empty because they live
  in the mod's own environment — reachable via `FS25_CropRotation.g_cropRotationPlanner` (see the
  re-diagnosis in the CropRotation note above). Not a blocker; confirm the env-global handle in-game
  before building, same as we did for TaskList.
- **Mod-environment isolation.** FS25 gives each mod its own Lua env, so a third-party mod's *class*
  and `g_*` singletons (`FS25_TaskList.Task`, `FS25_CropRotation.g_cropRotationPlanner`) are **not**
  reachable as bare globals from our mod — only shared engine tables (`g_currentMission`,
  `MessageType`) and instances hung off them are. Reach a mod's own globals through the env global
  named after the mod. This is what caused both the createTask crash and the CropRotation misread.
- **Mod version drift.** ✅ **Handled** (2026-07-12). Both integrations read third-party internals, so
  each header now pins the version it was written against — **FS25_TaskList `1.2.0.1`**,
  **FS25_CropRotation `1.0.1.0`** — and states the fail-soft contract: guard every field read (and
  `pcall` the yield maths), because a throw in a collector takes the whole telemetry write down with
  it. The risk itself doesn't go away; a mod update can still empty a panel, and the pin is what tells
  the next reader where to look.
- **`CROP_ROTATIONS_CHANGED` id collision.** See the landmine above.

---

## Deferred

- **CropRotation position/field data.** Sampling the crop-history density map at the player's
  position (`historyStateManager.historyStates[i].map:getState(x, z, ...)`,
  `YieldCalculator:potentialYieldAtPosition(x, y, cropIndex)`,
  `YieldCalculator:getYieldMultiplier(...)`), or sweeping `g_fieldManager:getFields()` for a
  per-field table. Both need a timer (position changes as you drive) and in-game profiling. The
  channel registry supports it — a channel whose `markDirty()` is driven by a position bucket rather
  than a message.
- **VDT-owned data layer.** Notes / custom fields that the mods cannot represent. Explicitly out of
  scope for the four steps above: it needs a VDT-owned store and a read path the mod does not have
  today. The concrete motivating case is field→rotation assignment, below.

### Assigning a CropRotation plan to a field

**The first real use for VDT-owned storage.** CropRotation's planner stores rotation plans as a flat
list — `g_cropRotationPlanner.cropRotations` entries carry `{ name, farmId, index, rotations }` and
**nothing that ties a plan to a field**. The mod has no notion of "field 7 follows the *Heavy Soil*
rotation"; you read the plan and apply it by hand. That mapping is exactly the kind of data VDT can
own without fighting the mod.

Shape of it:

- **Store:** a VDT-owned map of `fieldId → rotation index` (plus, plausibly, "current position in the
  rotation" so the app can say *what to plant next* on this field). Persisted by VDTelemetry itself —
  savegame-scoped, since field ids and plan indices are both savegame-local.
- **Read side:** field ids come from `g_fieldManager:getFields()` / `getFieldIdAtPlayerPosition()`.
  Joining our map against `cropRotations[index]` gives the app a per-field "assigned rotation" view,
  and against the field's current fruit a "next crop" hint.
- **Write side:** assignment is VDT's own data, so it does **not** go through
  `CropRotationEntryEvent` — it is a VDT command against a VDT store, not a mutation of the mod.

Things to work out when this is picked up:

- **Persistence location and lifetime.** The command channel and settings XML both live under
  `modSettings/<modName>/`, but this is *savegame* state, not client settings — two savegames must
  not share a field→rotation map. Keying by savegame id inside `modSettings/`, versus writing into
  the savegame directory the way both target mods do (`TaskList:saveToXmlFile`,
  `CropRotationPlanner:saveToXMLFile`, both hooked off `FSBaseMission.saveSavegame`), is an open
  choice. The latter matches how the mods already behave and gets save/load timing for free.
- **Reading it back.** The FS25 Lua sandbox restricts `io.open` to write mode, which is why the
  command channel is XML (see `CommandChannel.lua`). A VDT-owned store the mod must **read** has the
  same constraint — so it is XML via `XMLFile.load`, not JSON, regardless of what we emit for
  telemetry.
- **Referential integrity.** Plans are referenced by `index`, and
  `CropRotationPlanner:addDeleteCropRotations` deletes by clearing `cropRotations[index]` — so an
  assignment can dangle when a plan is removed. Resolve dangling references on load, and treat a
  missing plan as "unassigned" rather than an error.
- **Multiplayer.** Unlike task/rotation edits, this state has no mod event to ride on. Either scope
  it to the local client (simplest, and consistent with telemetry being client-side only) or build a
  VDT sync event. Start local.
