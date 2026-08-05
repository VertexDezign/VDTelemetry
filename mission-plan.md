# Mission app — accept and track contracts (issue #17)

Plan for a **Missions** app: the farm's available and running contracts, accepted and managed from the
terminal, with their locations on the map.

Status: **planned, nothing built**. Written 2026-08-05 against `main` @ `1c51871` (mod `VERSION 9`).

Scope for round 1, decided with the user before writing this:

- **All four client actions**: accept, accept-with-leased-vehicles, cancel a running contract, and
  dismiss (collect) a finished one. The issue says "accept and track"; the game exposes exactly these
  four to a client and they all go through one control file.
- **All base-game mission types** — the 16 registered in the extracted source (13 field, 2 forestry,
  1 rock). The per-type detail comes from the game's own already-localized rows, so breadth is nearly
  free (see "Detail rows, not a type switch").
- **Map: marker + field tint.** Each mission carries a normalized position and, for field missions,
  the farmland id — so `MapPanel` drops a contract marker and tints the field polygon `map.json`
  already carries. Point-located missions (deadwood, tree transport, rock) get the marker only.

Engine references are `file:line` into the extracted source bundled with `fs25-modding-skill`
(`references/lua-source/`), which is the authority here — check it before trusting a getter's name.

---

## Where this stands

