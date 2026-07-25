# VDTerminal

Real-time dashboard for the VDTelemetry Farming Simulator 25 mod, rebuilt as a Kotlin
Multiplatform project (replacing the old React/Vite + Go stack in `../VDTerminal_old`).

- **`shared`** (KMP: `jvm` + `wasmJs`) — the typed VDT data model, the `ServerMessage` wire
  protocol (kotlinx.serialization), and the JSON `VdtParser`.
- **`server`** (Kotlin/JVM, Ktor) — watches `vdTelemetry.json`, parses it, broadcasts over a
  WebSocket, serves the map image (DDS → PNG) and the ground-layer raster PNGs
  (`/api/map-layer/{id}`), and serves the built web app.
- **`app`** (Compose Multiplatform, `wasmJs`) — the dashboard UI.

## Requirements

- JDK 21+ (developed and verified on Temurin 26). The Gradle wrapper (9.6.1) is included; no
  separate Gradle install is needed.
- A WasmGC-capable browser (recent Chrome/Edge/Firefox).

## Development

Two processes, mirroring the old Vite setup:

```bash
# 1. the telemetry server on :3001
./gradlew :server:run

# 2. the web app dev server on :8080 (proxies /ws and /api -> :3001)
./gradlew :app:wasmJsBrowserDevelopmentRun
```

Then open <http://localhost:8080>. Editing `vdTelemetry.json` updates the dashboard live.

## Configuration (environment variables)

| Variable       | Default                                             | Meaning                          |
|----------------|-----------------------------------------------------|----------------------------------|
| `VDT_PORT`     | `3001`                                              | server port                      |
| `VDT_GAME_DIR` | OS-specific FS25 profile dir (Windows / Linux+Proton) | game directory                 |
| `VDT_FILE`     | `<gameDir>/modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json` | telemetry file to watch          |
| `VDT_DEBOUNCE_MS` | `40`                                             | debounce window for file writes  |

## Production (single artifact)

```bash
./gradlew :server:installDist
VDT_FILE=/path/to/vdTelemetry.json server/build/install/server/bin/server
```

`:server:installDist` builds the production wasm bundle and embeds it in the server's resources,
so the one server process serves both the dashboard (`/`) and the API/WebSocket. Open
<http://localhost:3001> on any device on the LAN. (`distZip`/`distTar` produce the same as a
portable archive.)

## Tests

```bash
./gradlew :shared:jvmTest   # JSON decode/round-trip + model assertions over examples/json/*
./gradlew :server:test      # DDS decoder golden tests + ground-layer PNG rendering
```

The DDS golden fixtures in `server/src/test/resources/dds/` are generated from the reference Go
`bcn` library via `../VDTerminal_old/apps/server-go/ddsgen`.

## Formatting

[Spotless](https://github.com/diffplug/spotless) runs [ktlint](https://pinterest.github.io/ktlint/)
over the Kotlin sources (`.kt`) and the Gradle build scripts (`*.gradle.kts`) in every module. The
[compose-rules](https://mrmans0n.github.io/compose-rules/) ktlint ruleset adds Compose-specific
checks and is applied to the `app` module's Compose UI (there its `compose:function-naming` replaces
the standard `function-naming` rule). Rules are tuned in the root `.editorconfig`.

```bash
./gradlew spotlessCheck   # verify formatting (fails on violations)
./gradlew spotlessApply   # auto-format in place
```

## Known gaps / simplifications

- The Lighting panel lays out the toggles functionally; the original tractor-schematic background
  image is not yet bundled (cosmetic).
- Map pan/zoom/auto-center and settings persistence are implemented but only verified by code +
  static rendering (gesture input wasn't driven in CI).
- `Footer` reproduces the original's 16-point-heading-into-8-slot direction quirk for parity.
