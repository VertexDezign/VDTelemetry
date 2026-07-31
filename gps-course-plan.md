# GPS course, guidance run-screen and section view

Written 2026-07-31 on `main` for issue
[#43](https://github.com/VertexDezign/VDTelemetry/issues/43) ("GPS Course"), from a read of the
FS25 game source (`fs25-modding-skill/references/lua-source`), the Precision Farming LUADOC
(`references/luadoc-index`) and the current map/vehicle code on both sides of the repo.

The issue asks for four things:

1. draw the course on the map,
2. show active working units (section control, e.g. a sprayer),
3. a Map Panel rework — 3D view? an app-side "worked" layer, because not all work shows up in the
   ground layers (a tedder leaves no trace there),
4. fold the Navigation widget into the map,

with two real-terminal references (John Deere G5, Trimble GFX-750). Those photos are all the same
screen: a vehicle-centric map, the guidance lines, coverage painted behind the machine, a lightbar,
a section strip and a few big numbers. This plan gets there in that order.

**Scope decided with the user (2026-07-31):**

- §1–§3 (course channel → draw it → guidance readouts → course-up) are the **scope for now**.
- §4 (section view) and §5 (coverage) are planned here but not yet scheduled. The coverage raster
  is decided to live **server-side**, not in the mod — see §5.
- §6 (3D) stays a maybe, and the plan says why 2D course-up gets most of the way there.

**Status (2026-07-31, branch `43-gps-course`):**

- §2's app half — **done and confirmed in the app** (commit `c106372`): the `MapProjection` refactor
  and the navigation strip on the map. Its lightbar and progress readout were blocked on §1's live
  state and are the remaining piece; see §2's "How it landed".
- §1 — **done, not yet validated in game.** Both halves (the `gpsCourse.json` geometry channel and
  the `vehicle.gps.course` live state) plus the course drawn on the map. Mod export version is now
  **7**. See "How it landed" below and the in-game checks at the end — the deviation *sign* is the
  one that genuinely needs a tractor.
- §3 (course-up) — not started.

Every game-source claim is cited `file:line` against the bundled extracted source. Precision
Farming is cited by LUADOC section, since PF's own Lua is not in the bundle — those are marked as
needing an in-game check.

## What the game already gives us (the reason this is worth doing)

FS25's steering assist is not an AB line, it is a **fully generated field course** hanging off the
vehicle spec: `vehicle.spec_aiAutomaticSteering.steeringFieldCourse`
(`AIAutomaticSteering.lua:83`). It carries everything the terminal screens in the issue draw:

| What | Where | Notes |
|---|---|---|
| every guidance line | `.fieldCourse.segments[i].positions` = `{{x,z},…}` | `FieldCourse.lua:61-73` also sets `index`, `segmentId`, `length`, `headlandIndex` |
| line kind | `segment.isHeadlandSegment` / `.isIslandSegment` | the game's own debug draw colors by these — `FieldCourse.lua:138-150` |
| **which lines are worked** | `.segmentStates[i]` (bool) | `SteeringFieldCourse.lua:11-14`; set after 2.5 s of steering on a line (`:157-160`), saved to the savegame (`:63-72`) |
| the line you are on | `.currentSegmentIndex`, `.currentSegmentIsLeft` | `SteeringFieldCourse.lua:16-18`, recomputed by proximity in `:99-163` |
| the actual line to follow | `.currentSegment` | already a `sideOffset`-shifted clone when the setting is non-zero (`SteeringFieldCourse.lua:174-182`) — so cross-track error measured against it is the truth |
| field boundary + islands | `.fieldCourse.courseField.boundaryPositions`, `.islands[].rootBoundary.boundaryLine` | `FieldCourseField.lua:75`, `:128-145`; the *detected* boundary, finer than our farmland polygon |
| course settings | `.fieldCourseSettings` | `implementWidth`, `numHeadlands`, `sideOffset`, `skipNumLines`, `workDirection`, `headlandsFirst` — `FieldCourseSettings.lua:5,6,43` |
| engaged / available | `spec.steeringEnabled`, `getAIAutomaticSteeringState()` | `AIAutomaticSteering.lua:88`, `:544` — `DISABLED / AVAILABLE / ACTIVE` (`:14-18`) |
| working width | `vehicle:getAttacherToolWorkingWidth()` | `AIAutomaticSteering.lua:413-429` (max over AI markers / work areas) |

So "draw course on map" is a transport problem, not a computation problem. And `segmentStates` is a
**free, game-authoritative worked-area signal** — it is a coarse (line-granular) answer to the
tedder problem in bullet 3 that costs us nothing, long before any raster exists.

### Three constraints found while reading

- **`lastDistanceToEnd` is server-only.** It is computed inside `if self.isServer` in
  `AIAutomaticSteering.lua:251-296`, so on an MP client it stays 0. We compute distance-to-end
  ourselves from `currentSegment` instead of exporting theirs — same number everywhere.
- **The course exists on clients, but asynchronously.** `onReadStream` (`:198-206`) syncs settings
  + boundary and `FieldCourse.readStream` (`FieldCourse.lua:80-100`) *re-runs the generator*
  locally, so `segments` can be briefly empty right after a course arrives. `segmentStates` are
  streamed server→client (`AIAutomaticSteering.lua:218-223`), and `currentSegmentIndex` is computed
  locally for the entered vehicle (`:226-248` runs `updateVehicleData` when
  `isServer or isActiveForInputIgnoreSelection`). Everything we need is therefore available to a
  client — collect defensively, never assume `segments` is populated.
- **A client cannot choose the line.** `AIAutomaticSteeringStateEvent:run` calls
  `setAIAutomaticSteeringEnabled`, which on the server overwrites the passed `segmentIndex` with its
  own proximity pick (`AIAutomaticSteering.lua:473-479`). So "tap a line in the app to snap to it"
  cannot work in MP; see Deferred.

## The contract

Split by change rate, the way `map.json` and `mapVehicles.json` already are.

### `gpsCourse.json` — new export channel, geometry only

Rewritten only when the course object itself changes: entering a field, an implement-width change,
an AI-settings change (`AIAutomaticSteering.lua:307-353` regenerates on exactly those). Everything
normalized `[0,1]` with `VDT.MapExporter.normalizeCoord` (`MapExporter.lua:53`) and thinned with
`VDT.MapExporter.decimate` (`:64`) — headland rings follow the boundary at terrain-detail
resolution, so without thinning a 5 ha field is tens of thousands of points.

```jsonc
{
  "version": "1",
  "courseId": "…",           // identity+settings fingerprint; joins to the live state below
  "implementWidth": 6.0,      // meters; app divides by mapData.terrainSize for the swath stroke
  "numHeadlands": 2,
  "sideOffset": 0.0,
  "workDirection": -1,
  "boundary": [x, z, …],      // flat, like MapFieldModel.polygon
  "islands": [[x, z, …]],
  "segments": [
    { "i": 1, "kind": "line", "p": [x, z, x, z] },
    { "i": 2, "kind": "headland", "headlandIndex": 1, "p": [x, z, …] }
  ]
}
```

`kind` is a string token (`line` / `headland` / `island`), like `MapPoiModel.type` — an unknown
future kind must render as a plain line, not break the parse.

### Live state rides on the main telemetry `vehicle.gps`

The fast-changing part is small and belongs where the 10 Hz already is, so the course file is
written once per field and the lightbar still updates live:

```jsonc
"gps": {
  "enabled": true, "active": true, "heading": 271, "headingUnit": "°", "linesVisible": true,
  "course": {
    "courseId": "…",         // stale-geometry guard: ignore indices when it differs
    "segmentIndex": 12,
    "isLeft": false,
    "deviationM": -0.14,      // signed cross-track, + = right of the line
    "distanceToEndM": 83.5,
    "workedCount": 23,
    "segmentCount": 47,
    "worked": "0f3a…"         // hex bitmask over segment indices
  }
}
```

`deviationM` and `distanceToEndM` come from `SteeringFieldCourse.getClosestPositionSegment`
(`SteeringFieldCourse.lua:202`) against `currentSegment` — a static function we can call on the
course we already hold, over one segment's few points, so it is cheap at telemetry cadence. Sign the
deviation with the 2D cross product against the segment direction.

Bumps `VDTelemetry.VERSION` 6 → 7, with `SupportModel.lua` and the Kotlin `Gps` in the same commit
(CLAUDE.md rule).

## §1 — The course channel, and drawing it

**Mod.** `src/collect/GpsCourseExporter.lua` + `src/model/GpsCourseModel.lua` (annotation-only) +
`spec/GpsCourseExporter_spec.lua`; registered into `VDT.ExportChannels`
(`ExportChannels.lua:70`) with a `tick` that polls the current vehicle's course fingerprint (table
identity + `#segments` + a settings hash) every ~500 ms and `markDirty`s on change. Extend
`VDT.SupportSystems.collectGps` (`SupportSystems.lua:11-29`) with the `course` subtree above.
Sourced after `MapExporter.lua` (it uses its normalization helpers) and before `VehicleExporter.lua`.

