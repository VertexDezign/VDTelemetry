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
  (§4 was picked up next, on 2026-08-01, once §1–§3 were driven and closed.)
- §4 (section view) and §5 (coverage) are planned here but not yet scheduled. The coverage raster
  is decided to live **server-side**, not in the mod — see §5.
- §6 (3D) stays a maybe, and the plan says why 2D course-up gets most of the way there.

**Status (2026-07-31, branch `43-gps-course`):**

- §2 — **done.** The `MapProjection` refactor and the navigation strip landed first (commit
  `c106372`, confirmed in the app); the lightbar and progress readout followed once §1 supplied their
  data. See §2's "How it landed".
- §1 — **done, validated in game (singleplayer and multiplayer).** Both halves (the `gpsCourse.json`
  geometry channel and the `vehicle.gps.course` live state) plus the course drawn on the map. Mod
  export version is now **7**. See "How it landed" below and the in-game checks at the end.
- §3 (course-up) — **done.** A header toggle beside auto-center (per placed tile); north-up stays the
  default, so no saved page changes under anyone.
- §4 (section view) — **built 2026-08-01, not yet driven** (commits `f700b1e` + `6d441d8`). Mod
  export version is now **8**. See §4's "How it landed" and the checks under it. The boom also went
  onto the bottom of the map on 2026-08-02 (app-only, no export change) — see "The section strip on
  the map".

