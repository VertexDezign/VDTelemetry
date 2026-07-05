# Roadmap

Post-migration improvements to the telemetry pipeline. These are follow-ups to the
Kotlin rewrite (see `VDTerminal/kotlin-migration-plan.md`), focused on latency, a
command back-channel, and serialization.

**Current data flow:** the mod (`vdTelemetry/`) writes `vdTelemetry.xml` to the FS25 user
dir on a timer → the server (`VDTerminal/server`) watches the directory, debounces, parses
via `shared`, and broadcasts `ServerMessage.Telemetry` over WebSocket → the app renders it.

**Baseline already changed:** the mod write interval was dropped from 500 ms to **100 ms**
(`VDTelemetry.lua` `update()` timer) and validated on a real save (~50–70 fps, no FPS delta,
no rhythmic stutter in the F8 script profiler). The items below build on that.

---

## Status / next session (updated 2026-07-05)

**Decision:** do the **JSON migration (item 5) BEFORE the mod restructure**. JSON has no
element-ordering requirement, so it sidesteps the XSD `xs:sequence` problem that would otherwise
force ordered emission; the restructure then lands on a format that doesn't fight it.

**Decision (2026-07-05): the restructure and the JSON migration are one and the same pass.** Each
vertical slice becomes a `collect → model → Json.encode` pipeline. Because JSON is the only target,
we **drop the `attr`-convention writer** from the design doc: the model is a plain table that maps
1:1 to the contract, and "serialize" is just `Json.encode(model)`. The new pipeline grows the
`vdTelemetry.json` output **alongside** the untouched `populateXMLFrom*` XML writer, so in-game
telemetry keeps working; at the end we flip the server to JSON and delete the XML methods.

**Done so far (on branch `restructure`):**
- Confirmed the `shared` model is already `@Serializable` — JSON reuses the *identical* model and
  drops xmlutil. `shared/.../JsonContractTest.kt` generates `examples/json/*` (the exact shape the
  mod must emit) and proves lenient decode (`ignoreUnknownKeys`, omitted keys → defaults). Green.
- **Lua → JSON spike succeeded in-game.** `vdTelemetry/src/utils/Json.lua` (pure-Lua encoder,
  minified default + `pretty` mode that also sorts keys for diff-stable live-watching). `io.open`
  writing works inside the Proton prefix on Linux. Validated with real, fast-changing motor values.
- Added `VDTS.json.pretty` setting (default `false`, no `SETTINGS_XML_VERSION` bump — additive).
- **Environment slice migrated** and **verified in-game (2026-07-05).** `src/model/EnvironmentModel.lua`
  (annotation-only) + `src/collect/EnvironmentExporter.lua`. `update()` calls
  `VDTelemetry:writeJsonFile()`, writing `vdTelemetry.json`. Still `pcall`-guarded while partial.
- **Motor slice migrated (2026-07-05), incl. the first optional integration.** New
  `src/collect/VehicleExporter.lua` (vehicle header: name/type/speed/brand/operatingTime + motor,
  then runs integrations), `src/collect/vehicle/Motor.lua` (`Motor.collect`), `src/model/{VehicleModel,MotorModel}.lua`,
  and `src/integrations/{EnhancedVehicle,registry}.lua` (Enhanced Vehicle contributes
  diffLock/awd/parkingBrake; those aren't in `Model.kt` so the server drops them via
  `ignoreUnknownKeys`, exactly as today). Numeric fields go through `tonumber(mapper(...))` to keep
  presentation rounding while emitting real JSON numbers. **Needs in-game verification.**
  - **Fixture staleness noted:** the `examples/*` captures predate `maxSpeed`/`brand`/fuel-`usage`,
    which current code *does* emit — so the live JSON will carry a few optional fields the fixtures
    lack. That's expected (behaviour-preserving mirrors the code); regenerate fixtures from fresh
    `vdTelemetry.json` captures once the mod emits JSON.

**Scaffolding to remove/clean before merge:**
- The `pcall` guard around `writeJsonFile` in `update()` — drop once the model is complete and JSON
  is the sole output.
