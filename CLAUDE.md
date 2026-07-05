# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A telemetry pipeline for **Farming Simulator 25**, in two independent parts joined only by a JSON file:

1. **`vdTelemetry/`** — the in-game mod (Lua). Reads live game state each frame and writes it to `vdTelemetry.json` in the FS25 user directory.
2. **`VDTerminal/`** — a standalone Kotlin Multiplatform app that watches `vdTelemetry.json` and renders a live web dashboard.

The contract between the two is the shared Kotlin model **`VDTerminal/shared/.../Model.kt`** plus the **`examples/json/`** fixtures. Changing the data shape means changing the Lua collectors/model and the Kotlin model together, and refreshing the fixtures. (The project previously used XML + an XSD; that has been fully migrated to JSON.)

## Commit messages

Follow the commit-subject convention documented in `README.md` ("Commit messages"): `<issue> <modifier> <[area]> <subject>`, where the modifier is a gitmoji (a `commit-msg` hook rewrites the one-letter shorthands like `+`, `!`, `r`, `c` to their emoji).

## The mod (`vdTelemetry/`)

Plain Lua, no build system — the deliverable is `FS25_vdTelemetry.zip` (a zip of the folder's runtime files: the `.lua` files, `modDesc.xml`, `icon_vdTelemetry.dds`, `LICENSE`; `*.zip` is git-ignored).

- `VDTelemetry.lua` — the mod entry point and main loop. It `source()`s the files listed in its `sourceFiles` table (order matters: dependencies first), then on a timer builds a model and writes `vdTelemetry.json`; reads config from `vdTelemetrySettings.xml` in `modSettings/` (settings still use the engine's XML API — unrelated to the telemetry output).
- The exporter is a **collect → model → serialize** pipeline. `src/collect/` holds the collectors (`EnvironmentExporter`, `VehicleExporter`, `vehicle/` for motor/lights/support, `aspects/` for collectors valid on any vehicle *or* implement); `src/integrations/` holds optional third-party mod hooks (e.g. Enhanced Vehicle) run via a stage registry — for mods that may or may not be installed; **FS25_additionalInputs is a hard requirement, so its data is treated as core in `collect/`, not an integration**. `src/model/` holds annotation-only `---@class` shape defs; `src/utils/Json.lua` is the pure-Lua encoder. **All runtime modules live under a single `VDT.*` namespace table** to avoid clobbering FS25's bare-global specialization classes (`Lights`, `FillUnit`, …).
- `GrisuDebug.lua` — logging helper (`VDTelemetry.debugger`), levels configurable via the settings XML.
- `src/mapper/ValueMapper.lua` — value normalization (enums, unit conversions like m/s→km/h, percentages). Collectors run it through `tonumber()` where the JSON field is numeric.
- `fsTypes/` — EmmyLua/LuaLS **type stubs only** (`Vehicle`, `XMLFile`) for IDE type-checking against the FS25 engine API. Not sourced at runtime, not shipped.

Runtime dependency: **FS25_additionalInputs** (declared in `modDesc.xml`) — action-event/input handling lives there. `examples/json/` holds sample outputs (combine, tractor+cultivator, multiple implements) that double as VDTerminal parser fixtures.

When editing the JSON output, keep `VDTelemetry.VERSION` (in `VDTelemetry.lua`), the Lua model, and the Kotlin `Model.kt` in sync.

## VDTerminal (Kotlin Multiplatform)

Gradle (Kotlin DSL, version catalog in `gradle/libs.versions.toml`, wrapper 9.6.1). Three modules — see `VDTerminal/README.md` for the full dev/prod story:

- **`shared`** (`jvm` + `wasmJs`) — the single source of truth for the data layer: `Model.kt` (typed VDT model, `@Serializable`), `Protocol.kt` (`ServerMessage` WebSocket wire types, kotlinx.serialization), `VdtParser.kt` (`parseJson`: lenient JSON→model). Both server and app depend on it.
- **`server`** (Kotlin/JVM, Ktor + Netty) — watches the telemetry file, parses via `shared`, broadcasts model updates over a WebSocket, decodes the map DDS image to PNG (`Dds.kt` / `ImagePipeline.kt` / `AssetResolver.kt`), and serves the built wasm app.
- **`app`** (Compose Multiplatform, `wasmJs`) — the dashboard UI (`panels/`, `components/`, `TelemetryRepository.kt` consuming the WebSocket).

Data flow: `vdTelemetry.json` → server file-watch → `VdtParser.parseJson` (shared) → `ServerMessage.Telemetry` JSON over WebSocket → app `TelemetryRepository` → Compose panels. (Mid-migration: the mod still also writes `vdTelemetry.xml`, and `VdtParser.parse` retains the xmlutil path for the fixture tests; the server reads JSON.)

### Commands

Run from `VDTerminal/`.

```bash
./gradlew :server:run                              # telemetry server on :3001
./gradlew :app:wasmJsBrowserDevelopmentRun         # web dev server on :8080 (proxies /ws,/api -> :3001)
./gradlew :shared:jvmTest                           # JSON decode/round-trip + model assertions over examples/json/*
./gradlew :server:test                              # DDS decoder golden tests
./gradlew :server:installDist                       # production build: embeds prod wasm bundle, one process serves all on :3001
```

Run a single test class: `./gradlew :shared:jvmTest --tests "net.vertexdezign.vdt.VdtModelTest"`.

Config is via env vars: `VDT_PORT` (3001), `VDT_GAME_DIR`, `VDT_FILE` (the telemetry file, default `vdTelemetry.json`). Requires JDK 21+.

The DDS golden fixtures in `server/src/test/resources/dds/` are reference outputs originally generated from the Go `bcn` library — treat them as golden data, don't hand-edit.
