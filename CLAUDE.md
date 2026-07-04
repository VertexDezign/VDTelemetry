# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A telemetry pipeline for **Farming Simulator 25**, in two independent parts joined only by an XML file and its schema:

1. **`vdTelemetry/`** — the in-game mod (Lua). Reads live game state each frame and writes it to `vdTelemetry.xml` in the FS25 user directory.
2. **`VDTerminal/`** — a standalone Kotlin Multiplatform app that watches `vdTelemetry.xml` and renders a live web dashboard.

**`vdTelemetrySchema.xsd`** (repo root) is the contract between the two: the mod produces XML conforming to it, and VDTerminal parses/validates against it. Changing the data shape means changing the XSD, the Lua writer, and the Kotlin model together.

## Commit messages

Follow the commit-subject convention documented in `README.md` ("Commit messages"): `<issue> <modifier> <[area]> <subject>`, where the modifier is a gitmoji (a `commit-msg` hook rewrites the one-letter shorthands like `+`, `!`, `r`, `c` to their emoji).

## The mod (`vdTelemetry/`)

Plain Lua, no build system — the deliverable is `FS25_vdTelemetry.zip` (a zip of the folder's runtime files: the `.lua` files, `modDesc.xml`, `icon_vdTelemetry.dds`, `LICENSE`; `*.zip` is git-ignored).

- `VDTelemetry.lua` — the mod entry point and main loop. It `source()`s the files listed in its `sourceFiles` table (order matters: dependencies first). Writes `vdTelemetry.xml`; reads config from `vdTelemetrySettings.xml` in `modSettings/`.
- `GrisuDebug.lua` — logging helper (`VDTelemetry.debugger`), levels configurable via the settings XML.
- `src/utils/` (`Set`, `MapUtil`), `src/mapper/ValueMapper.lua` — value normalization (enums, unit conversions like m/s→km/h, percentages) applied before writing XML.
- `fsTypes/` — EmmyLua/LuaLS **type stubs only** (`Vehicle`, `XMLFile`) for IDE type-checking against the FS25 engine API. Not sourced at runtime, not shipped.

Runtime dependency: **FS25_additionalInputs** (declared in `modDesc.xml`) — action-event/input handling lives there. `examples/xml/` holds sample outputs (combine, tractor+cultivator, multiple implements) that double as VDTerminal parser fixtures.

When editing the XML output, keep `VDTelemetry.XML_VERSION` (in `VDTelemetry.lua`) and the XSD in sync.

## VDTerminal (Kotlin Multiplatform)

Gradle (Kotlin DSL, version catalog in `gradle/libs.versions.toml`, wrapper 9.6.1). Three modules — see `VDTerminal/README.md` for the full dev/prod story:

- **`shared`** (`jvm` + `wasmJs`) — the single source of truth for the data layer: `Model.kt` (typed VDT model), `Protocol.kt` (`ServerMessage` WebSocket wire types, kotlinx.serialization), `VdtParser.kt` (xmlutil-based XML→model). Both server and app depend on it.
- **`server`** (Kotlin/JVM, Ktor + Netty) — watches the telemetry file, parses via `shared`, broadcasts model updates over a WebSocket, decodes the map DDS image to PNG (`Dds.kt` / `ImagePipeline.kt` / `AssetResolver.kt`), and serves the built wasm app.
- **`app`** (Compose Multiplatform, `wasmJs`) — the dashboard UI (`panels/`, `components/`, `TelemetryRepository.kt` consuming the WebSocket).

Data flow: `vdTelemetry.xml` → server file-watch → `VdtParser` (shared) → `ServerMessage.Telemetry` JSON over WebSocket → app `TelemetryRepository` → Compose panels.

### Commands

Run from `VDTerminal/`.

```bash
./gradlew :server:run                              # telemetry server on :3001
./gradlew :app:wasmJsBrowserDevelopmentRun         # web dev server on :8080 (proxies /ws,/api -> :3001)
./gradlew :shared:jvmTest                           # XML parse + JSON round-trip + XSD validation of examples/xml/*
./gradlew :server:test                              # DDS decoder golden tests
./gradlew :server:installDist                       # production build: embeds prod wasm bundle, one process serves all on :3001
```

Run a single test class: `./gradlew :shared:jvmTest --tests "net.vertexdezign.vdt.VdtParseTest"`.

Config is via env vars: `VDT_PORT` (3001), `VDT_GAME_DIR`, `VDT_XML_FILE`. Requires JDK 21+.

The DDS golden fixtures in `server/src/test/resources/dds/` are reference outputs originally generated from the Go `bcn` library — treat them as golden data, don't hand-edit.
