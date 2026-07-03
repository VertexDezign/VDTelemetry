# Plan: Migrate VDTerminal to Kotlin Multiplatform

## Goal

Replace `VDTerminal_old` (React/Vite client + Go server) with:

- A **Kotlin/JVM server** (Ktor) that watches `vdTelemetry.xml`, parses it into a typed
  model, broadcasts updates over WebSocket, and serves the map image (DDS → PNG).
- A **Compose Multiplatform web client** (Kotlin/Wasm) rendering the dashboard.
  iOS/Android can be added later as additional targets of the same app module.
- A **shared module** holding the telemetry data model, serialized with
  kotlinx.serialization on both sides — no more `tygo`-style codegen, no more
  untyped `Vehicle extends Record<string, any>`.

Display-only for now. The `commands.xml` back-channel (app → mod) is explicitly out of
scope but the message protocol should not preclude it (see Protocol below).

## Scope change vs. the old stack

The old terminal consumed the **GameGlass** XML shape (`GGI`, `gameGlassInterface.xml`).
The mod has since been rebranded and writes its own schema: `vdTelemetry.xml`
(root `<VDT version="1">`, defined by `vdTelemetrySchema.xsd`). The new stack targets
the VDT schema exclusively — the schema file and `examples/xml/*.xml` are the contract
and become test fixtures.

**Known gap:** the mod already emits the PDA/map data the map panel needs
(`VDTelemetry.lua:230` writes `VDT.environment.pda` with `filename`/`width`/`height`
and `player` `posX`/`posZ`), but `vdTelemetrySchema.xsd` and the `examples/xml/*` files
don't include it yet. The schema and fixtures need to catch up to the mod. See Phase 10.

## Versions & stack

| Concern            | Choice                                                            |
|--------------------|-------------------------------------------------------------------|
| Kotlin             | 2.4.0                                                             |
| Build              | Gradle, version catalog (`gradle/libs.versions.toml`)             |
| Server             | Ktor 3.x (Netty or CIO), `ktor-server-websockets`, `ktor-server-cors` |
| Serialization      | kotlinx.serialization (JSON on the wire)                          |
| XML parsing        | `io.github.pdvrieze.xmlutil` (serialization-based, works in commonMain) |
| Web UI             | Compose Multiplatform, `wasmJs` target (Beta — acceptable for a LAN dashboard) |
| WS client          | Ktor client (`ktor-client-js` engine for wasmJs)                  |
| Settings persistence | `multiplatform-settings` (localStorage on web)                  |
| File watching      | `java.nio.file.WatchService`, wrapped in a coroutine `Flow`       |

> Check the Compose Multiplatform ↔ Kotlin 2.4.0 compatibility matrix when pinning
> versions; Compose MPP releases lag new Kotlin releases by a few weeks.

## Module layout

```
VDTerminal/
  settings.gradle.kts
  gradle/libs.versions.toml
  shared/          # KMP: jvm + wasmJs
    src/commonMain/kotlin/   # VDT data model, ServerMessage protocol
    src/commonTest/kotlin/   # XML parsing tests against examples/xml/*
  server/          # Kotlin/JVM, Ktor
    src/main/kotlin/
      Config.kt          # env vars, game dir resolution   (paths.go)
      XmlSource.kt       # watch + parse + StateFlow       (watcher.go, parser.go)
      AssetResolver.kt   # file / mod-folder / mod-zip     (assets.go)
      Dds.kt             # DDS header + BC1/2/3 decode     (image.go + bcn)
      ImagePipeline.kt   # decode, crop, PNG encode        (image.go)
      Server.kt          # Ktor routes, WS                 (main.go, hub.go)
  app/             # Compose MPP, wasmJs (desktop/android/ios later)
    src/commonMain/kotlin/
      net/               # WS client, reconnect, TelemetryRepository
      theme/             # colors, typography, brand accents
      components/        # Panel, Gauge, SimpleGauge, ProgressBar, ...
      panels/            # EngineTransmission, Implements, Lighting, MapPanel
      App.kt             # header / 3×2 grid / footer
    src/wasmJsMain/kotlin/  # entry point, wake lock, browser interop
```

## Protocol

One sealed hierarchy in `shared`, JSON over WebSocket:

```kotlin
@Serializable
sealed interface ServerMessage {
    @Serializable @SerialName("telemetry")
    data class Telemetry(val data: VdtData) : ServerMessage

    @Serializable @SerialName("error")
    data class Error(val message: String) : ServerMessage
}
```

Client → server messages are not needed yet; when `commands.xml` lands they become a
mirrored `ClientMessage` sealed interface. Nothing else to prepare now.

Endpoints (unchanged from Go server):

```
GET /health
GET /ws
GET /api/map-image
GET /            → serves the built wasm app (production)
```