Gotchas to honour: guard every engine read (the file is written from `writeDirty`'s pcall, but a
half-built model is worse than none); return `nil` from `collect` when there is no course, so the
channel simply does not write; the channel is user-configurable (not `hidden`, not
`latencyCritical`), default on.

**Shared / server.** `model/GpsCourse.kt` + `ServerMessage.GpsCourse`, watched and broadcast like
`map.json`; `VdtStore.gpsCourse`. Fixtures under `examples/json/gpsCourse/` (one straight-line
course, one with headlands + an island) and a decode test in `shared/src/jvmTest`.

**App.** Draw in `MapDataOverlay` (`MapPanel.kt:633`), in the same normalized space as the field
polygons:

- unworked line: thin stroke; worked: filled at low alpha with the swath width
  (`implementWidth / terrainSize * side * scale`) so the map reads as coverage strips, which is what
  the reference photos actually show;
- current segment: bright, thicker, drawn last;
- headland segments dashed, island segments distinct;
- the course boundary as a subtle outline (it is more accurate than the farmland polygon under it).

Build the `Path`s in `remember(course)` exactly like `fieldPaths` does, so pan/zoom never rebuilds
them. Add a "Course" toggle to the filter popover, persisted per placed tile through
`WidgetSettings` like `KEY_SHOW_FIELDS`.

