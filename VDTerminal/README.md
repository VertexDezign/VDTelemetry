# VDTerminal

Real-time dashboard for the VDTelemetry Farming Simulator 25 mod, rebuilt as a Kotlin
Multiplatform project (replacing the old React/Vite + Go stack in `../VDTerminal_old`).

- **`shared`** (KMP: `jvm` + `wasmJs`) — the typed VDT data model, the `ServerMessage` wire
  protocol (kotlinx.serialization), and the xmlutil-based `VdtParser`.
- **`server`** (Kotlin/JVM, Ktor) — watches `vdTelemetry.xml`, parses it, broadcasts over a
  WebSocket, serves the map image (DDS → PNG), and serves the built web app.
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

Then open <http://localhost:8080>. Editing `vdTelemetry.xml` updates the dashboard live.

## Configuration (environment variables)

| Variable       | Default                                             | Meaning                          |
|----------------|-----------------------------------------------------|----------------------------------|
| `VDT_PORT`     | `3001`                                              | server port                      |
| `VDT_GAME_DIR` | OS-specific FS25 profile dir (Windows / Linux+Proton) | game directory                 |
| `VDT_XML_FILE` | `<gameDir>/vdTelemetry.xml`                          | telemetry file to watch          |

## Production (single artifact)

```bash
./gradlew :server:installDist
VDT_XML_FILE=/path/to/vdTelemetry.xml server/build/install/server/bin/server
```

`:server:installDist` builds the production wasm bundle and embeds it in the server's resources,
so the one server process serves both the dashboard (`/`) and the API/WebSocket. Open
<http://localhost:3001> on any device on the LAN. (`distZip`/`distTar` produce the same as a
portable archive.)

## Tests

```bash
./gradlew :shared:jvmTest   # XML parsing + JSON round-trip + xsd validation of examples/xml/*
./gradlew :server:test      # DDS decoder golden tests vs the woozymasta/bcn reference
```

The DDS golden fixtures in `server/src/test/resources/dds/` are generated from the reference Go
`bcn` library via `../VDTerminal_old/apps/server-go/ddsgen`.

## Known gaps / simplifications

- The Lighting panel lays out the toggles functionally; the original tractor-schematic background
  image is not yet bundled (cosmetic).
- Map pan/zoom/auto-center and settings persistence are implemented but only verified by code +
  static rendering (gesture input wasn't driven in CI).
- `Footer` reproduces the original's 16-point-heading-into-8-slot direction quirk for parity.