- `JsonContractTest` writes fixtures as a side-effect — split into a generator + a plain decode test.

- **Shared-aspects slice migrated (2026-07-05).** Seven pure aspect collectors under
  `src/collect/aspects/` (`TurnOn`, `Foldable`, `Lowered`, `FillUnit`, `Pipe`, `Cover`, `Wearable`)
  + `Aspects.apply(object, model)` that maps each to its model key. `VehicleExporter` now applies
  aspects to the vehicle and walks the **recursive implement tree** (`collectImplements`, running
  `contributeObject` per implement too). New models: `AspectModel.lua`, `ImplementModel.lua`;
  `VehicleModel` extended with the aspect + implement fields. Empty implement/fill-unit lists return
  `nil` (absent key → Model.kt default `[]`), never a `{}`. `combinedInfo` stays OUT — it's the
  next slice. Implement `brand` is emitted (behaviour-preserving; server drops it). **Needs
  in-game verification.**

- **Support systems + lights slice migrated (2026-07-05).** `src/collect/vehicle/Lights.lua`
  (`gps`/`ai`/`cruiseControl` in `SupportSystems.lua`), models `LightsModel.lua`/`SupportModel.lua`,
  `VehicleModel` extended. **Needs in-game verification.**
- **Namespace fix (2026-07-05): all runtime modules moved under a single `VDT.*` table.** FS25
  exposes vehicle specializations as bare globals (`Lights`, `FillUnit`, `Foldable`, `Cover`,
  `Pipe`, `Wearable`); the earlier slices' bare-global modules clobbered them (latent bug — surfaces
  when a *new* vehicle loads). Now `VDT.Motor`, `VDT.Lights`, `VDT.FillUnit`, … each guarded with
  `VDT = VDT or {}`. This also un-clobbers the still-live XML code, which reads the real engine
  globals. `ValueMapper`/`Set`/`MapUtil`/`Json` stay bare (no collision, used by XML code).
- **`combined` deferred (not skipped forever).** VDTerminal doesn't use it and there's no GameGlass
  integration yet, so there's no consumer to validate the shape — the JSON emitter omits it for now.
  Collectors stayed pure, so `derive/CombinedInfo` drops in cleanly when a consumer needs it. The
  XML writer still emits `combined` until the XML path is deleted.

- **Server switched to JSON (2026-07-05).** `VdtParser.parseJson` (lenient); `XmlSource` →
  `TelemetrySource` reading `vdTelemetry.json`; `Config.telemetryPath()` (env `VDT_FILE`);
  verified end-to-end against a live game.
- **✅ XML fully removed (2026-07-05) — the migration is complete.** Mod: `VDTelemetry.lua` now only
  builds the model + writes `vdTelemetry.json` (all `populateXMLFrom*` / `writeXMLFile` / `combinedInfo`
  scaffolding deleted, `pcall` guard dropped, `XML_VERSION` → `VERSION`). Kotlin: xmlutil dropped
  (dependency + all `@Xml*` annotations on `Model.kt`); `VdtParser` is JSON-only; `XsdValidationTest`
  deleted, `VdtParseTest` → `VdtModelTest` (repointed to `examples/json`), `JsonContractTest` trimmed.
  Deleted `examples/xml/` and `vdTelemetrySchema.xsd`. Docs updated (CLAUDE.md, both READMEs, mod
  Readme). `:shared:jvmTest` + wasm + `:server:compileKotlin` green on the host.

- **App decoupled from `combined` (2026-07-05).** The Implements panel used to drive its Front/Rear
  fold/power/lowered icons from `vehicle.combined`; since the mod no longer emits it, `Implements.kt`
  now reads those straight off each front/back implement (the old `combined.implement.front/back` was
  only ever the *first* front/back implement's own state anyway, so this is behaviour-equivalent and
  keeps name+status consistent). `:app:compileKotlinWasmJs` green; no app code references `combined`.

**Migration done.** `examples/json/*` were refreshed to real captures. Remaining work is the
independent near-term items below (configurable interval, reduce debounce, client interpolation),
plus the deferred `combined` derive (only if a future consumer like GameGlass needs it) and the
command back-channel.

