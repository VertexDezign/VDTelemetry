# Farm page + per-mod telemetry channels

Plan for a second dashboard page covering everything that isn't the current vehicle, and the
mod/server/app plumbing needed to feed it from two optional third-party mods
(**FS25_CropRotation**, **FS25_TaskList**).

Status: **planned, not started.** Written 2026-07-09 against `main` @ `c1809e5`.

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
entity* position, which tracks the player when out of a vehicle. The only vehicle-coupled input is
`heading`, passed from `vehicle.gps.heading` in `App.kt`. On the farm page pass `0` (marker points
north), or add a player-rotation field to the environment collector.

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

Both mods self-detect exactly like `integrations/EnhancedVehicle.lua` does
(`g_currentMission.taskList ~= nil`, `g_cropRotationPlanner ~= nil`), so they belong in
`src/integrations/`, **not** `collect/`. If the mod isn't installed the file is never written, and
**absence of the file is the app's "not installed" signal**.

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

**Roadmap note:** this revives the machinery from the shelved
[Fast/slow channel split](ROADMAP.md) — but for a different reason than it was shelved for. That was
a *rate split of the same data* and wasn't worth the complexity. This is genuinely *different data*
with a naturally different cadence. Worth a line in `ROADMAP.md` so the shelving rationale doesn't
read as contradicted.

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

Globals: `g_cropRotation`, `g_cropRotationPlanner`. Note the `CropRotationPlanner` *class* is
file-local — only the instance is reachable.

- **Read:** `g_cropRotationPlanner.cropRotations` — a list of
  `{ name, farmId, index, rotations = [{ state, yieldValue, catchCropState }] }`. Crop `state`s are
  fruit-type indices; resolve for display via `g_cropRotation:cropByFruitTypeIndex(state)`.
- **Write:** the global `CropRotationEntryEvent`:
  `CropRotationEntryEvent.new(farmId, name, rotations, index, isUpdate, shouldDelete):sendOrBroadcastEvent()`.

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
- Farm page: large `MapPanel` (pass `heading = 0`) + placeholder cells.
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

- **MessageCenter on a dedicated-server client.** Telemetry is client-side only, and both mods sync
  their state to clients via initial-state events (`InitialClientStateEvent`,
  `SyncCropRotationPlannerEvent`), so the client *should* see updates. **Prove this in-game before
  committing to Step 3** rather than assuming it.
- **Mod version drift.** Both integrations read third-party internals. Pin the versions this was
  written against (CropRotation `1.0.1.0`) and fail soft — a missing field must not throw in the
  collector.
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
- **Player heading on foot.** Add a rotation field to `EnvironmentExporter` so the farm-page map
  marker points where the player looks, instead of always north.
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
