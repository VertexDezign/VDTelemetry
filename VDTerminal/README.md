# VDTerminal

Real-time dashboard for the VDTelemetry Farming Simulator 25 mod, built as a Kotlin Multiplatform
project (it replaced an earlier React/Vite + Go stack, which is no longer in this repo).

- **`shared`** (KMP: `jvm` + `wasmJs`) — the typed VDT data model, the `ServerMessage` wire
  protocol (kotlinx.serialization), and the JSON `VdtParser`.
- **`server`** (Kotlin/JVM, Ktor) — watches the mod's `telemetry/` folder (`vdTelemetry.json` and
  every other channel file), parses each with `shared`, broadcasts over a WebSocket, writes the app's
  commands back out as `commands/commands.xml`, serves the map image (DDS → PNG) and the ground-layer
  raster PNGs (`/api/map-layer/{id}`, one file per plane out of the mod's `mapLayers/` folder — which
  planes exist is discovered, not hardcoded), and serves the built web app. It also **derives** one
  ground layer of its own — see below.
- **`app`** (Compose Multiplatform, `wasmJs`) — the dashboard UI.

## What it shows

The shell is a launcher of **apps** and a set of **pages**. An app owns one full-screen view and
contributes tiles (**widgets**) that any page can place; a page is a grid you arrange yourself. An app
whose mod isn't installed is not listed at all, rather than showing an empty screen.

| App | What it covers |
|---|---|
| **Vehicle** | the machine you're driving: engine and transmission, lighting, and a rig laid out the way it sits — front, machine, rear — with each slot's fill units, sections and rates |
| **Map** | the PDA map: the DDS map image, POIs, fields, vehicle markers, the steering course, and the ground-layer overlays below |
| **Production** / **Storage** / **Animals** | the farm's production points and factories, its silos and object storages, and its animal pens |
| **Contracts** | the farm's missions — on offer, running, waiting to be collected — with accept / cancel / collect |
| **Finance** | the balance, the month-by-month table and the money log, borrow and repay; Enhanced Loan System's annuity loans stand in for the base loan where it is installed, and FS25_Invoices adds an Invoices tab |
| **Tasks** / **Crop Rotation** | FS25_TaskList and FS25_CropRotation, both read *and* write |
| **Diagnostics** | what the mod is actually writing: each channel's observed cadence and staleness, measured server-side |

Alerts (low fuel, tasks due, …) are raised by the apps but evaluated shell-wide, so one fires whatever
is on screen.

## Ground layers, and the one the server owns

Most ground-layer planes (crops, growth, soil, …) are swept by the mod and read from its
`mapLayers/` folder. **Coverage** is different: it is accumulated by the server from the work-area
footprints already in the telemetry, because nothing the mod can sample records it — a tedder
spreads a windrow and leaves the map exactly as it found it. Not *every* work area: the ones that
only put material out behind the machine (a combine's straw chopper and swath, a potato header's
haulm drop) are skipped, here and in the map's swath overlay, since they trail the pass rather than
being it — see `WorkArea.coversGround`.

It reaches the app as an ordinary plane (same catalogue, same `/api/map-layer/coverage`, same
legend), with three differences worth knowing:

- It is offered even when the mod's layer channel is off, and its id is never sent back to the mod
  as a subscription — the mod has no such plane to sweep.
- It lives in memory for as long as the server runs, and clears when another map is loaded.
  `POST /api/coverage/reset` clears it on request (the app offers this under the layer in the map's
  filter popover). There is nothing for the mod to do either way.
- Cells are ~1 m rather than the mod's 512-cell map overlay, since the layer is read for whether a
  strip was *missed*. On a 2 km map that is a 2048² mask, and so a 2048×2048 bitmap in the browser.
- Worked ground is **magenta**, not the green "done" usually is: this layer is read over grass, on a
  green map, under a course that shades its own worked lines green. The colour is published in the
  legend and the app's live trail reads it back from there, so the two halves of the layer cannot
  drift apart.

## Requirements

- JDK 25+ (developed and verified on Temurin 26). The Gradle wrapper (9.6.1) is included; no
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
| `VDT_FILE`     | `<gameDir>/modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json` | telemetry file to watch; its folder is where every other channel file is read from, and where the command file is derived from — see below |
| `VDT_COMMAND_FILE` | `<the same mod folder>/commands/commands.xml`    | command file the server writes; derived from `VDT_FILE` unless set |
| `VDT_DEBOUNCE_MS` | `40`                                             | debounce window for file writes  |

`VDT_COMMAND_FILE` is derived from `VDT_FILE` by stepping out of its `telemetry/` folder:
`<mod>/telemetry/vdTelemetry.json` becomes `<mod>/commands/commands.xml`, which is the layout the mod
writes and polls. Point `VDT_FILE` at a path with no `telemetry/` folder in it and the commands land
beside the telemetry file instead — `/tmp/vdTelemetry.json` gives `/tmp/commands/commands.xml` — and
the server warns at startup, because the mod polls only its own folder. So off that layout, set
`VDT_COMMAND_FILE` explicitly. Getting it wrong breaks one direction only, which is what makes it
worth a warning: the panels keep updating, but nothing you press reaches the game.

