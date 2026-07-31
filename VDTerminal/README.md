# VDTerminal

Real-time dashboard for the VDTelemetry Farming Simulator 25 mod, rebuilt as a Kotlin
Multiplatform project (replacing the old React/Vite + Go stack in `../VDTerminal_old`).

- **`shared`** (KMP: `jvm` + `wasmJs`) — the typed VDT data model, the `ServerMessage` wire
  protocol (kotlinx.serialization), and the JSON `VdtParser`.
- **`server`** (Kotlin/JVM, Ktor) — watches `vdTelemetry.json`, parses it, broadcasts over a
  WebSocket, serves the map image (DDS → PNG) and the ground-layer raster PNGs
  (`/api/map-layer/{id}`, one file per plane out of the mod's `mapLayers/` folder — which planes
  exist is discovered, not hardcoded), and serves the built web app.
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

## Display mode (a second device as a fixed screen)

A phone clamped in the cab is a different thing from the tablet: it shows *one* screen, is never
touched while driving, and every pixel spent on chrome is a pixel the readout doesn't get. Open the
dashboard with a `display` parameter naming a page (or an app) and that browser becomes a dedicated
display:

```text
http://<host>:3001/?display=vehicle   # pin this device to the Vehicle page
http://<host>:3001/?display=map       # an app works too
http://<host>:3001/?display=off       # back to the normal shell
```

The parameter is applied once and then remembered per browser, so the bare address keeps working on
that device — a reload, a crash, or a home-screen shortcut all come back to the same screen. The
seeded pages are `vehicle` and `farm`; a page you made yourself shows its own `?display=…` address in
the page edit toolbar. Nothing is shared between devices: pages, favourites and widget state are
per-browser `localStorage`, so the phone's layout is its own.

A display drops the header, the bottom bar, the launcher, the notification centre, edit mode, page
swipe, and the auto-switch to the Farm page when you step out of the tractor. It keeps the loading and
connection states, the screen wake lock (requested automatically — there's no header toggle left to
press), and the widgets' own controls, which still work. Alert banners and the chime stay on the
tablet, so one alert doesn't announce itself twice in one cab.

**To leave display mode on the device itself**, press and hold anywhere for two seconds: a small bar
appears with the wake-lock state and EXIT DISPLAY, and hides itself again if you ignore it.

**Add to Home Screen** gives a display its own icon and no browser chrome at all
(`manifest.webmanifest` plus the iOS `apple-mobile-web-app-*` tags). The manifest deliberately
declares no `start_url`, so a shortcut launches the URL it was made from — install from
`/?display=vehicle` on the phone and from `/` on the tablet, and the two icons stay different.

## Pillar cluster

Three widgets that stack into an A-pillar instrument cluster, after the small display a modern
tractor puts between the windscreen and the right-hand window:

- **Telltales** — a wrapping band of lamps (turn signals, beams, work lights, beacon, parking brake,
  diff locks, AWD, coolant temperature and a general "needs attention"). Which lamps a band shows is
  per instance, since what matters differs per rig. A lamp the vehicle reports *nothing* about is
  absent rather than unlit — the drivetrain trio comes from Enhanced Vehicle, and an unlit diff-lock
  lamp is a claim we can't make without it. Engine warning, battery, brake system and service are
  drawn and offered but permanently absent: they wait on a maintenance mod that exports nothing yet.
  (The engine lamp used to be derived from temperature *or* damage; those are now their own two
  lamps, and it waits for the mod's own engine fault, which is a different claim from either.)
- **Cluster Readout** — engine speed over ground speed in the largest type that fits, then the cruise
  target and the gear under them in amber. Both numbers are tweened over one sample interval so they
  read continuously rather than stepping at the telemetry rate. The transmission's direction rides
  beside the speed as an arrow plus the F/R/N letter the game itself prints, and **flashes while the
  machine is standing still** — it says where the machine will go, and at a standstill that is a plan
  rather than a fact. Neutral neither flashes nor draws an arrow: `N` is simply true.

  It comes from `motor.direction` (mod version 6), *not* from the engine's `getReverserDirection()` —
  that is written only by the reversible-driving-position specialization (the seat swivelled round),
  so on an ordinary tractor it reads forward for ever.
- **Level Strip** — the compact vertical form of the fill-unit bars: coolant temperature, then the
  engine's fuel, DEF and air. Each is an open-topped frame — green over the working range, red across
  the tenth where the gauge is in trouble — with a light, ten-band level standing in it, so where the
  trouble *starts* is visible before you are in it. Engine gauges only; what's in the hopper changes
  shape as you hitch things up and belongs to the rig-slot tiles, which name it and give figures.

They're ordinary widgets, placeable anywhere, and they render dark rather than in the usual panel
chrome. A seeded **Pillar** page stacks all three; it never auto-shows, because it is the page you
pin a second device to (`?display=pillar`). A pinned display paints the whole viewport black, so the
gutters between cluster tiles don't read as a grid of seams; the hand-held tablet keeps the light
terminal look.

The type is **DSEG Classic**, bundled under the SIL Open Font License — see
[`licenses/DSEG-OFL-1.1.txt`](licenses/DSEG-OFL-1.1.txt). Two faces: **DSEG7** for the rpm, speed and
cruise, and **DSEG14** for the two fields that carry letters — the direction and the gear. Seven
segments cannot draw a capital `R`, `N` or `D` and fall back to half-height lowercase, which reads as
a smaller letter rather than a different one. The gear is alphanumeric *throughout*, not only when it
happens to hold a letter, so the field doesn't change face with its own value. Real dashboards split
the same way — numeric fields are seven-segment, alphanumeric ones are not.

Three DSEG conventions are load-bearing: `!` is an all-off cell, an all-on one is the faint ghost
behind every readout (`8` on the seven-segment face, `~` on the fourteen — `8` would leave its
diagonals dark), and `.` has zero advance, so `12.4` occupies three cells and not four. Each readout
reserves a fixed cell count, which is why a number getting shorter unlights a cell instead of shifting
its neighbours.

## Portrait layouts

A page holds **one arrangement per orientation**. The shell measures the page body and renders either
the landscape one — a 12 × 7 grid, tuned so an 11" tablet gets ~91dp square cells — or the portrait
one, 6 × 12, tuned so a phone with no chrome around it does too (~56dp square on an iPhone 15 Pro).
Square cells are the point: every span a widget declares is calibrated against one, so the same grid
on a 393dp-wide phone would have given 32 × 120dp stripes and a page that was wrong rather than
merely cramped.

Edit mode edits whichever arrangement is on screen — turn the device to rearrange the other. Tiles
keep their identity across the two, so a map's zoom, filters and layer follow it round instead of
resetting when you rotate; the arrangements diverge only where you make them, since adding or removing
a tile in one orientation leaves the other alone. A page saved before portrait existed gets one
derived from its landscape arrangement (12 → 6 columns is an exact halving), so no stored layout was
thrown away and the storage key did not have to change.

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