Note: `raw values vs presentation values` stays a *separate* decision — keep presentation values
(via `ValueMapper`) for now so output matches the contract.

---

## Near-term

### 1. Configurable write interval (mod) — ✅ done

- **What:** the hard-coded write interval now lives in `vdTelemetrySettings.xml`
  (`VDTS.export.intervalMs`, default 100) and drives the `update()` timer threshold,
  clamped to `VDTelemetry.MIN_INTERVAL_MS` (16 ms) so sub-frame intervals are impossible.
- **Scope grew (per maintainer):** instead of the apply-on-restart note below, both this and
  the export toggle (`VDTS.export.enabled`) are exposed as **live** controls on the in-game
  **General Settings** page ("Allgemeine Einstellungen"), via `src/gui/SettingsFrame.lua`. The
  callbacks call `VDTelemetry:setExportEnabled` / `setWriteIntervalMs`, which mutate the in-memory
  fields (so `update()` picks them up next tick) and re-persist the XML. Disabling export also
  `deleteFile`s the stale `vdTelemetry.json`. Settings XML bumped to version 2; l10n added.
- **Injection mirrors the maintainer's own shipped pattern** (VertexDezign/LiquidManureTransfer):
  hook `InGameMenuSettingsFrame.onFrameOpen`, clone the `economicDifficulty` MultiTextOption plus
  the `gameSettingsLayout.elements[5]`/`[7]` row/header templates, `applyProfile(...)` the FS25
  settings profiles, wire `FocusManager`, add once per frame with a marker field + `refreshGui()`
  on reopen. The reference mod targets the **Game-Settings** tab (`gameSettingsLayout`); we clone
  its templates but add the controls to the **General-Settings** tab's own layout (resolved by
  name via `GENERAL_LAYOUT_CANDIDATES`, with a one-time layout-field dump if none match). Missing
  templates/layout → a logged warning and no controls, never a broken menu.

### 2. Reduce server debounce — ✅ done

- **What:** `TelemetrySource`'s debounce dropped from **150 ms** to a **40 ms** default, and
  exposed as `VDT_DEBOUNCE_MS` (via `Config.debounceMs()`, matching `VDT_PORT`/`VDT_GAME_DIR`/
  `VDT_FILE`). Passed as a `TelemetrySource(path, debounceMs)` constructor arg (default 40, testable).
- **Why:** the 150 ms `delay()` was a flat latency floor *larger* than the mod's write interval,
  coalescing the 10 Hz stream down to ~6–7 Hz. 40 ms restores the full rate.
- **Why a constant (not derived from the interval):** the debounce only needs to cover the
  event burst + write time of a **single** file write, which is ~constant regardless of how far
  apart writes are. A torn read already self-heals via the `try/catch` + last-good-state in
  `reparse()`. The only real constraint is `debounce < interval`, which 40 ms satisfies for every
  interval preset (min 100 ms).

### 3. Client-side interpolation (app)

- **What:** tween fast-changing values (speed, rpm, player position/heading) between samples
  in the Compose app instead of snapping to each update.
- **Why:** decouples *perceived* smoothness from the write/broadcast rate. Smooth rendering
  over modest-rate samples reads as continuous — this is what lets us keep write rates
  conservative without the dashboard looking choppy. Reduces the pressure to chase high
  sample rates at all.
- **How:** `Animatable`/`animate*AsState` on the values; the map panel already plans an
  `Animatable` offset for the player marker (migration plan Phase 8).

---

## Larger features

### 4. Command back-channel (app → mod)

Let the app send commands to the mod (toggle lights, set cruise speed, …).

- **Transport: a file, not a pipe.** Server writes `commands.json` (or `.xml`) into a `commands/`
  subfolder of `modSettings/<modName>/` (sibling to the existing `telemetry/` folder); the mod
  polls it in `update()`. See "Rejected approaches" for why not pipes. Note the mod can only
  *delete* files under `modSettings/<modName>/`, but the server (native Linux) writes the command
  file, so that constraint doesn't bite here.
