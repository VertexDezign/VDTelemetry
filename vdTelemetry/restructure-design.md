# Design: Restructuring the mod (`vdTelemetry/`)

Branch: `restructure`. This is the mod-side companion to `VDTerminal/kotlin-migration-plan.md`.

## Motivation

Today the mod is one ~750-line `VDTelemetry.lua` that mixes four concerns in a single pass:

- **lifecycle** (init, `loadMap`, settings, the `update()` timer),
- **extraction** of state from the game (`vehicle.spec_*`, `g_currentMission.environment`, …),
- **serialization** — every collector writes XML inline via `xml:setString/Int/Bool/Float(path, …)`,
- **aggregation** — `combinedInfo` is mutated *during* the walk (`:613`, `:673`, `:462`).

Problems this causes:

- **Format is welded into ~20 collectors.** Switching XML→JSON (roadmap item 5) would mean
  editing every one of them.
- **Third-party mod support is inlined** with `if x ~= nil` guards — Enhanced Vehicle inside
  the motor collector (`VDTelemetry.lua:326-341`), for example — so vanilla and mod logic are
  tangled.
- **Not testable** without a running game: a function that calls `xml:setX` can't be exercised
  off a fake object.
- **`combinedInfo` is stateful mutation** interleaved with extraction, the hardest part to
  follow.

Note the code already *hints* at the fix: `writeFillUnit` (`:561`) takes a plain
`{type, title, unit, capacity, fillLevel}` table, and `combinedInfo` is an intermediate model.
The refactor makes that pattern the rule.

## Core principle

**Collectors return data (a plain-table model). They never touch the serializer.**

The format lives in exactly one place. This is the linchpin: it makes the JSON migration one
new writer instead of a rewrite, makes collectors unit-testable off fake objects, and lets
third-party support *contribute to a model* instead of threading `if mod ~= nil` through vanilla code.

## Pipeline: three stages, not one walk

```
extract    game state ──▶ model     (core collectors; then optional-mod integrations contribute)
derive     model      ──▶ model     (combinedInfo — pure aggregation over the model tree)
serialize  model      ──▶ XML/JSON  (format lives here, and ONLY here)
```

The `derive` stage is the big cleanup: `combinedInfo` stops being mutation-during-walk. We build
the pure implement tree first, then compute front/back state, summed fill units, and averaged
wearable by walking the *model*. No cross-cutting state during extraction.

## File layout

```
src/
  model/            -- ---@class definitions only (VehicleModel, ImplementModel, MotorModel, …). No logic.
  collect/
    VehicleExporter.lua      -- orchestrates a vehicle + its recursive implement tree
    EnvironmentExporter.lua
    aspects/                 -- apply to ANY object (vehicle OR implement)
      FillUnit.lua  Wearable.lua  Foldable.lua  Lowered.lua  TurnOn.lua  Pipe.lua  Cover.lua
    vehicle/                 -- vehicle-only
      Motor.lua  Lights.lua  SupportSystems.lua      -- gps / ai / cruise control
  integrations/              -- OPTIONAL third-party mods only, one file each (+ registry.lua)
    EnhancedVehicle.lua
  derive/
    CombinedInfo.lua         -- model -> model aggregation
  serialize/
    XmlWriter.lua            -- model -> XMLFile (today).  JsonWriter.lua later.
  utils/  mapper/ValueMapper.lua      -- unchanged
```

The `aspects/` vs `vehicle/` split reflects the current code: shared collectors are already reused
for both vehicle and implements via the `path` param (`:264-271` mirrors `:485-492`), while
motor/lights/support are vehicle-only.

## Dependencies vs. integrations — an important distinction

- **FS25_additionalInputs is a hard requirement, NOT an integration.** It is declared in
  `modDesc.xml` and version-checked in `loadMap`; if it is absent or the wrong version the mod
  sets `exportEnabled = false` and produces nothing. Therefore, during collection its API is
  **guaranteed present**, and its data (attacher-joint position via `vdAIGetAttacherJointPosition`
  at `:456`, heading, …) is **core** — the collectors call it directly, no presence check, no
  registry. It lives in `collect/`, not `integrations/`.