### How it landed (2026-07-31)

Close to the shape above, with three decisions worth recording:

- **`courseId` is a counter, not a content hash.** It is only ever a join key between two files
  written by the same process, so a monotonic id bumped whenever the course table changes is enough —
  and it is free, where hashing the geometry would cost a walk per poll. The mod publishes the new id
  the moment it notices; the file follows on the next flush, and the app ignores indices whose id it
  has no geometry for, so the gap shows as a briefly unhighlighted line rather than a wrong one.
- **The change poll lives in two places.** `refresh()` runs from the channel's `tick` (500 ms) *and*
  from the 10 Hz state collector. The tick alone would leave the id up to half a second stale on the
  telemetry; the collector alone would never notice the course going away, because it only runs while
  there is a vehicle with one.
- **No course publishes an empty file rather than skipping the write.** `collect()` returning nil
  means "skip", which would leave the last field's course on disk for the app to keep drawing after
  the driver has left. The empty model (`courseId: ""`) is the mod saying so out loud.

`lineState` is the one piece of real logic: closest point on the polyline, cross-track error signed
against the game's own left (`SteeringFieldCourse.lua:174-182`) after orienting the line along travel,
and remaining length walked forward from the hit point. It is pure — the engine only supplies a
position and a heading — so `spec/GpsCourse_spec.lua` covers it directly, including the
drive-it-backwards case. The worked bitmask is pinned from both ends (`GpsCourseModelTest`), since it
is the only piece of wire format the two sides must agree on bit for bit.

The app draws it under the existing overlays: swath bands at `implementWidth / terrainSize` (a real
width, so it grows with zoom like the ground it covers), green where worked, the current line in red,
headlands blue and islands amber, with the course's own detected boundary outlined under everything.
Toggle in the filter popover, persisted per placed tile.

## §2 — Navigation into the map, and a lightbar

**First, a refactor that everything after this depends on.** `MapPanel` repeats the projection
`norm * side * scale + applied` in at least three places — the tap hit-test (`MapPanel.kt:480-493`),
the zoom-scaled `graphicsLayer` (`:495-501`) and the player marker (`:559-563`) — plus once more
inside `MapDataOverlay`. Extract a single `MapProjection` value type with `toScreen(norm)` /
`toNorm(screen)` and route every overlay through it. Course-up (§3) and any later tilt (§6) are then
a change to one object instead of a change to every draw site.

**Then the integration.** The `Navigation` widget's content (heading + compass letter, steering-assist
lamp, AI lamp, the guide-lines toggle — `Navigation.kt`) becomes an optional strip *inside* the map
panel, off by default, enabled per instance through `WidgetConfig`. The standalone widget stays: it
is still the right thing on a grid page without a map.