## Production (single artifact)

```bash
./gradlew :server:installDist
VDT_FILE=/path/to/modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json server/build/install/server/bin/server
```

`:server:installDist` builds the production wasm bundle and embeds it in the server's resources,
so the one server process serves both the dashboard (`/`) and the API/WebSocket. Open
<http://localhost:3001> on any device on the LAN. (`distZip`/`distTar` produce the same as a
portable archive.)

## Release packaging

All three of those need a JDK on the machine, which a Farming Simulator player has no reason to
have. `packageRelease` wraps the same `installDist` tree with a JRE via **jpackage**, so the
published download is "unzip and run":

```bash
./gradlew :server:packageRelease     # -> server/build/release/VDTerminal-<version>-<os>-x64.(zip|tar.gz)
```

jpackage cannot cross-compile: the image is for whatever OS built it, which is why
`.github/workflows/release.yml` runs that task on a Windows *and* a Linux runner. macOS is not
built — the server reads the files the game is writing, so it has to sit on the machine running
FS25, and that is Windows or Proton.

Two things the release path pins that a dev build otherwise wouldn't:

- **JVM target 25 and `-Xjdk-release=25`**, in `shared` and `server` alike. Both halves are needed
  for "JDK 25+" to be true of what a developer on 26 produces: the target alone stamps the class
  file 25 while still letting a JDK 26 API link into it, and that failure would land on a user's
  machine rather than in CI. jpackage bundles the JDK it runs from, so the release workflow's `25`
  is what the two app images ship and what the portable zip asks of a JDK you bring.
- **The version**, via `-PvdtVersion=…`, which the workflow feeds from the git tag after checking it
  against the checked-in numbers. A tag like `v0.1.0-alpha.1` names the archives; jpackage's own
  metadata gets the numeric head, since that is all it accepts.

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
seeded pages are `vehicle`, `farm` and `pillar`; a page you made yourself shows its own `?display=…` address in
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

Four widgets that stack into an A-pillar instrument cluster, after the small display a modern
tractor puts between the windscreen and the right-hand window:

- **Telltales** — a wrapping band of lamps (turn signals, beams, work lights, beacon, parking brake,
  diff locks, AWD, and the six maintenance lamps). Which lamps a band shows is per instance, since
  what matters differs per rig. A lamp the vehicle reports *nothing* about is absent rather than
  unlit — the drivetrain trio comes from Enhanced Vehicle, and an unlit diff-lock lamp is a claim we
  can't make without it.

  The maintenance six (engine warning, coolant temperature, battery, brake system, service, needs
  attention) come from **Advanced Damage System**, and follow the same rule twice over: without the
  mod there is nothing to say, and *with* it a lamp the machine is too old to have stays absent too —
  ADS gates each on the vehicle's production year, so a 1960s tractor's band doesn't grow lamps its
  dashboard never had. Two of them keep a base-game fallback for a game without the mod: coolant
  temperature off the gauge, and "needs attention" off vanilla damage.

  A lamp lights in the **severity ADS reports** — blue for a coolant lamp that hasn't warmed up,
  amber, then red — and a critical lamp **flashes** as well, so the ladder never rests on hue alone.
  The cold coolant lamp gets its own glyph rather than the hot one in another colour: not warmed up
  is not a milder version of boiling.
- **Service** — how long this machine has before its service is due, and system voltage, both from
  Advanced Damage System. It answers the one question the rest of the cluster can't: whether to take
  this machine out today. Nothing on it is a number ADS hides behind a workshop diagnostic — and the
  pre-shift walk-round (radiator, air intake, lubrication) is deliberately not here either, coarse
  bands or not: you learn that by getting out and walking round the machine.
- **Cluster Readout** — engine speed over ground speed in the largest type that fits, then the cruise
  target and the gear under them in amber. Both numbers are tweened over one sample interval so they
  read continuously rather than stepping at the telemetry rate. The transmission's direction rides
  beside the speed as an arrow plus the F/R/N letter the game itself prints, and **flashes while the
  machine is standing still** — it says where the machine will go, and at a standstill that is a plan
  rather than a fact. Neutral neither flashes nor draws an arrow: `N` is simply true.

  It comes from `motor.direction` (mod version 6), *not* from the engine's `getReverserDirection()` —
  that is written only by the reversible-driving-position specialization (the seat swivelled round),
  so on an ordinary tractor it reads forward for ever.
- **Level Strip** — the compact vertical form of the fill-unit bars: coolant temperature, a CVT's own
  transmission temperature where Advanced Damage System reports one, then the engine's fuel, DEF and
  air. The two temperatures are told apart by their glyphs — a thermometer over water, and one over a
  gear — because the strip is read by icon alone. Each is an open-topped frame — green over the working range, red across
  the tenth where the gauge is in trouble — with a light, ten-band level standing in it, so where the
  trouble *starts* is visible before you are in it. Engine gauges only; what's in the hopper changes
  shape as you hitch things up and belongs to the rig-slot tiles, which name it and give figures.