Env vars keep their names: `VDT_PORT` (default 3001), `VDT_GAME_DIR`, `VDT_XML_FILE`
(default `<gameDir>/vdTelemetry.xml` — note: no longer `gameGlassInterface.xml`).

---

## Phase 1 — Project skeleton

- Gradle multi-module KMP project as laid out above, Kotlin 2.4.0, version catalog.
- `shared` targets `jvm()` and `wasmJs { browser() }`.
- CI-ready: `./gradlew build` compiles all modules.

**Acceptance:** empty modules compile; `server` runs and answers `GET /health`.

## Phase 2 — Typed VDT model (`shared`)

- Model the VDT schema as `@Serializable` data classes with xmlutil annotations:
  `VdtData(environment, vehicle?)`, `Vehicle(speed, motor, lights, gps, cruiseControl,
  wearable, implements, combined, ...)` etc. Derive fields from `vdTelemetrySchema.xsd`
  plus what the mod actually emits (the xsd lags: `environment.pda` — filename, width,
  height, `player` posX/posZ — is written by `VDTelemetry.lua:230` but missing from the
  xsd and examples). Model `pda` as optional from the start; the map panel needs it.
- Watch the attribute+text pattern (`<speed unit="km/h" direction="STOPPED">0</speed>`):
  text content maps via `@XmlValue`.
- Unknown/extra elements must not crash parsing (`pedantic = false` policy) — the mod
  will evolve faster than the client.
- Tests in `commonTest`: parse all three files in `examples/xml/` and assert key values
  (speed, rpm, fill units, implement count).

**Acceptance:** all example XMLs parse; model round-trips to JSON; tests green.

## Phase 3 — Server core: config, watcher, WebSocket

- **Config/paths** (port of `paths.go`): Windows profile dir, Linux Steam/Proton dir,
  env overrides. Log resolved paths at startup.
- **XmlSource**: `WatchService` on the *directory* (the game replaces the file rather
  than editing it), filter by filename, debounce 150 ms, parse, emit into a
  `MutableStateFlow<VdtData?>`. Parse failures log and keep the last good state.
- **WebSocket**: on connect, send current state if present, then collect the StateFlow
  and push updates. No hand-rolled hub needed — the StateFlow *is* the hub; each WS
  session is one collector coroutine.
- CORS wide open (LAN tool), same as the Go server.

**Acceptance:** `websocat ws://localhost:3001/ws` receives initial state and live
updates when the XML is touched; multiple clients receive broadcasts; server survives
file deletion/recreation.

## Phase 4 — Asset resolution + image pipeline

- **AssetResolver** (port of `assets.go`): absolute path → gameDir-relative →
  `mods/<mod>.zip` (via `java.util.zip.ZipFile`) → `mods/<mod>/` folder. Clear 404 on miss.
- **DDS decoder** (port of `image.go` + the used subset of `woozymasta/bcn`):
  header parse with a little-endian `ByteBuffer`, then BC1/BC2/BC3 (DXT1/3/5) block
  decompression to RGBA, plus the uncompressed-RGBA8 fallback. This is a mechanical
  port of *validated* code — keep behavior identical, including center-crop to PDA
  width/height and always-PNG output (`BufferedImage` + `ImageIO`).
- **Golden tests:** run the existing Go server once over the known FS25 DDS files,
  save its PNG output as fixtures, and assert the Kotlin pipeline produces
  pixel-identical (or visually identical + dimension-identical) results. This retires
  the only real technical risk on day one.

**Acceptance:** `GET /api/map-image` returns the same image the Go server returns for
the same inputs; golden tests green; zipped and unzipped mods both resolve.

## Phase 5 — Web app skeleton + live data

- Compose wasmJs entry point; dev loop via `wasmJsBrowserDevelopmentRun` with a dev-server
  proxy for `/ws` and `/api` → `localhost:3001` (mirrors the old Vite proxy).
- `TelemetryRepository` in `commonMain`: Ktor WS client, reconnect with fixed 2 s backoff
  (same behavior as `socket.ts`), exposing `StateFlow<ConnectionState>` and
  `StateFlow<VdtData?>`.
- Minimal UI: "VDTERMINAL LOADING…" state, "No vehicle connected" state, and one raw
  value (speed) rendering live.

**Acceptance:** touching the XML on disk changes the number in the browser within ~200 ms;
killing and restarting the server reconnects automatically.

## Phase 6 — Design system & components

Port from Tailwind theme + components, smallest first:

- Theme: terminal colors, brand accent per `vehicle.brand` (the old `brand-*` CSS classes
  become a `brandColors: Map<String, Color>` lookup).
- `Panel` (title bar, icon, header actions slot), `ProgressBar`, `StatusIconButton`,
  `Gauge` / `SimpleGauge` as `Canvas` arcs (port the SVG math), `FillUnitsDisplay`.
- Icons: lucide isn't available — use `compose-material-icons` equivalents or convert the
  handful of needed lucide SVGs to `ImageVector`s.