Unlike ISOBUS (#58), this feature is **a new channel end to end**: file, watcher, `ServerMessage`,
store field, fixtures, plus a new command control and a new app. Nothing in the repo touches missions
today — the only hits for "mission" are `g_currentMission`.

What the game gives us, and it is a lot:

| Source | What it gives |
| --- | --- |
| `g_missionManager.missions` (`MissionManager.lua:367`) | every mission, on the client too |
| `getMissionsByFarmId(farmId)` (`:370`) | the filter the game's own contracts screen uses |
| `AbstractMission:getDetails()` (`AbstractMission.lua:556`) | **localized** title/value rows, per type |
| `MissionStartEvent` / `MissionCancelEvent` / `MissionDismissEvent` | the client → server actions |
| `MessageType.MISSION_GENERATED` / `MISSION_DELETED` / `MISSION_STATUS_CHANGED` | event-driven cadence |

`InGameMenuContractsFrame.lua` is the reference implementation for all of it — the list filter
(`:175-203`), the four buttons (`:573-607`), and the map framing (`:318-353`). Read it rather than
re-deriving; this plan follows it deliberately, so what the terminal shows matches what the in-game
screen shows.

---

## Core design

### 1. Detail rows, not a type switch

Sixteen mission types, each with its own extra state: harvest has a selling station and a crop, sow
has a crop, bale has round-vs-square, deadwood has tree counts, rock has rock counts. Modelling each
as typed fields means sixteen collectors and a Kotlin sealed hierarchy — and a modded mission type
still falls off the end.

**Don't.** `AbstractMission:getDetails()` (`:556`) already returns exactly what the in-game screen
prints: a list of `{title, value}` pairs, **already localized by the game**, overridden per type
(`HarvestMission:getDetails` adds crop + selling station, `DeadwoodMission:getDetails` adds the tree
counts and per-tree reward, `AbstractFieldMission:getDetails` adds field name + size). A finished
mission has its own set, `getFinishedDetails()` (`:563`), which is the reward breakdown.

So the channel exports **a small structured core** (the fields the app needs to sort, filter, colour,
place on a map and act on) **plus the game's detail rows verbatim**. Same spirit as #58's "sections
keyed by aspect presence, not a switch on `type`": one generic path that composes, degrades to
"nothing extra to show", and carries modded types for free.

`type` (`mission.type.name` — `harvest`, `sow`, `deadwood`, …) is still exported, as a **label and
icon hint**, not as a dispatcher.

### 2. Its own event-driven channel, with a slow tick

`missions.json`, registered in `ExportChannels` like `taskList`/`husbandry`. Two cadence drivers:

- **Event-driven** off `MessageType.MISSION_GENERATED`, `MISSION_DELETED`, `MISSION_STATUS_CHANGED`
  (`AbstractMission.lua:291,72,365`) — generation, acceptance, completion and deletion all land here.
- **Plus a slow interval** (~10 s). Two values move continuously without any message: `minutesLeft`
  (`:485`, derived from the environment clock) and `completion` (`:213`, pushed over the mission's
  update stream every ~2.5 s while running). A purely event-driven channel would show a frozen
  countdown.

### 3. Commands go through the engine's own events

The three engine events are already the client→server path with permission checks server-side
(`MissionStartEvent.lua:run` checks `manageContracts` before calling `startMission`). The control
sends them the same way `InGameMenuContractsFrame` does — `g_client:getServerConnection():sendEvent(…)`
— so **the mod needs no network event of its own**, and singleplayer takes the same path (local
connection). `MissionManager:startMission` itself is server-only (`MissionManager.lua:317` asserts),
so calling it directly would be a client crash: always the event.

---

## The channel: `missions.json`

New collector `src/collect/MissionExporter.lua`, channel `missions`, own `VERSION` (starting at 1),
`INTERVAL_MS = 10000`, self-registering. Own-farm scoping via the same filter the game uses.

**Which missions.** `getMissionsByFarmId(g_localPlayer:getFarmId())` (`:370`) — missions with no
`farmId` (available to anyone) plus this farm's — then the frame's own second filter
(`InGameMenuContractsFrame.lua:195`): keep it if `not mission:getWasStarted() or sameFarm`. That
drops contracts another farm is running in multiplayer, which is what the in-game screen does.

### Model

| Field | Source | Note |
| --- | --- | --- |
| `id` | `getUniqueId()` (`:371`) | the command handle; `mission<N>`, stable across a save |
| `type` | `mission.type.name` | `harvest`, `sow`, `deadwood`, … — label/icon hint only |
| `title` | `getTitle()` (`:571`) | localized |
| `description` | `getDescription()` (`:553`) | localized |
| `status` | `MissionStatus` (`MissionStatus.lua`) | `CREATED`/`PREPARING`/`RUNNING`/`FINISHED`/`DISMISSED` |
| `finishState` | `mission.finishState` | `SUCCESS`/`FAILED`/`TIMED_OUT`/`CANCELED`; omitted at `NONE` |
| `location` | `getLocation()` (`:550`) | "Field 12" / "Farmland 8", localized |
| `npc` | `getNPC()` (`:574`) | `{ name = npc.title, image = npc.imageFilename }` |
| `reward` | `getReward()` (`:442`) | the offered reward |
| `totalReward` | `getTotalReward()` (`:466`) | finished only: reward − vehicle costs − stealing + reimbursement |
| `vehicleCosts` | `getVehicleCosts()` (`:431`) | what leasing costs |
| `leasable` | `hasLeasableVehicles()` (`:626`) | drives the "lease" button |
| `completion` | `mission.completion` | `[0,1]`, running only |
| `minutesLeft` | `getMinutesLeft()` (`:485`) | nil when the mission has no end date |
| `extraProgress` | `getExtraProgressText()` (`:577`) | "3 trees remaining", running only |
| `fieldId` | `getFarmlandId()` (`AbstractFieldMission.lua:367`) | joins to `map.json` `fields[].id` |
| `areaHa` | `field:getAreaHa()` | field missions only |
| `posX`/`posZ` | `getWorldPosition()` (`:658`) → `MapExporter.normalizeCoord` | the map marker |
| `details` | `getDetails()` / `getFinishedDetails()` | the localized rows, verbatim |
| `own` | `mission.farmId == ownFarmId` | running-by-us vs. on offer |

Plus a small header:

```json
{ "version": "1",
  "limit":   { "active": 2, "max": 3 },
  "canManage": true,
  "missions": [ … ] }
```

- `limit` — `MissionManager.MAX_MISSIONS_PER_FARM` (`:5`, = 3) and the count
  `hasFarmReachedMissionLimit` (`:416`) walks. The app greys "accept" at the cap instead of firing a
  command the server will reject with `LIMIT_REACHED`.
- `canManage` — `getHasPlayerPermission(Farm.PERMISSION.MANAGE_CONTRACTS)` (`Farm.lua:8`,
  `FSBaseMission.lua:2052`), exactly the gate the in-game buttons use
  (`InGameMenuContractsFrame.lua:140`). A farmhand without the right sees the list, not the buttons.

**Fail-soft**: every engine read `pcall`-guarded, per the house rule. `getDetails()` is the one that
matters most — it is overridden 16 ways and some overrides touch lazily-resolved state
(`HarvestMission:getDetails` calls `tryToResolveSellingStation()`), so a single bad type must not take
the channel down.

### Multiplayer

Checked per type against `writeStream`/`readStream`, because "the client has the object" does not mean
it has the fields:

- **Core** (`AbstractMission.lua:156-208`): type, reward, reimbursement, status, `spawnedVehicles`,
  vehicle group, `farmId` (only once started), `activeMissionId`, `finishState` + stealing cost (only
  when finished), `endDate`. All present on a client.
- **`completion`** rides the *update* stream (`:209-217`), pushed while running. Present.
- **Field missions** send the field id first and `setField` on read (`AbstractFieldMission.lua:119-131`),
  so `field`, its name, area and indicator position all resolve client-side.
- **Forestry / rock**: `DeadwoodMission` syncs `numDeadTrees`/`numCutDownTrees`, `TreeTransportMission`
  syncs `numTrees`/`numDelivered`/`numDeleted` + the selling station, `DestructibleRockMission` syncs
  the rock list and `numRocksDestroyed`. So **their `getDetails()` rows are populated on a client** —
  the one thing that would have forced a field-missions-only round.
- **Not synced**: nothing we export. `getCompletion()` (the recompute, `:580`) is server-only work;
  we read the synced `completion` field instead, never call the getter.

---

## The commands: `src/command/MissionControl.lua`

Three command types, all `requiresVehicle = false` (they drive the farm, not the vehicle):

| Command | Params | Engine call |
| --- | --- | --- |
| `acceptMission` | `missionId`, `lease` | `MissionStartEvent.new(mission, farmId, lease)` |
| `cancelMission` | `missionId` | `MissionCancelEvent.new(mission)` |
| `dismissMission` | `missionId` | `MissionDismissEvent.new(mission)` |

All three: resolve `missionId` through `g_missionManager:getMissionByUniqueId()` (`:314`) — the same
id the read side exports, so the two cannot drift, exactly as `ProductionControl` resolves a point id
— then `g_client:getServerConnection():sendEvent(…)`.

Guards before sending, mirroring the frame:

- **Permission**: refuse and log unless `getHasPlayerPermission(Farm.PERMISSION.MANAGE_CONTRACTS)`.
  The server re-checks (`MissionStartEvent.lua:run`), so this is a clearer log, not the security
  boundary.
- **Ownership** on cancel/dismiss: the mission's `farmId` must be the local farm. Same rule the server
  enforces (`MissionCancelEvent.lua:run`).
- **Leasing**: `isSpawnSpaceAvailable()` (`:629`) first — the frame shows `warning_noFreeMissionSpace`
  and does not send. Refuse and log instead of spawning nothing.

**The reply.** All three publish their outcome on the message centre — `MissionStartEvent` with a
`MissionStartState` (8 values, `MissionStartState.lua`), cancel/dismiss with a bool. Round 1 subscribes
and **logs** the outcome; the app learns the result from the next channel write, which is event-driven
off `MISSION_STATUS_CHANGED` and therefore lands within a tick of the action. A user-visible failure
toast ("contract limit reached", "no permission") needs a mod→app reply path the repo does not have
yet — noted as an open question, not built here.

---

## Map: marker + field tint

`map.json` already carries every field as a polygon keyed by farmland id, in the same normalized
frame. So the map work is app-side only — **no change to `MapExporter`**:

- `MapPanel` draws a contract marker at `posX`/`posZ` for every mission in the channel, styled by
  status (on offer vs. running by us).
- When `fieldId` matches a `MapField.id`, tint that polygon instead of leaning on the marker alone —
  the polygon is already being drawn, so this is a colour decision, not new geometry.
- The Missions app's list and the map select together: picking a contract centres the map on it, the
  way `InGameMenuContractsFrame:updateDetailContents` (`:318-353`) frames its own mini-map.

---

## App shape

`MissionsApp` in `AppRegistry` (base-game data → `isAvailable` stays true), plus:

- **`MissionsPanel`** — two sections, matching the game's own split: *available* (`CREATED`) and
  *active* (`PREPARING`/`RUNNING`/`FINISHED`). Per row: NPC portrait, location, reward, time left,
  and the finish-state indicator. Detail pane: description, the `details` rows, progress bar +
  `extraProgress` while running, leased-vehicle cost when offered.
- **Buttons** gated on `canManage` and the `limit`: Accept / Accept with equipment / Cancel / Collect.
  Cancel goes through `ConfirmDialog` — it forfeits a contract, the same reason the game asks
  (`InGameMenuContractsFrame:onButtonCancel` → `YesNoDialog`).
- **A widget** for the dashboard: active contracts with their completion, and a count of what is on
  offer.
- **An alert rule** candidate (not round 1): a running contract about to time out.

---

## Sequencing

1. **This plan** — doc commit.
2. **Mod, read side**: `MissionExporter.lua` + `src/model/MissionModel.lua` + `spec/Mission_spec.lua`;
   register the channel and its settings entry. Then **ask for one capture** with a couple of
   contracts on offer and one running (the working cadence from #58: collector → capture → assert
   against the capture, not against inline JSON).
3. **Shared + server**: `model/Mission.kt`, `VdtParser.parseMissions`, `ServerMessage.Missions`,
   watcher registration + broadcast job, `MissionModelTest` over the capture.
4. **App, read side**: `MissionsApp` + `MissionsPanel` + store field + widget.
5. **Write side**: `MissionControl.lua` + specs; `ClientMessage.AcceptMission`/`CancelMission`/
   `DismissMission`; `CommandWriter` cases; the four buttons.
6. **Map**: markers + field tint in `MapPanel`.
7. **In-game validation** (SP first, then MP if available) — accept a contract, watch the status
   change land, cancel one, collect one.

---

## Open questions

- **Command outcomes have nowhere to go.** The engine answers every action with a state
  (`MissionStartState` has 8 values, five of which are ordinary refusals a user should see). The
  channel's next write shows the *result* but never the *reason* a nothing-happened action failed.
  Options: a `lastCommand` block on the channel (cheap, fits the existing one-way plumbing), or a
  real mod→app reply channel (bigger, and other controls would use it). Worth deciding before the
  write side is built.
- **Mission vehicles.** An accepted-with-equipment contract spawns machines at the shop, and
  `mission.vehicles` is on the object. Showing them (and where they are) is a natural round 2.
- **How much detail belongs on a driving screen.** The panel above is a menu; the widget is the thing
  someone glances at while working. The split needs the same review the ISOBUS layout got.
- **Contracting (`MANAGE_CONTRACTING`)** — the "work for another farm" system is a separate
  permission and a separate feature. Explicitly out of scope.
