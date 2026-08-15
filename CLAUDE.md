# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A telemetry pipeline for **Farming Simulator 25**, in two independent parts joined only by a JSON file:

1. **`vdTelemetry/`** — the in-game mod (Lua). Reads live game state and writes it to `vdTelemetry.json` in the mod's own settings folder (`modSettings/FS25_vdTelemetry/telemetry/`), plus a sibling **export channel** file per subject (map, productions, contracts, finance, …), each on its own cadence. It also reads a command channel back (`commands/commands.xml`).
2. **`VDTerminal/`** — a standalone Kotlin Multiplatform app that watches those files and renders a live web dashboard.

The contract between the two is the shared Kotlin model (**`VDTerminal/shared/.../model/`**, one file per subject) plus the **`examples/json/`** fixtures. Changing the data shape means changing the Lua collectors/model and the Kotlin model together, and refreshing the fixtures. (The project previously used XML + an XSD; that has been fully migrated to JSON.)

## Planned and deferred work

**`FUTURE.md`** collects everything planned, deferred or still open across the whole repo — the
follow-ups the finished plans left behind and the in-game checks nobody has run. Read it before
proposing work that looks new; it may already be there, with the reason it was left. An *accepted*
limitation is a decision rather than future work, so it lives as a comment on the feature it belongs
to, not in `FUTURE.md`.