- **No duplicate execution:** each command carries a monotonic `id`; the mod tracks
  `lastCommandId` and executes only ids greater than it. Tolerant of the server rewriting
  the whole file; keep a small ring of recent commands so a missed poll doesn't drop
  intermediate messages.
- **Atomicity is the *server's* job:** losing a command is bad (unlike lossy telemetry), so
  the server — native Linux, real `rename(2)` — writes temp + atomic rename. The mod side
  (which lacks `os.rename`) doesn't need to write anything back.
- **Mod-side reads:** guard with `fileExists` before `XMLFile.load`; poll on the same timer
  cadence (command latency ≈ poll interval, fine for button presses). Execution flows
  through the vehicle API / `FS25_additionalInputs` action events.
- **Protocol:** mirrors the migration plan's `ClientMessage` sealed interface (app → server
  over WS → server → `commands.xml` → mod).
- **Constraint:** telemetry (and by symmetry command handling) is **client-side only** —
  `exportEnabled` and `xmlFileLocation` are gated on `g_dedicatedServerInfo == nil`. The
  file lives on the *client's* machine, not the dedicated server box.

### 5. Migrate serialization XML → JSON

- **What:** replace the on-disk XML with JSON on both sides.
- **Why it fits:** the server already speaks JSON on the wire (`ServerMessage` via
  kotlinx.serialization). JSON on disk means **one `@Serializable` model for file-in and
  WS-out, and xmlutil can be dropped** from `shared`. Genuine simplification, not just taste.
- **Caveats:**
  - **No engine JSON writer** in the FS Lua API. Vendor a small pure-Lua encoder or
    hand-build the string. For a small fixed-shape payload, hand-building is trivial and
    faster than `XMLFile`; **escape** untrusted fields (PDA `filename`/paths contain
    backslashes and quotes).
  - **`os.rename` is unavailable in the sandbox**, so the mod can't do atomic temp+rename.
    Not a blocker for telemetry: it's a lossy stream — a torn read fails to parse, the
    server keeps last-good-state, and the next sample lands one interval later.
  - **Loses the XSD as the published contract.** Replace it with the shared Kotlin model +
    `examples/*` fixtures as the contract (the migration plan already leans this way). Update
    the example fixtures and parse tests accordingly.
- **Sequencing:** independent of the latency work. Natural first step is to make any new
  **fast/command channel JSON from day one**, then migrate the main document later.

---

## Deferred / conditional

### Fast/slow channel split

Split telemetry into a small high-rate channel (speed, position, heading, rpm) and a
low-rate channel (implements, fill units, weather, PDA). **Shelved** — a single 100 ms
channel showed no FPS cost on a heavy save, so the extra complexity (two watchers, two
writes, server-side merge) isn't justified yet. Revisit only if a single channel proves too
heavy on the write or reparse side.

### Staleness / connection detection

Detect "no telemetry for N intervals → paused/disconnected" and surface it in the UI. If the
server needs to know the mod's cadence for this, **emit the interval on the telemetry root**
(`<VDT version="1" interval="100">`) — it rides the contract the server already parses, with
no second file and no coupling to the mod's private settings. (This is the *only* good reason
to share the interval with the server — **not** debounce, which stays a constant.)

---

## Decisions & rejected approaches

- **Named pipe for the command channel — rejected.** Although `io.open` works in the FS Lua
  sandbox (a pipe mod writes telemetry with `io.open("\\\\.\\pipe\\…", "w")`), *reading*
  commands is the hard part: stdlib `io` offers only **blocking** `file:read` with no
  select/poll and no `PIPE_NOWAIT`, so a read on the render thread stalls the game. Writing
  is the easy direction; reading is what breaks the frame loop. On Linux there's also the
  **Wine boundary**: a Win32 named pipe lives in the Wine prefix's object namespace, which a
  native Linux server can't join without a Wine-side agent. The file approach sidesteps both,
  because the user dir is a real Linux path shared by both processes.
- **Server deriving debounce from `vdTelemetrySettings.xml` — rejected.** Debounce isn't a
  function of the write interval (see item 2), and it would couple the server to the mod's
  private config schema and force it to locate/watch a second file. Debounce stays a small
  constant (or its own env var).