**Acceptance:** a component gallery screen (dev-only) showing each component with fake data.

## Phase 7 — Panels & layout

- `Header`, `Footer`, `EngineTransmission`, `Implements`, `Lighting`, `EmptyPanel`;
  3×2 grid layout from `App.tsx`. (`Tools.tsx` / `Operations.tsx` are unused in the old
  App — port only if wanted.)
- Wire everything to the typed model — this is where the untyped `vehicle.foo?.bar`
  accesses from the TSX get replaced by real fields; any field the panels use that is
  missing from the VDT schema surfaces here as a compile error (good).

**Acceptance:** side-by-side with the old app on the same example XML, all panels show
the same values.

## Phase 8 — Map panel

No map library. One custom composable (~200 lines):

- Load `/api/map-image` bytes via Ktor client → `ImageBitmap`
  (`org.jetbrains.skia.Image.makeFromEncoded` on wasm).
- Pan/zoom: `detectTransformGestures` feeding scale/offset state, applied via
  `graphicsLayer`; clamp to image bounds (the `maxBoundsViscosity` behavior), zoom
  range mirroring minZoom −2 … maxZoom 4 as scale factors.
- Player marker: the arrow as an `ImageVector`, positioned by
  `(posX * width, height − posZ * height)` through the same transform, rotated by
  `gps.heading`.
- Auto-center: `Animatable` offset animation on player movement; any drag gesture
  disables auto-center (same as `dragstart` → `onUserMove`).
- Persist `zoom` and `autoCenter` with `multiplatform-settings` (localStorage), matching
  the old `StorageProvider.localStorage("map")` behavior.
- Zoom −/+ and crosshair buttons in the panel header slot.

**Acceptance:** pan, pinch/scroll zoom, bounded panning, marker tracks player with
heading, auto-center toggle + animation, settings survive reload.

## Phase 9 — Browser niceties

- Wake lock: `navigator.wakeLock.request("screen")` via wasmJs interop (port of
  `wakeLock.ts`), re-acquire on visibility change.
- Connection-lost overlay driven by `ConnectionState`.

## Phase 10 — Schema/fixtures: document PDA data

The mod already emits the PDA element (`VDTelemetry.lua:230`):

```xml
<environment>
  ...
  <pda filename="..." width="2048" height="2048">
    <player posX="0.53" posZ="0.41"/>
  </pda>
</environment>
```

But `vdTelemetrySchema.xsd` and `examples/xml/*` don't include it. Since the xsd is the
published contract (`xsi:noNamespaceSchemaLocation` points at it), add the `pda` element
to the xsd (optional, additive — no version bump needed) and to the example XMLs so the
Phase 2 parsing tests cover it. Note the mod writes `player` even when `pda` attributes
are absent (`self.pda == nil`), so `filename`/`width`/`height` must be optional in both
xsd and model.

**Acceptance:** examples validate against the updated xsd; live game session shows the
map with the player marker moving.

## Phase 11 — Packaging & cleanup

- Production build: wasm bundle copied into server resources; single runnable artifact
  (fat jar via Shadow, or `jlink`/`jpackage` for a no-JVM-install distribution;
  GraalVM native-image is an optional later experiment).
- README: dev workflow (`gradlew :server:run` + `gradlew :app:wasmJsBrowserDevelopmentRun`),
  env vars, packaging.
- Delete `VDTerminal_old/` only after feature parity is confirmed side-by-side.

**Acceptance:** one artifact starts on a machine with the game, browser on a tablet in the
same LAN shows the live dashboard.

---

## Suggested order (vertical slice first)

1. Phases 1–3 + 5 as one milestone: **XML change → WebSocket → number changes in browser.**
   This validates the entire new toolchain (Kotlin 2.4.0, wasm, Ktor, xmlutil) in days,
   before any porting effort is sunk.
2. Phase 4 (image pipeline + golden tests) in parallel — it's server-only and isolated.
3. Phases 6–7 (components/panels), then 8–9 (map, wake lock).
4. Phase 10 (mod PDA export) whenever convenient before the map goes live.
5. Phase 11 last.

## Risks

| Risk | Assessment |
|------|------------|
| Compose for Web is Beta | Acceptable: canvas-rendered LAN dashboard; no SEO/a11y/text-input needs. Requires WasmGC-capable browser. Wasm bundle is a few MB — fine on LAN. |
| DDS decoding on JVM | Low: mechanical port of the already-validated Go code, locked in by golden tests against Go output. |
| xmlutil mapping quirks (attributes + text content, lists of `<implement>`) | Low: covered by Phase 2 tests on real example files. |
| Kotlin 2.4.0 / Compose MPP / Ktor version alignment | Check compatibility matrix at kickoff; worst case start on the latest supported Kotlin and bump to 2.4.0 when Compose catches up. |
| Schema drift between mod and client | Mitigated by shared repo + `version` attribute + lenient parsing of unknown elements. |