Feature plans live at the repo root as `<topic>-plan.md` while their feature is in progress, and are
**deleted once the feature is built** — the reasoning moves into the code, which is where it gets
read, and whatever the plan left undone moves to `FUTURE.md`. `isobus-plan.md` (issue #58) is the
only live one.

## Commit messages

Follow the commit-subject convention documented in `README.md` ("Commit messages"): `<issue> <modifier> <[area]> <subject>`, where the modifier is a gitmoji (a `commit-msg` hook rewrites the one-letter shorthands like `+`, `!`, `r`, `c` to their emoji).

## Formatting

Both subsystems are formatter-gated in CI (`.github/workflows/ci.yml`) — format before committing, or the build fails:

- **Kotlin (`VDTerminal/`)** → **Spotless** (ktlint). Run `./gradlew spotlessApply` from `VDTerminal/` (`spotlessCheck` is the CI gate). See `VDTerminal/README.md` → "Formatting".
- **Lua (`vdTelemetry/`)** → **StyLua** (config `vdTelemetry/stylua.toml`). Run `stylua .` from `vdTelemetry/` (`stylua --check .` is the CI gate). See `vdTelemetry/Readme.md`.

## Design rules (the app)

Two standing constraints on every panel, widget and mark — full version, with the worked answers, in `VDTerminal/README.md` → "Design rules":

- **Hue never carries a state on its own.** Two states must differ in brightness, shape or a word; colour may reinforce, never decide. (The user is colour blind and could not read the cluster's amber-armed against its green-engaged.)
- **A mark that carries meaning is an `Icon`, not a character.** The wasm build has no font fallback, so `▲ ▼ ✕ →` render as tofu; inside a sentence, host the `Icon` in an `InlineTextContent` so the line still ellipsizes as one `Text`.

## The mod (`vdTelemetry/`)

Plain Lua, no build system — the deliverable is `FS25_vdTelemetry.zip` (a zip of the folder's runtime files: the `.lua` files, `modDesc.xml`, `icon_vdTelemetry.dds`, `LICENSE`; `*.zip` is git-ignored). Packed with [FSTools](https://github.com/VertexDezign/FSTools) (`fs pack`), locally and in the release workflow alike: **`.fsignore` is the single definition of what does not ship**, and `fstools.toml` rewrites author/title into the packed `modDesc.xml` — but deliberately not the version, which `modDesc.xml` owns and ships as written.

- `VDTelemetry.lua` — the mod entry point and main loop. It `source()`s the files listed in its `sourceFiles` table (order matters: dependencies first), then on a timer builds a model and writes `modSettings/<modName>/telemetry/vdTelemetry.json`; reads config from `vdTelemetrySettings.xml` at the root of that same `modSettings/<modName>/` folder (settings use the engine's XML API — unrelated to the telemetry output). Everything lives under `modSettings/<modName>/` because the engine only permits `deleteFile()` there (disabling export deletes the json). Export enabled, write interval and the performance profile are also editable in-game (General Settings) via `src/gui/SettingsFrame.lua`; per-channel enable/interval is XML-only.
- The exporter is a **collect → model → serialize** pipeline. `src/collect/` holds the collectors (`EnvironmentExporter`, `VehicleExporter`, `vehicle/` for motor/lights/steering/support, `aspects/` for collectors valid on any vehicle *or* implement, and one `*Exporter.lua` per non-vehicle channel — map, ground layers, GPS course, productions, storage, husbandry, contracts, finance, field info); `src/export/ExportChannels.lua` is the registry every channel self-registers into (cadence, enable toggle, performance profile, farm scope); `src/command/` is the write side, one control per command type; `src/integrations/` holds optional third-party mod hooks (e.g. Enhanced Vehicle) run via a stage registry — for mods that may or may not be installed; **FS25_additionalInputs is a hard requirement, so its data is treated as core in `collect/`, not an integration**. `src/model/` holds annotation-only `---@class` shape defs; `src/utils/Json.lua` is the pure-Lua encoder. **All runtime modules live under a single `VDT.*` namespace table** to avoid clobbering FS25's bare-global specialization classes (`Lights`, `FillUnit`, …).
- `GrisuDebug.lua` — logging helper (`VDTelemetry.debugger`), levels configurable via the settings XML.
- `src/mapper/ValueMapper.lua` — value normalization (enums, unit conversions like m/s→km/h, percentages). Collectors run it through `tonumber()` where the JSON field is numeric.
- `fsTypes/` — EmmyLua/LuaLS **type stubs only** (`Vehicle`, `XMLFile`) for IDE type-checking against the FS25 engine API. Not sourced at runtime, not shipped.

Runtime dependency: **FS25_additionalInputs** (declared in `modDesc.xml`) — action-event/input handling lives there. `examples/json/` holds sample outputs — the vehicle captures at its root and under `telemetry/`, and one folder per channel — that double as VDTerminal parser fixtures. They are **real game captures, never hand-authored** (see FUTURE.md → "Captures wanted as fixtures").

When editing the JSON output, keep `VDTelemetry.VERSION` (in `VDTelemetry.lua`), the Lua model, and the Kotlin model in sync. Each secondary channel carries its **own** version, independent of `VDTelemetry.VERSION`.

## VDTerminal (Kotlin Multiplatform)

Gradle (Kotlin DSL, version catalog in `gradle/libs.versions.toml`, wrapper 9.6.1). Three modules — see `VDTerminal/README.md` for the full dev/prod story:

- **`shared`** (`jvm` + `wasmJs`) — the single source of truth for the data layer: `model/` (the typed VDT model, `@Serializable`, one file per subject — `VdtData.kt` is the telemetry root, `Vehicle.kt` the bulk of it), `Protocol.kt` (`ServerMessage` WebSocket wire types, kotlinx.serialization), `VdtParser.kt` (one lenient `parseX` per channel). Both server and app depend on it.
- **`server`** (Kotlin/JVM, Ktor + Netty) — watches the mod's `telemetry/` folder (every channel file, not just the telemetry one), parses via `shared`, broadcasts model updates over a WebSocket, writes the app's commands back out as `commands/commands.xml` (`CommandWriter.kt`), decodes the map DDS image to PNG (`Dds.kt` / `ImagePipeline.kt` / `AssetResolver.kt`), accumulates the coverage ground layer it derives itself, and serves the built wasm app.
- **`app`** (Compose Multiplatform, `wasmJs`) — the dashboard UI: `apps/` (the launchable features, registered in `AppRegistry`), `pages/` + `widgets/` (the arrangeable tiles), `panels/`, `components/`, `alerts/`, and `TelemetryRepository.kt` consuming the WebSocket.

Data flow: `vdTelemetry.json` → server file-watch → `VdtParser.parseJson` (shared) → `ServerMessage.Telemetry` JSON over WebSocket → app `TelemetryRepository` → Compose panels. Every other channel takes the same path with its own `parseX` and its own `ServerMessage` variant. Commands go the other way: app → WebSocket → server `CommandWriter` → `commands/commands.xml` → the mod's poll.

### Commands

Run from `VDTerminal/`.

```bash
./gradlew :server:run                              # telemetry server on :3001
./gradlew :app:wasmJsBrowserDevelopmentRun         # web dev server on :8080 (proxies /ws,/api -> :3001)
./gradlew :shared:jvmTest                           # JSON decode/round-trip + model assertions over examples/json/*
./gradlew :server:test                              # DDS decoder + ground-layer PNG golden tests
./gradlew :app:wasmJsBrowserTest                    # app-side unit tests (headless Chrome)
./gradlew check                                     # everything CI runs, spotlessCheck included
./gradlew :server:installDist                       # production build: embeds prod wasm bundle, one process serves all on :3001
./gradlew :server:packageRelease                    # release build: jpackage app image + bundled JRE, for the OS you're on
```

`packageRelease` is what `.github/workflows/release.yml` runs on a Windows and a Linux runner when a `v*` tag is
pushed; it also builds the mod zip and publishes both as a GitHub prerelease. jpackage cannot cross-compile, which is
why the workflow is a matrix. User-facing setup instructions live in `docs/setup.en.md` and `docs/setup.de.md` — they
ship inside the release archive, so keep the two languages in step.

Run a single test class: `./gradlew :shared:jvmTest --tests "net.vertexdezign.vdt.VdtModelTest"`.

Config is via env vars: `VDT_PORT` (3001), `VDT_GAME_DIR`, `VDT_FILE` (the telemetry file, default `<gameDir>/modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json`; its folder is where the other channel files are read from), `VDT_COMMAND_FILE` (derived from `VDT_FILE` by stepping out of its `telemetry/` folder; off that layout it lands beside `VDT_FILE` with a warning, since the mod polls only its own folder), `VDT_DEBOUNCE_MS` (file-watch debounce, default 40). Requires JDK 21+.

The DDS golden fixtures in `server/src/test/resources/dds/` are reference outputs originally generated from the Go `bcn` library — treat them as golden data, don't hand-edit.