Engine **load** arrived with the same work but is not part of the cluster: it goes on the Engine and
Transmission panel, having been exported since long before this and drawn nowhere. Advanced Damage
System's figure where there is one, since that is what the mod shows in the cab and what it charges
engine wear against; it goes amber past the mod's own overload threshold, and is deliberately not
clipped at 100%, because how far over you are is the part you would change your driving for.

They're ordinary widgets, placeable anywhere, and they render dark rather than in the usual panel
chrome. A seeded **Pillar** page stacks three of them — lamps, readout, levels, top to bottom, which
is the whole page; Service is the one you add where you want it, and it arrives as a single-row slab,
since an interval and a voltage is all that is left in it. The page never auto-shows, because it is
the page you pin a second device to (`?display=pillar`). A pinned display paints the whole viewport
black, so the gutters between cluster tiles don't read as a grid of seams; the hand-held tablet keeps
the light terminal look.

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

## Design rules

Two constraints that apply to **every** new panel, widget and mark. Both have cost a round of rework
already, and neither is discoverable from the code you happen to be editing — so they live here.

### Hue never carries a state on its own

A state told apart only by colour is a state some people cannot read, and this app's instruments are
read at a glance, off-axis, while driving. Amber-vs-green is the commonest confusion axis and the one
that bit us: the pillar cluster's amber "armed" against its green "engaged".

So for any two-state mark, the states must differ in **brightness**, in **shape**, or by a word. Hue
may reinforce that; it may never be the only cue. The two worked answers, both driven in-game:

- **On the black cluster — one colour, two alphas.** `ClusterReadout.ARMED_ALPHA` (0.45) against full
  brightness, which `guidanceMark` and the cruise line share. `GHOST_ALPHA` (0.09) is the *unlit*
  level, far too faint to stand for a live state.
- **On the light panels — fill the chip.** A live lamp is a solid colour chip with its mark knocked
  out in white: dark-on-light against light-on-dark, which is what `GuidanceLamp` and
  `StatusIconButton` do. Spend the chip's padding in every state, so nothing shifts when it lights.

Where a ladder has more than two rungs, add a second channel — the ADS telltales flash at critical as
well as reddening, so the severity survives without hue.

The trap to check for: `VdtColors.DarkGray` (5.0:1) and `AccentText` (5.3:1) are the *same* contrast,
so a lamp using those two for idle/active differs in nothing but hue. Put the state in the
`contentDescription` too — a map overlay with its labels hidden has no other reading.

### A mark that carries meaning is an `Icon`, not a character

The wasm build has **no font fallback**. A browser falls back through the system's fonts; Compose/wasm
draws into a canvas with the fonts it bundles — here the two DSEG faces plus the default — and that
default covers neither Geometric Shapes nor Dingbats. `▲ ▼ ✕ →` all shipped as tofu boxes before this
rule existed.

- Standing alone → a Material `Icon`, which is a vector and depends on no font at all.
- Inside a sentence → `InlineTextContent` hosting the `Icon`, so the line stays a single `Text` and
  still ellipsizes as one thing in a narrow tile (a `Row` of three pieces would not). `SectionView`'s
  rate readout is the worked example.
- Latin-1 and General Punctuation are fine: `— · × ± ° …` are used throughout and render. Above
  U+2000, assume anything that isn't punctuation is missing until you have seen it in a browser — the
  minus sign `−` (U+2212, in Mathematical Operators) is the one that reads as safe and isn't. Issue
  #77 pulled it and `≈` out of the money panels for their ASCII spellings.

If a new glyph is genuinely needed as text, look at it in a browser before shipping it.

## Tests

```bash
./gradlew :shared:jvmTest          # JSON decode/round-trip + model assertions over examples/json/*
./gradlew :server:test             # DDS decoder golden tests + ground-layer PNG rendering
./gradlew :app:wasmJsBrowserTest   # app-side unit tests, in headless Chrome
./gradlew check                    # all of the above plus spotlessCheck — what CI runs
```

The wasm tests need a Chrome/Chromium binary; the build points karma at `/usr/bin/chromium` on a dev
machine that has one, and `CHROME_BIN` always wins.

The DDS golden fixtures in `server/src/test/resources/dds/` were generated from the reference Go
`bcn` library by the retired Go server's `ddsgen` tool. Treat them as golden data — don't hand-edit
them.

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

- `directionFromHeading` (`panels/Navigation.kt`) rounds a heading into 16 sectors and then labels it
  from an 8-point compass, which is the game's own quirk — kept for parity, so a heading reads the
  same here as in the cab.
- The app localizes nothing of its own: strings the mod hands over are already localized by the game,
  everything the app writes itself is English.
- A half-upgraded install fails the parse rather than degrading: `ignoreUnknownKeys` carries a newer
  mod against an older terminal, but not the reverse. Mod and terminal ship from the same repo, so
  this only bites a mixed install.
