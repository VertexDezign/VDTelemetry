# Loader tool position — angle and height of the loader tool (issue #169)

The issue asks for the positions of loaders and shovels, shown in a panel of their own and in the ISOBUS
panel when the loader or its tool is selected, with a button to re-zero the shovel so different tools can
all be aligned to the ground.

Status: **steps 1–4 built**, step 5 (in-game validation) open. The aspects (v30) are captured on seven vanilla SP
machines under `examples/json/telemetry/vanilla/loader/`; the reference store, command, `LoaderSection` (ISOBUS) and the
standalone Loader app/widget are unseen in game.

The model is [Tool Inclination Helper](https://www.farming-simulator.com/mod.php?mod_id=308809)
(timmeey86, Apache-2.0, [source](https://github.com/Timmeey86/FS25_ToolInclinationHelper)), which the user
named as where this should end up: an angle against the horizon, a distance to whatever is under the tool,
and a stored reference so "level" means level for *this* tool.

Decided with the user before this was written:

- **Readout is world-referenced.** The headline is the tool's angle against the horizon (°) and its
  distance to whatever is under it (m), the way a real loader display reads. Each cylinder's travel (%)
  is a secondary readout.
- **"Level" comes from a manual reference**, set by a button: put the tool where you want zero and tap.
  There is no automatic detection (see below for why).
- **The reference lives in the mod**, in `modSettings/FS25_vdTelemetry/loaderReferences.xml`, keyed by the
  tool's *model*. It is not kept in app storage, because then every device would have its own zero. It is
  not kept in the savegame either. The offset is a fact about the tool's geometry, so every copy of that
  shovel in every save needs the same one. The savegame also belongs to the host, and writing it would mean
  adding a vehicle specialization.
- **Every loader joint type is in scope**: tractor front loaders, wheel loaders, telehandlers and skid
  steers. They share joint types and moving-tool axes, so one aspect covers all of them.

---

## Why "level" cannot be read off the game

No node on a tool is guaranteed to lie parallel to the shovel floor or the fork tines.

- `Shovel.dischargeInfo` measures its tip angle as `acos(dy)` of its own node's Z axis against world up,
  and `shovelNode#maxPickupAngle` gates pickup on a different node in the same way. Both are thresholds
  for game mechanics, and both are only present on a `Shovel`. A bale fork, pallet fork or grab has
  neither.
- Tool Inclination Helper measures the tool's **root node**, and its whole UX rests on the reference
  hotkey for exactly this reason.

Trying to detect level automatically from the shovel data is a FUTURE.md item, to be judged against real
captures. It is not part of this round.

---

## Mod side

### 1. `loaderTool` aspect: on the tool

`src/collect/aspects/LoaderTool.lua`. It is present on an object whose **active input attacher joint**
(`getActiveInputAttacherJoint().jointType`) is one of `JOINTTYPE_FRONTLOADER`, `TELEHANDLER`,
`WHEELLOADER`, `SKIDSTEER` or `LOADERFORK`.

That is a structural test. Tool Inclination Helper matches on `storeData.category` (`frontLoaderTools`,
…) instead, but the category is a shop concept, and a modder who files a tool under the wrong shop
category has not changed the way it hitches. (`attachableFrontloader` is the joint between the loader and
the tractor, not between the loader and the tool, so it is excluded.)

| Field | Meaning |
| --- | --- |
| `pitch` | Degrees. The tool's `rootNode` Z axis against the horizon (`MathUtil.directionToPitchYaw`), positive nose-up. Raw, with no reference applied. |
| `distance` | Metres from the tool to the first thing below it that is neither the tool nor the rig. Negative if it is buried. Absent if the ray hits nothing within 10 m. |
| `joint` | Which loader joint it hangs on: `FRONTLOADER`, `TELEHANDLER`, `WHEEL_LOADER`, `SKID_STEER`, `LOADER_FORK`. |
| `reference` | `{ pitch, distance }` as stored for this tool model. Absent until set. **Step 2.** |

The distance follows Tool Inclination Helper: one synchronous `raycastAll` straight down from 0.5 m along
the root's Z axis, over `TERRAIN + STATIC_OBJECT + VEHICLE + DYNAMIC_OBJECT`. The callback skips any hit
in the rig's or the tool's own `vehicleNodes`. If nothing is below, it casts upwards and negates the
result. That is how it reads a pallet, a bale or a trailer edge rather than only the terrain.

The rig is the *root* vehicle (`rootVehicle`), not just the attacher, so a front loader's own arm cannot
answer the ray.

**Raw plus reference, never a pre-subtracted value.** That lets the app say "no reference set" instead of
showing a root-node angle as though it were level, and it lets a test check the arithmetic against a
capture.

**Multiplayer:** node transforms are interpolated on a client (`Cylindered:onReadUpdateStream` →
`networkInterpolators`), and collision is client-side, so both readings should hold on a client. This is
argued from the source and still to be checked on a client capture.

### 2. `loaderCylinders` aspect: on the machine with the cylinders

`src/collect/aspects/LoaderCylinders.lua`. (Planned as `loaderCylinders`; renamed because a tool with a clamp of
its own carries it too, and that is not an arm.) It is present on any object whose `spec_cylindered.movingTools`
include one whose `axis` starts with `AXIS_FRONTLOADER_`. That is the loader implement on a tractor, and
the vehicle itself on a wheel loader, telehandler or skid steer. Cranes (`AXIS_CRANE_*`) are excluded,
since forestry is its own issue.

```
loaderCylinders: [ { role, axis, travel } ]
```

- `role` is derived from the axis: `ARM` → `LIFT`, `ARM2` → `TELESCOPE`, `TOOL` → `TILT`, and
  `TOOL2`..`TOOL5` → `AUX` (grab, clamp, top-hold: the meaning is tool-specific, so it stays a slot rather
  than a guess).
- `travel` is `Cylindered.getMovingToolState(object, tool)` (a spec function, not a vehicle method), clamped
  to 0..1, and only for tools with both limits set. **Oriented** so 1 is raised / curled back / extended: the
  engine's 0..1 ran backwards on every captured arm, so the mod checks each node's X axis against the
  machine's and turns lift and tilt round; a lift/tilt/telescope whose direction cannot be told is dropped. A tool with an unbounded rotation is left out, because its "state" is a raw angle and the panel
  would draw it as a fraction.
- Only tools with `hasRequiredConfigurations` are kept.

### 3. Reference command

`src/command/LoaderControl.lua`: `SET_LOADER_REFERENCE` / `CLEAR_LOADER_REFERENCE`, addressed by node
path like `SelectionControl`, since the tool is usually two levels down (tractor → loader → tool) and
beyond `ControlTarget`'s reach.

- **Set** stores the tool's current raw `pitch` and `distance`.
- **Clear** removes them.

Both go to `loaderReferences.xml` straight away, not at savegame time: the reference is not savegame
data, and a crash should not lose it.

The key follows Tool Inclination Helper's `buildVehicleIdentifier`: `customEnvironment|configFileNameClean`
for a mod tool, and `Giants|<i3dFilename>` for a base-game tool. Same model means same reference, on any
save.

The command is local to the machine it runs on. The command channel is per-player already (the mod polls
its own folder), so on a multiplayer client it is that player's zero, which is right for a display
preference.

The XML is loaded once at startup into `VDT.LoaderReferences` and read by the aspect. The aspect itself
does no file I/O.

### 4. Version and models

- `VDTelemetry.VERSION` 29 → 30.
- Lua `---@class` for both aspects in `src/model/AspectModel.lua`.
- Kotlin `LoaderTool` / `LoaderJoint` / `LoaderCylinder` / `LoaderCylinderRole` (`model/Loader.kt`); `LoaderReference` comes with step 2 on `Vehicle` and `Implement`.
- Both new collector files go into `sourceFiles` and `Aspects.apply`.

---

## App side

### 5. `LoaderSection` in the ISOBUS panel

`IsoBusMachine` gains `loaderTool` / `loaderCylinders`, and `hasSection` grows both.

The loader is **two or three machines on one screen**: the tractor, the loader (the cylinders) and the
tool (the angle). This is the `CombineSection` precedent: resolve the pair from the whole rig, so tapping
between the loader and the tool on the diagram changes what the generic controls address, not the screen.
The tool is found as the `loaderTool` node; the arm is its nearest ancestor (or the vehicle) carrying a
`loaderCylinders`.

Layout, with the side view facing left (design rule):

- **A side-view glyph**: the arm at its lift travel, the tool drawn at its referenced angle. It is
  own-drawn vector geometry, not art, because it moves.
- **The angle as the headline figure**, with a level marker. "Level" vs "tilted" is told by **marker
  position, a glyph and a word**, never by hue: inside ±1.5° the readout reads `LEVEL`, and outside it, it
  reads the signed angle with an up/down `Icon`. The tolerance is a constant to tune in game.
- **Distance** in metres below it, with the same reference applied.
- **Cylinder travel** as small bars, one per `loaderCylinders` tool, labelled by role.
- **Set level** button, plus *Clear* once a reference exists. With no reference the root node is the
  level (the user's call, 2026-10-03: it matched every tool they had), and the caption says
  "default level" so the two are never confused.

### 6. Dedicated `LoaderPanel` / Loader app

The same section as a standalone widget and launchable app (`AppRegistry`, `BuiltinWidgets`), following
the rig the way `IsoBusWidget` does but showing only the loader. Shared composable, two hosts. This is the
"dedicated panel" the issue asks for.

---

## Captures wanted (the user's cadence: build the collector, capture, assert against it)

1. Tractor + front loader + **shovel**: flat on the ground, lifted and tipped, then reference set.
2. Tractor + front loader + **bale fork / pallet fork**, over a bale or pallet (the raycast must stop on
   it, not on the ground).
3. **Wheel loader** with shovel (arm and tilt on the vehicle itself).
4. **Telehandler** (`TELESCOPE` role).
5. **Skid steer**.
6. Any of these from an **MP client**.

The existing `tractor_frontloader*.json` predate both aspects and stay as they are.

---

## Deferred (to FUTURE.md once built)

- **Forklift forks.** On a forklift the forks are a *component*, not an implement. Tool Inclination
  Helper finds them by loading the vehicle's i3d as XML and string-matching shape names
  (`fork`/`tine`). That is fragile, and a lot of machinery for one vehicle class.
- **Detecting level automatically** from `Shovel` data (see above).
- **An in-game hotkey for Set level.** Input belongs to FS25_additionalInputs, so it would be an
  additionalInputs change.
- **Coexisting with Tool Inclination Helper**: reading its stored references, so a player who calibrated
  there does not have to do it twice. Its settings file format is its own and could change.

## Steps

1. Mod: `loaderTool` (pitch + raycast distance) and `loaderCylinders` aspects, model, Kotlin model, v30.
   Busted specs. Ask for captures 1–5.
2. Mod: reference store and `SET/CLEAR_LOADER_REFERENCE`, server `CommandWriter` and `ClientMessage`
   variants.
3. App: `LoaderSection` in `IsoBusPanel`, with tests against the captures.
4. App: standalone Loader widget and app.
5. In-game validation, SP and MP client. Move the leftovers to FUTURE.md and delete this plan.