**In-game results (2026-07-31/2026-08-01, user):** the course draws and clears correctly, the worked
shading follows the game, the deviation sign reads the right way round, and the channel's cadence is
sane. Course-up threw up one bug — map labels tilted with the heading (see §3's "How it landed") —
since fixed. **Multiplayer behaves the same as singleplayer**, which was the one thing the design
genuinely bet on (see the `lastDistanceToEnd` note in "How it landed"). §1–§3 are closed.

Every game-source claim is cited `file:line` against the bundled extracted source. **Correction
(2026-08-01):** an earlier version of this line said Precision Farming had to be cited by LUADOC
because its Lua was not in the bundle. It is — `internalMods/FS25_precisionFarming/scripts/` — so the
§4 citations are `file:line` like the rest, and they are what turned up the server-only sub-sections
that a LUADOC page would never have shown.

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
  client — collect defensively, never assume `segments` is populated. **Confirmed in game 2026-08-01:
  a dedicated-server client sees the same course, worked flags and current line as the host.**
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

The lightbar and readout followed §1, app-only. Three calls worth recording:

- **The bar is a bubble level, not a steer-this-way arrow.** Terminals differ on this and let you flip
  it; showing where the vehicle *is* relative to the line is the reading that cannot be misinterpreted,
  because it matches the map directly above it. Positive error moves the marker right.
- **The line-end countdown turns amber at 10 m**, which is the game's own
  `AIAutomaticSteering.LINE_END_SOUND_DISTANCE` — the display warns when the tractor starts beeping,
  rather than at a threshold of our invention.
- **Both surfaces get it.** The standalone widget grows the bar under its lamps and the strip grows a
  second line, so a page without a map is not the poor relation. `lightbarCell` and `deviationLabel`
  are pure and unit-tested (`LightbarTest`) — the app end of the sign agreement with the mod.

## §3 — Course-up

Rotate the map to the vehicle's heading with the marker sitting ~⅓ up from the bottom — the
standard guidance view, and the single change that makes the panel *look* like the reference
displays. With §2's projection in place this is a rotation folded into `MapProjection`, plus
counter-rotation for the constant-size labels and markers that are deliberately drawn outside the
zoom-scaled layer. Per-instance config (`north-up` / `course-up`), default north-up so existing
dashboards do not change under people.

Heading is already smoothed for the player marker (`animHeading`, `MapPanel.kt:227-241`) — reuse
that value, or the whole map judders at 10 Hz.

### How it landed (2026-07-31)

Rotation became two fields on `MapProjection` — `rotationDeg` and the `pivot` it turns about — and the
pipeline is now **scale → translate → rotate**. Everything that projects a point got course-up for
free; what needed thought was the handful of places that are not points:

- **The raster needs two nested layers.** `graphicsLayer` turns everything about a single
  `transformOrigin`, but the rotation pivots on the vehicle while the zoom pivots on the box corner.
  An outer layer that only rotates, wrapping an inner one that only scales and pans, composes into
  exactly `toScreen`. The gestures stay on the outer box *outside* its own rotation layer, so they
  keep receiving plain box coordinates.
- **Input has to be turned back.** The pinch centroid and the tap position are places, so they go
  through `unrotate` (about the pivot); a drag delta is a direction, so it goes through
  `unrotateVector` (no pivot) — otherwise the world slides off at an angle to the finger.
- **Text needs nothing, arrows need the rotation.** This one shipped wrong and was caught in game: the
  overlay canvas is never rotated — only the *positions* pass through the rotation, inside `toScreen` —
  so a label drawn at a projected point is upright already. The counter-rotation added "to keep labels
  upright" tilted every field and POI name by the heading instead. Removed, with the reasoning left in
  the code so it does not come back. Vehicle markers do need `heading + rotationDeg`, because a heading
  is relative to north and north is no longer up, and the player marker's two rotations cancel to zero
  — which is the point: the map turned instead.
- **The vehicle sits at 0.66 down the side**, and that anchor doubles as the rotation pivot: the one
  screen point rotation leaves alone, which is what keeps the machine still while the world turns.
  `centeredOn` is now a special case of `anchoredAt`.
- **It is a header toggle, not widget config** (user, on seeing it): orientation is a mode you flip
  while driving, like following the vehicle, not a decision you make when placing a tile. It sits next
  to the auto-center button, persists per placed tile, and turning it on resumes following — course-up
  means "point where I am going", which says nothing about a map parked over another corner of the map.

Known cosmetic limit: at zoom < 1 a turned square leaves the viewport corners empty, since the map
image no longer covers them. Real terminals crop into the map rather than out of it, and course-up is
a zoomed-in mode, so this is left alone rather than papered over with a √2 scale-up that would make
the zoom levels mean different things in the two orientations.

## §4 — Section view (issue bullet 2) — *built, not yet validated in game*

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
  (`ExtendedSprayer.lua:602-636` builds them, `:663-785` refreshes them per travelled distance).
  That is variable-rate data rather than on/off shutoff, but it is exactly the strip a real terminal
  draws across the boom, and `lastDetectionX/Z` even gives each section a world position to paint on
  the map.

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

### How it landed (2026-08-01, commits `f700b1e` + `6d441d8`)

Built as planned, from all three sources, with one finding that reshaped the PF half.

**Mod, export version 8.** Three additions, each an aspect that is simply absent on a machine that
does not have it:

- `workWidth.sections` — `{ active, side }` per section plus `activeCount`, in **`spec.sections`
  order**. That ordering is the whole subtlety: it is the XML's declaration order, which is what the
  game's own HUD draws left to right (`VariableWorkWidthHUDExtension:77-96`). `sectionsLeft` /
  `sectionsRight` look like the obvious lists to use and are **sorted by width** for the fold-in
  state machine (`VariableWorkWidth.lua:176-180`), so a bar built from them would be shuffled.
- `workAreas` — per area the engine's own two predicates, which say different things and are both
  worth having: `getIsWorkAreaActive` is capability (ground contact, direction, lowered, *and* the
  section it belongs to — `VariableWorkWidth.lua:378-386` — so shutoff is already folded in), and
  `getIsWorkAreaProcessing` is evidence (it worked ground within 200 ms). Plus `width`, measured
  from the start→width corners rather than read off `workArea.workWidth`, which is only recomputed
  when a section moves and starts life at `-1`. Plus `shape`: three corners of the footprint
  parallelogram in the normalized map frame, so the app draws it with the same projection as
  everything else. `AUXILIARY` areas are dropped — the engine skips their whole processing setup
  (`WorkArea.lua:246`), so they never touch ground.
- `precisionFarming` — mode (`LIME` / `FERTILIZER` / `OTHER`), auto flag, the boom-average
  nitrogen/pH readings converted out of PF's internal levels through the maps' own converters, and
  the per-slice `subSections` joined to `workAreas` by index.

**The finding: PF's sub-sections are server-only.** `updateWorkAreaSubSectionData` is called from
`onUpdate` inside `if self.isServer` (`ExtendedSprayer.lua:212-255`), so on a dedicated-server client
`subSectionData` is never filled in. The averages are fine — `nActualValue` / `nTargetValue` /
`phActualValue` / `phTargetValue` are streamed (`:180-206`) and are what PF's own HUD draws.

That asymmetry is in the model on purpose rather than hidden: the app reads the **average** as the
value and treats the strip as detail on top, so a multiplayer client shows a real number instead of
an empty bar. The alternative — deriving a uniform strip from the average — was rejected as a lie: a
flat strip is a claim about variation we do not have.

Two smaller calls: a slice PF marks `isValid = false` renders **grey, not green**, because green on
that ramp means "nothing needed here" and that is a claim about ground the soil sample has not
uncovered. And the map footprint is drawn only for areas that are `active`; painting a raised
implement's resting footprint would read as coverage.

### The nozzle bar (2026-08-01, from a play session)

Driving it turned up two things, one a game fact and one a defect.

**PF takes the base game's width controls away.** On a sprayer PF ships nozzle data for,
`ExtendedSprayerEffects` removes `VariableWorkWidth`'s `onRegisterActionEvents` *and* `onDraw`
(`ExtendedSprayerEffects.lua:101-105`) — so the player cannot change the working width at all and the
game's own section HUD is gone. The sections still exist and still gate the work areas and the
effects; they are simply frozen all-on for a human driver (an AI worker can still set them). Which
means **the shutoff bar is dead weight on exactly the machines a section view is most wanted on.**

**PF's own answer is per nozzle, and it is better than everything above.**
`spec.sprayerEffects[i].isActive` (`ExtendedSprayerEffects.lua:283-289`) is recomputed every update
and already folds in: the section being off (`:361`), reversing or crawling
(`WeedSpotSpray.lua:118-120`), spot spraying finding no weed under that nozzle (`:123-131`), and
liquid fertilizer skipping ground that already has some (`:142-160`). Two properties make it the
primary signal rather than a nice-to-have:

- **It survives multiplayer.** `onUpdate` has no `isServer` gate (`:187-203`), because these states
  drive what the player sees leaving the boom.
- **It says something with herbicide in the tank**, where PF computes no rates at all and every
  sub-section reads invalid.

Ordering is rebuilt from `effectData.xOffset` (measured once at load, `:249`) rather than taken from
the spec's list, which comes out of a `pairs()` walk of PF's node XML. Positive x is the **left**
side — that is how PF itself reads it, looking a positive offset up in `sectionsLeft` (`:264-271`) —
so descending x runs left to right, matching the shutoff bar.

So the app shows **one** bar: the nozzles when they exist, the shutoff sections otherwise. Never both,
because that would be an honest bar next to a stuck one.

**The defect:** with herbicide the rate strip drew twelve grey cells — PF fills sub-sections in only
while liming or fertilizing (`ExtendedSprayer.lua:682`), so every slice was "no data". Correctly
mirrored and useless to look at; a row of grey reads as a broken bar rather than an absent one. A
strip with nothing valid in it is now not drawn.

### The saving readout, and a stale-value bug it exposed (2026-08-01)

Asking whether the N figure should show with herbicide in the tank turned out to be the right
question, because the honest answer is **no, and it must not**: `nitrogenLevel` is read under
`if spec.isFertilizing` and `phLevel` under `if spec.isLiming` (`ExtendedSprayer.lua:714-719`), the
aggregates they feed are only assigned inside `processWorkAreaSubSectionData` (`:931`, reached from
the spray path only in those modes, `:1246`), and **nothing ever resets them**. A sprayer that
fertilized this morning and is on herbicide now still holds this morning's nitrogen, possibly from
another field.

So the mod now emits each reading **only in the mode that maintains it** — the same branch PF's own
HUD picks. That was a live bug, not a display preference: the number looked current because the
nozzle bar beside it was.

In its place, for herbicide, the readout that *is* live: **what spot spraying saved.**
`WeedSpotSpray:getSprayerUsage` multiplies the sprayer's usage by exactly the active-nozzle fraction
(`:102-105`), so `1 - fraction` is the liquid not put down versus the same ground at full width —
the game's own arithmetic, from two numbers already on the wire.

Two gates keep it honest, and both are the point of the feature rather than polish:

- **Only with the spot-spray configuration fitted** (`WeedSpotSpray.lua:28`, exported as `spotSpray`).
  Without it, closed nozzles are folded-away boom: less liquid over less ground, which is not a saving.
- **Only while the boom is down and moving.** Raised or stopped, every nozzle is shut, which is a
  perfectly true "100% saved" and a completely useless one. Moving, 100% means the interesting thing
  instead: a full-width pass over clean crop, putting nothing down.

**The first version of that second gate was wrong, and flickered.** It asked whether the tool was
*processing* — and `lastProcessingTime` is only bumped when the processing function reports a changed
area (`WorkArea.lua:191-198`). A spot sprayer over clean crop changes nothing, so the flag toggles
**with the weeds**, several times a second, taking the whole row in and out of the layout with it.
The gate is now `getIsWorkAreaActive` — lowered, in contact, moving forward — which is what actually
decides whether the number means anything and is steady while you drive. (For a spot sprayer that
also implies moving forward: `WeedSpotSpray` sets `disableBackwards = true`, `:169-171`.)

The same twitch is why the two PF rows now **claim their height up front** from what the machine can
do, rather than from having something to say this instant:

- The rate row is held whenever the mode is one PF maintains rates for. The values themselves go
  absent off the field and on unsampled ground, so a row that followed the value would blink at every
  headland.
- The strip row is held whenever PF computes slices at all — never with herbicide, where it would be
  permanently blank, and always while liming or fertilizing, including the moments no slice is valid.

The status lamp above them still follows `processing` and so still blinks green↔amber while spot
spraying clean crop. That one is arguably honest — ground really is only changing where the weeds are
— and it is a 6 dp dot rather than a row, so it moves nothing. Worth revisiting if it reads as noise
in the seat.

The rate row reserves its space with a **blank line of text**, not a height in dp. The first attempt
used `height(14.dp)`, which is a guess at how tall a line is and was wrong at the ordinary font
scale: it clipped the glyphs the moment there was a rate to draw. A placeholder at the same text
metric the content uses cannot be wrong at any scale.

### Pulse-width modulation (2026-08-01, from the seat)

A PWM sprayer visibly stops spraying out of parts of the boom, and the nozzle bar showed a solid boom
throughout — because in PF's model **nothing had switched off**. With
`configurations.pulseWidthModulation` fitted, `updateExtendedSprayerNozzleEffectState` returns
`isTurnedOn` for `isActive` and puts each nozzle's *own* ground speed over the machine's limit into
`amountScale` (`ExtendedSprayerEffects.lua:361-377`), which becomes the pause between shader pulses
(`:402-404`). So a slow nozzle pulses slowly; it does not shut. Reading only `isActive` was therefore
correct and useless.

The mod now exports `amount` per nozzle (omitted when every one is wide open, which is every non-PWM
machine), and the bar shades a lit cell by it, floored well above transparent so a pulsing nozzle can
never be mistaken for the shut one beside it. Through a turn the inside of the boom fades and the
outside stays solid, which is what the driver is actually looking at.

Two things fell out of reading that code. `individualNozzleControl` **is** `pwmEnabled` (`:40-45`) —
individual nozzle control and pulsing are the same machines, and without it PF switches a whole
section at a time. And with PWM the "too slow to spray" cut-off does not apply: that lives in the
non-PWM branch, because pulsing *is* how a PWM boom handles low speed.

Reading PF needed no mod-environment dance: `spec_FS25_precisionFarming.extendedSprayer` is a plain
string key on the vehicle (`ExtendedSprayer.lua:3`), the same reason `subSectionData` is reachable at
all. The value maps come off that spec too, so `pfInstance()` stays confined to the layers code.

### The section strip on the map (2026-08-02)

The second reference photo puts the boom along the bottom of the guidance screen, and that turned out
to be the one part of it the map was still missing. App-only — the data has been on the wire since
export version 8, so nothing in the mod moved.

**Why it is not redundant with the footprint already drawn there.** For base-game sections it nearly
is: a work area carries a `sectionIndex` and `getIsWorkAreaActive` returns false when that section is
shut (`VariableWorkWidth.lua:378-386`), and `workFootprints` takes only active areas — so a shutoff
section already appears as a gap in the quad behind the machine. PF is the opposite: it freezes those
sections all-on and switches nozzles instead, so the work areas stay active and the footprint stays a
solid full-width quad while spot spraying blinks half the boom. **The nozzle bar is the only place
that pattern shows**, and it is the machine a section view is most wanted on.

Three calls worth recording:

- **The bar and its two numbers, nothing else.** The rate readout and the rate strip stay on the rig
  panel. This is the screen you steer by, and the map already has the machine, the course and the
  lightbar competing for the same glance; the rate strip is also server-only, so it would be absent in
  multiplayer on exactly the screen most likely to be used there.
- **The lamp comes along, because the bar alone cannot say whether the tool is down.** A shutoff
  section still reads "on" under a raised implement, and every nozzle reads "off" on a lowered boom
  that has simply found no weeds. It is the same 6 dp dot as the rig panel, so the vocabulary matches.
- **One boom, picked by a pure rig walk** (`boomOf`): the machine itself first — a self-propelled
  sprayer is its own boom — then implements depth-first, first bar wins. Unit-tested, so which tool
  gets picked is pinned rather than being whatever the rig happened to look like the day it was tried.

It is an overlay rather than a row under the map, so hitching a tool mid-drive cannot reshuffle the
map under the driver, and it reports its measured height so the ground-layer legend in the same corner
stacks above it instead of under it. Off by default, per placed tile, beside the navigation strip in
widget config — deliberately a separate switch, since a sprayer wants the boom and a map watched from
the yard wants neither. In course-up it lands directly under the machine, which sits two thirds down
the screen: exactly where the reference photo has it.

### In-game checks §4 needs

Everything above is unit-tested against stubs (`spec/WorkAreas_spec.lua`,
`spec/PrecisionFarming_spec.lua`, `SectionViewModelTest`, `SectionViewTest`), which says nothing
about whether the real spec tables look like the stubs.

1. **Section order and the centre.** On a machine with an odd number of sections (a sprayer with a
   centre nozzle group), check the bar reads left-to-right the way the boom does and the bracketed
   cell really is the middle one. This is the one where being wrong looks plausible. Note this now
   only shows on machines PF drives no nozzles on — a non-PF sprayer, a spreader, a folding
   cultivator.
2. **The nozzle bar.** Left-to-right order is derived from `xOffset` and the sign convention is read
   out of PF's own code, so it is worth one look that a section switching off darkens the correct
   *end* of the bar. Spot spraying is the fun case: the pattern should be scattered and should move.
3. **Which machines populate `subSectionData`.** PF only refreshes sprayer / sowing-machine /
   cultivator work areas, only while moving, and only on the server — so the expected result is a
   strip in singleplayer and no strip on a client, with the readout present in both. Worth checking
   the sub-section count on a wide boom too (~2 m each, so a 36 m sprayer is ~18 cells).
4. **The footprint.** Does the quad sit under the machine and stay there in course-up, and does the
   fill actually follow the tool switching on and off? A tedder is the interesting case: no sections
   at all, so the status line and the footprint are the entire section view.
5. **A trailed tool with several work areas.** A cultivator-plus-seeder reports more than one; check
   the status line names the tool sensibly rather than whichever area happens to be busy.
6. **The map strip.** Switch it on for a map tile and drive a sprayer: the bar should sit along the
   bottom edge without covering the machine in course-up, vanish when the tool is unhitched, and hand
   the corner back to the ground-layer legend when it does. The rig walk is the part to watch on a
   train of implements — the bar must describe the tool with the boom, not the first thing hitched.

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
- **MP.** ~~Verify on a dedicated server that segments, `segmentStates` and `currentSegmentIndex`
  all arrive on a client, and that our locally computed `distanceToEndM` matches the host's
  behaviour (the game's own value is server-only).~~ Verified 2026-08-01: it does.
- **Empty-course transitions.** Leaving a field resets the course after 20 s
  (`AIAutomaticSteering.lua:343-352`); the channel must publish that as "no course", and the app
  must clear rather than keep painting the last one.

### In-game checks §1 still needs (2026-07-31)

Everything below is unit-tested against stubs, which says nothing about whether the real spec tables
look like the stubs:

1. ~~**The deviation sign.**~~ Checked in the seat 2026-07-31: reads the right way round.
2. ~~**Course size on a real field.**~~ Measured 2026-07-31: **26 KB** for a big field on Riverbend
   Springs at a **0.5 m** working width — the narrowest tool the game has, so the worst realistic case
   on a 1x map. The thinning constants hold.

   Scaling to bigger maps is gentler than it looks, because size follows the number of *lines*, not
   area: a line is two endpoints however long it is, so a 4x map (2x the linear edge) is ~2x the lines
   and a 16x map ~4x — call it 100 KB at the ceiling, written once per field. The headland rings, the
   part that could genuinely run away, are already bounded by the 3 m spacing and the 128-point cap.

   The part that scales *per tick* rather than per field is the worked bitmask on the telemetry:
   ~1000 segments is ~250 hex characters and a 1000-iteration loop at 10 Hz, ~4x that on a 16x map.
   Small next to the vehicle walk it rides with, but it is the lever to pull if the profiler ever
   points here — memoise the mask on `workedCount` and rebuild only when a line completes.
3. ~~**How often the course actually changes.**~~ Cadence looked right in the Diagnostics app.
4. ~~**Multiplayer.**~~ Checked 2026-08-01: works as expected on a client. Segments, worked flags and
   `currentSegmentIndex` all arrive, and the locally computed `distanceToEndM` matches how the host
   behaves — which is the bet the design made when it stopped reading the server-only
   `lastDistanceToEnd`, so this was the check with something riding on it.
5. **Fixture refresh — half done.** The geometry channel now has a real capture,
   `examples/json/gpsCourse/gpsCourse.json` (a worked field: 14 lines, a headland ring in 15 pieces,
   11 m width), and `GpsCourseModelTest` asserts against it — it is what pins the mod's actual writer
   rather than a hand-authored shape, and it caught nothing, which is the good outcome. Still open:
   the top-level `examples/json/*.json` captures predate the VERSION 7 bump, so none carries
   `gps.course` and the live half stays inline in the test. Retake one when a course is up.

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