**And the part that makes it a terminal:** a lightbar driven by `deviationM` (LED-style, ±1 m full
scale, with the numeric offset in cm), `distanceToEndM` counting down to the headland, and a
progress readout — "line 12/47 · 23 worked" plus percent, which is genuinely useful and free from
the data in §1.

### How it landed (2026-07-31, commit `c106372`)

The refactor and the strip went in together — they touch the same file, and the strip is the reason
the refactor was worth scoping. `MapProjection` now owns `toScreen` / `toNorm` / the visibility cull
and the centring math, with the zoom-around-a-focal-point helper deliberately left *outside* it: the
pinch and wheel handlers live in a `pointerInput(Unit)` that never restarts, so anything they captured
from composition would go stale on a resize.

The strip is captionless (the lamp gained a `showLabel` flag rather than being duplicated), reads out
a bearing on foot as well — the map already unifies vehicle-GPS and player heading — and is off by
default per placed tile.

**Still open here:** the lightbar and the "line 12/47 · 23 worked" readout. Both now have their data
(§1 landed `deviationM`, `distanceToEndM`, `workedCount`/`segmentCount`), so this is app-only work.

## §3 — Course-up

Rotate the map to the vehicle's heading with the marker sitting ~⅓ up from the bottom — the
standard guidance view, and the single change that makes the panel *look* like the reference
displays. With §2's projection in place this is a rotation folded into `MapProjection`, plus
counter-rotation for the constant-size labels and markers that are deliberately drawn outside the
zoom-scaled layer. Per-instance config (`north-up` / `course-up`), default north-up so existing
dashboards do not change under people.

Heading is already smoothed for the player marker (`animHeading`, `MapPanel.kt:227-241`) — reuse
that value, or the whole map judders at 10 Hz.

## §4 — Section view (issue bullet 2) — *planned, not scheduled*

**Finding: the base game has no section control.** There are two real sources, and the user's
instinct is right about where the interesting one is:

- **Base game, on/off:** `spec_variableWorkWidth` — `sections[i].isActive` with `sectionsLeft` /
  `sectionsRight` and per-section `width` (`VariableWorkWidth.lua:89-119`, `:341`). We already
  export the aggregate as `workWidth` (`aspects/Work.lua:33-52`); the per-section array is the same
  read, one level deeper.
- **Precision Farming, per-section rates:** the internal `FS25_precisionFarming` divides each work
  area into ~2 m sub-sections and keeps a live per-section picture on it —
  `workArea.subSectionData[i]` with `nitrogenLevel` / `nitrogenTargetLevel`, `phLevel` /
  `phTargetLevel`, `soilTypeIndex`, `fruitType`, `growthState`, `isValid` and `lastDetectionX/Z`
  (LUADOC `Specializations/ExtendedSprayer.md` → `updateWorkAreaWidth`, refreshed per travelled
  distance in `onUpdate`). That is variable-rate data rather than on/off shutoff, but it is exactly
  the strip a real terminal draws across the boom, and `lastDetectionX/Z` even gives each section a
  world position to paint on the map.

**The good news for us:** PF hangs `subSectionData` off the vehicle's own base-game
`spec_workArea.workAreas[i]` tables, so it is readable **without** reaching into PF's Lua
environment — the trap that has bitten this repo three times (see `PrecisionFarming.lua`'s
`pfInstance`). Still gate it behind `VDT.PrecisionFarming.isActive()` and treat every field as
fail-soft, the way the other integrations do.

Generic fallback for tools with neither: `spec_workArea` itself — `getIsWorkAreaActive(workArea)`
(`WorkArea.lua:309`) and `getIsWorkAreaProcessing(workArea)` (`:337`, true within 200 ms of the last
processed area) say whether a given part of the tool is working right now.

Render as a section bar (N boxes across the boom, lit = active, tinted by applied rate under PF) in
the rig/implement panel, and as the active footprint drawn behind the vehicle marker on the map.

**In-game check needed:** which machines actually populate `subSectionData` (PF only calls
`updateWorkAreaSubSectionData` for sprayer / sowing / cultivator work areas, and only while turned
on and moving), and what the sub-section count looks like on a wide boom.

## §5 — Coverage layer (issue bullet 3) — *planned, not scheduled; decided to be server-side*

The tedder case is real: nothing in the ground layers records that a windrow was spread. Two
candidate homes were weighed; **the server wins** (user decision, 2026-07-31):