- **`integrations/` is exclusively for optional third-party mods** — mods that may or may not be
  installed and whose data is additive. Enhanced Vehicle (diff lock / AWD / parking brake,
  `:326-341`) is the current example.

## Interfaces

### 1. Core collector — pure, returns its fragment (or `nil` if the spec is absent)

```lua
---@param object table            -- a vehicle or an implement
---@return FillUnitModel[]|nil
function FillUnit.collect(object)
  if object.spec_fillUnit == nil then return nil end
  ...
  return units
end
```

The orchestrator assembles the model from fragments:

```lua
local model = {
  name  = vehicle:getFullName(),
  speed = { value = ValueMapper.mapFloat(vehicle:getLastSpeed()), attr = { unit = "km/h", direction = ... } },
  motor = Motor.collect(vehicle),
  lights = Lights.collect(vehicle),
  fillUnits = FillUnit.collect(vehicle),
  implements = collectImplements(vehicle),   -- recursive; each entry an ImplementModel
}
```

Implements reuse the same `aspects/` collectors and recurse (an implement can carry implements),
exactly as `populateXMLFromAttacherJoints` recurses today (`:492`).

### 2. Optional-mod integration — *contributes* to the assembled model; self-contained detection

```lua
-- integrations/EnhancedVehicle.lua

-- Fields this integration adds live here, next to the code that sets them (see below).
-- LuaLS merges @field lines declared against the same class name across files, so this
-- extends MotorModel without model/ having to know Enhanced Vehicle exists.
---@class MotorModel
---@field diffLock DiffLockModel?
---@field awd boolean?
---@field parkingBrake boolean?

function EnhancedVehicle.contribute(object, model)
  local vData = object.vData
  if vData == nil then return end          -- the "am I present?" check lives here, once
  model.motor.diffLock = { front = vData.is[1], back = vData.is[2] }
  model.motor.awd = vData.is[3] == 1
  model.motor.parkingBrake = vData.is[13]
end
```

```lua
-- integrations/registry.lua
Integrations = { EnhancedVehicle }
-- run per object during the walk, after core collectors have produced its model:
for _, i in ipairs(Integrations) do i.contribute(object, objectModel) end
```

Asymmetry is deliberate: **core collectors define the model shape; integrations decorate it.**

### Where contributed fields are declared

A field added by an integration exists in three places, at three different levels:

- **Runtime:** it's a real key in the merged model table — `contribute` mutates the same table
  the writer serializes, so `model.motor.diffLock` is simply present (or `nil`/absent when the
  mod isn't installed, in which case the convention-driven writer emits nothing, matching today's
  `if vData ~= nil` guard).
- **Type definition (`---@class`):** declared **in the integration file**, not in `model/`, via
  LuaLS cross-file class-field merging (the `---@class MotorModel` block above). The annotation
  lives next to the code that sets it, and `model/` stays free of optional-mod knowledge. Fields
  are optional (`?`) because they're absent when the mod isn't installed.
- **Schema:** they *are* part of the output contract — the XSD and Kotlin model carry them as
  **optional** elements. "Contributed" describes the *source* of the value (an optional mod),
  not whether it belongs to the schema. The XSD, not the Lua `@class`, is the central contract;
  splitting the Lua annotations across files therefore doesn't split the real contract.

### 3. Writer — the only format-aware code

> **Decision (2026-07-05): JSON-only, so there is no writer to speak of.** The convention below
> existed solely to let one model feed *both* XML (which distinguishes `#attr` from child elements)
> and JSON (which does not). With the JSON-first decision, XML on disk is being retired, so the
> model is a **plain nested table that maps 1:1 to the contract** and "serialize" is just
> `Json.encode(model)` (`src/utils/Json.lua`). No `attr` wrapper, no `XmlWriter`/`JsonWriter`
> classes, no convention to learn. The subsections below are kept only as the rationale for why the
> convention is *not* needed. If XML ever has to come back, this is where the convention would live.

The (now unused) convention was: the model needs a marker the writer can interpret, because XML
distinguishes `#attr` from child elements and JSON does not. A small node shape carried that:

```lua
speed = { value = "12.3", attr = { unit = "km/h", direction = "STOPPED" } }
```

- `XmlWriter` maps `attr.*` → `#`, `value` → element text, any other key → a child element
  (lists → indexed `name(i)` paths, matching today's `implement(%d)` / `fillUnit(%d)`).
- A later `JsonWriter` flattens `attr` into fields.

Because JSON is the only target, we skip all of this: `speed = { value = 12.3, unit = "km/h",
direction = "STOPPED" }` goes straight to `Json.encode`.

## Combined info (`derive/CombinedInfo.lua`)

A pure function `model -> model` that replaces the in-walk accumulation:

- **fill units:** group implement + vehicle fill units by type, sum capacity and fill level
  (today `:613-625`).
- **wearable:** average damage/wear/dirt across all objects that have the spec (`:694-708`).
- **front/back implement state:** find the first implement whose `position` is `FRONT` / `BACK`
  and copy its turnedOn/foldable/lowered (today `:462-469`). `position` comes from
  additionalInputs (core) and is already a field on each ImplementModel by this stage.

## Guardrails (behaviour-preserving, but now to a JSON target)

- **Grow JSON in parallel with XML; don't break the working output.** The old `populateXMLFrom*`
  methods keep writing `vdTelemetry.xml` untouched while the new `collect → model → Json.encode`
  pipeline grows `vdTelemetry.json` one slice at a time. In-game telemetry (server still reads XML)
  keeps working throughout. When the JSON model is complete, flip the server to JSON decode and
  delete the `populateXMLFrom*` methods.
- **Keep presentation values.** `ValueMapper` stays where it is and the model holds today's
  presentation values, so the JSON carries the same numbers the XML did — only the container
  changes. (Raw-values-vs-presentation stays a *separate* decision; see Out of scope.)
- **The safety net is the fixtures.** No automated test covers the mod's Lua output
  (`shared:jvmTest` tests the *Kotlin* parser). The contract is `examples/json/*` (generated by
  `JsonContractTest` from the model): after each slice, **diff the emitted `vdTelemetry.json`
  subtree against the matching fixture subtree**. Types must match the Kotlin model (`Model.kt`):
  e.g. `version` is a `String` (`"1"`), temperatures are `Int`, `speed.value` is `Float`.
- Lifecycle (`init`, `loadMap`, settings, `update` timer, `addModEventListener`) stays in
  `VDTelemetry.lua`; it just calls `EnvironmentExporter` / `VehicleExporter` and hands the model
  to `Json.encode` instead of doing the walk itself.
- Keep `sourceFiles` load order correct (dependencies first) as new files are added. `model/`
  files are annotation-only (`---@class`) and are **not** source()'d.

## Migration order (vertical slices, each ending byte-identical)

1. **Environment** — smallest, self-contained (no recursion, no integrations). Introduces
   `model/`, `EnvironmentExporter`, and `XmlWriter` for its subtree. Proves the model+writer
   convention end-to-end.
2. **Motor** — first vehicle aspect *and* the first optional integration: extract Enhanced
   Vehicle out of the motor collector into `integrations/EnhancedVehicle.lua`.
3. **Shared aspects** — FillUnit, Wearable, Foldable, Lowered, TurnOn, Pipe, Cover; wire them
   into both vehicle and the recursive implement walk.
4. **Support systems + lights.**
5. **CombinedInfo** as the `derive` stage (delete the in-walk accumulation).
6. Delete the old `populateXMLFrom*` methods once each is replaced.

## Out of scope / separate decisions

- **Raw values + units as structured data** (numbers instead of pre-formatted strings, letting
  the Kotlin app format) is attractive but **changes the wire contract** — decide it separately
  from the file reorg, not as a rider.
- **JSON serialization** (roadmap item 5) — the point of this design is that it becomes just
  `serialize/JsonWriter.lua` + a swap, with no collector changes.
- **Command back-channel** reading (roadmap item 4) — a future `collect`-side inverse
  (`commands.*` -> actions); the model/writer split doesn't preclude it.