The server already receives position, heading, working width and turned-on/lowered state at 10 Hz.
It can accumulate a coverage bitmask (~2 m cells over `terrainSize`, ~1 MB for a 2 km map) and serve
it as a PNG exactly like `/api/map-layer` — reusing `MapLayerRenderer` / `MapLayerRoute` and the
app's existing layer fetch path. **Zero added in-game frame cost**, which matters: `Json.lua` is
already the profiler hot spot for `mapLayers` during active work (see `map-layers-plan.md`), and a
mod-side coverage plane would land straight on top of it.

Open questions for when this is picked up: persistence (per save? per session, in memory?), the
reset control (a server route, since there is nothing for the mod to do), and whether coverage is
recorded on foot (no) or only with a lowered, turned-on tool (yes).

Note §1 already delivers a coarse version of this for free — worked lines are shaded from
`segmentStates` wherever a steering course exists.

## §6 — 3D (issue bullet 3's question mark) — deferred, with a reason

A tilted plane is reachable in Compose (`graphicsLayer { rotationX; cameraDistance }`), but every
overlay — field labels, POI dots, vehicle arrows, the course itself — would have to go through the
same perspective projection or it visibly detaches from the map, and text rasterizes badly inside a
tilted layer. §2's `MapProjection` is the thing that would make it tractable later.

My read: course-up (§3) delivers most of what the reference photos communicate. Revisit 3D after
§3 is on screen and judge it then, rather than committing now.

## Risks and in-game checks

- **Course size.** Headland rings come from the boundary at terrain-detail resolution. Decimate to
  ~2–3 m spacing and cap like `MapExporter` does; measure the written file on a large field before
  calling §1 done.
- **Non-square maps.** `MapExporter.resolveWorldSize` returns separate `sizeX`/`sizeZ`, but a swath
  width in meters normalizes by one axis. Use `sizeX` and note it; it only skews on non-square maps.
- **Regeneration race.** The app can hold geometry for a course the live state has already replaced
  — hence `courseId` on both sides; ignore indices when they disagree rather than highlighting the
  wrong line.
- **MP.** Verify on a dedicated server that segments, `segmentStates` and `currentSegmentIndex`
  all arrive on a client, and that our locally computed `distanceToEndM` matches the host's
  behaviour (the game's own value is server-only).
- **Empty-course transitions.** Leaving a field resets the course after 20 s
  (`AIAutomaticSteering.lua:343-352`); the channel must publish that as "no course", and the app
  must clear rather than keep painting the last one.

### In-game checks §1 still needs (2026-07-31)

Everything below is unit-tested against stubs, which says nothing about whether the real spec tables
look like the stubs:

1. **The deviation sign.** `lineState` calls "+ right" by deriving left from the game's own side-offset
   direction. That is a reading of `SteeringFieldCourse.lua`, not a measurement. Drive a line with the
   assist engaged, drift knowingly to one side, and check the number's sign — a lightbar that pushes
   you the wrong way is worse than none.
2. **Course size on a real field.** Measure the written `gpsCourse.json` on a big field with a narrow
   tool; the thinning constants (3 m, 128 points per segment) are guesses against boundary-resolution
   headland rings.
3. **How often the course actually changes.** The channel assumes "about once per field". If the game
   regenerates it more eagerly than that (attaching an implement, a settings tweak, re-entering), the
   write frequency wants a second look.
4. **Multiplayer.** Segments, worked flags and `currentSegmentIndex` on a dedicated-server client, and
   whether our own distance-to-end matches what the host feels.
5. **Fixture refresh.** `examples/json/*.json` are captures from before the VERSION 7 bump, so none
   carries `gps.course`; the live half is currently covered by inline JSON in `GpsCourseModelTest`.
   Retake one capture with a course active and swap the test over.

## Deferred

- **Tap a line to engage it.** The server overrides a client's segment choice
  (`AIAutomaticSteering.lua:473-479`), so it would work in singleplayer and desync in MP. A plain
  `setGpsSteering{on}` command (`setAIAutomaticSteeringEnabled`) is safe everywhere and could ride
  along with §2 if wanted — it fits the existing `CommandRegistry` pattern next to `GpsControl.lua`.
- **Exporting the course for vehicles other than the driven one.** The spec data is per vehicle and
  only the entered one computes its current segment client-side; a fleet view would need the server
  path and is not worth it.
- **Course settings write-back** (implement width, headland count) from the app — the game has a
  settings UI for it, and `onAIModeSettingsChanged` forces a full regeneration each time.
