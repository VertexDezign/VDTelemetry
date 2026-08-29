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
| **Fields** | what is on the farm's land: every field as a row with its crop, its area, its price if it is for sale, what state the ground is in and what condition it is in (plough, weeds) — all three counted across the whole field off the crops, growth and soil rasters rather than sampled at its centre, so a field half cut reads as half cut, a field whose middle is a track still names its crop, and a multiplayer client's one stale cell no longer decides — filterable to what is yours, what is for sale, what needs work and what is ready, and cross-linked to the map |
| **Fleet** | every machine the farm owns, the way the game's own vehicle overview lists them — condition, operating hours, age, what each is worth — searchable and sortable, with Advanced Damage System's maintenance record (state, service interval, faults found, what is in the workshop) where that mod is installed, and a row that puts itself on the map |
| **Production** / **Storage** / **Animals** | the farm's production points and factories, its silos and object storages, and its animal pens |
| **Market** | the map's price board and what the farm is sitting on: every commodity with the stations that buy and sell it and its twelve-month curve, and a sortable stock table joining silos, bunkers, bales, pallets, what the production points have made and what the pens are waiting to be emptied of against it — one line per commodity however many containers it is spread over, with what it is worth now, where it sells best, and what the year's peak would make of it; both tabs filter through a type-ahead box that takes several tokens at once |
| **Contracts** | the farm's missions — on offer, running, waiting to be collected — with accept / cancel / collect |
| **Calendar** | the game's crop calendar — the sowing and harvest periods of every crop, with a today line — searchable by name and filterable to what can be sown or harvested *now*, over the weather forecast (now, twelve two-hourly steps, six days) |
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

- JDK 25+ (developed and verified on Temurin 26). The Gradle wrapper is included; no
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
- **The version**, via `-PvdtVersion=…`, which the workflow derives from the git tag — the only
  place a version is authored. A tag like `v0.1.0-alpha.1` names the archives; jpackage's own
  metadata gets the numeric head, since that is all it accepts. The number in `build.gradle.kts` is
  a placeholder for local builds, and cutting a release never touches it.

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

**Keeping the screen on takes two routes**, because the device that needs it most can't take the
first one. The Screen Wake Lock API is secure-context only: the gaming PC gets it at
`http://localhost:3001`, which counts as trustworthy, but the tablet arrives at
`http://<lan-ip>:3001`, where `navigator.wakeLock` is not refused — it is undefined. A LAN address
can't be given a certificate anyone trusts without installing a CA on every device, so `WakeLock.kt`
falls back to the older trick: a one-frame clip (`resources/media/keep-awake.*`, from NoSleep.js —
see `NOTICE`) looping at 1×1 px out of sight, which browsers keep the screen lit for with no
secure-context clause. Both routes install the same `window.__vdtWake*` pair, so the toggle, the
visibility re-acquire and the first-gesture retry don't know which is running, and `AWAKE` in the
header means the mechanism is running, not that a request was sent. What that is worth differs by
route: on the real API it is the spec's guarantee, while on the fallback it says the clip is playing
and leaves the idle timer to the platform — which held on an iPad, and is unverified on Android
(`FUTURE.md`). The fallback needs the tab in the foreground, exactly as the real API does.

**The clip must not stay muted, and that is the whole subtlety.** iOS yields the idle timer to media
playback that holds an audio session; a *muted* video plays perfectly and the screen dims on
schedule, which is exactly how the first cut of this failed on an iPad — and unmuting is what fixed
it there, confirmed on the device rather than reasoned about. The clips therefore carry a
silent audio track, and NoSleep.js never mutes. But an unprompted `play()` is only allowed while
muted, and a display arms itself with nobody having touched anything — so the fallback starts muted
and the first gesture upgrades it (`__vdtWakeMuted` is why the retry fires even when the lock reports
itself active). Pressing the header's coffee cup *is* that gesture, so a tablet needs one tap and no
more; a display needs one touch anywhere, which is also what the reveal bar asks for. The cost is the
audio session: silent or not, iOS treats it as playback, so it can stop whatever the driver was
listening to. Hence the guides' device-level alternative.

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

  **A switched-off machine has a switched-off display.** The readout and the level strip fall to
  their ghost layer — unlit cells where the numbers were, empty frames where the levels were, and
  nothing flashing — so whether the machine is running is answered the way the panel this copies
  answers it, by being on or off. Only `MotorState.OFF` is dark: a key rested at the ignition lock
  lights a real dashboard, and that is where the bulb check runs, so the panel wakes as the key turns
  and reads zeros until the engine catches. The **telltale band stays lit**, because a parked machine
  can genuinely have its beacon on or its hazards going (`Lights:onStopMotor` re-applies the light
  mask rather than clearing it) and the band is the only place that shows it.
- **Level Strip** — the compact vertical form of the fill-unit bars: coolant temperature, a CVT's own
  transmission temperature where Advanced Damage System reports one, then the engine's fuel, DEF and
  air. The two temperatures are told apart by their glyphs — a thermometer over water, and one over a
  gear — because the strip is read by icon alone. Each is an open-topped frame — green over the working range, red across
  the tenth where the gauge is in trouble — with a light, ten-band level standing in it, so where the
  trouble *starts* is visible before you are in it. Engine gauges only; what's in the hopper changes
  shape as you hitch things up and belongs to the rig-slot tiles, which name it and give figures.

Engine **load** is on both. On the Engine and Transmission panel it is a figure; on the cluster's
readout it is a slim vertical bar standing over the `RPM` caption, filling from the bottom — load is
read as "how much of what it has is it using", which a bar answers at a glance and a two-digit
percentage does not, and the label column had all that height going spare. Both take Advanced Damage
System's figure where there is one, since that is what the mod shows in the cab and what it charges
engine wear against; it is deliberately not clipped at 100% **where it is printed**, because how far
over you are is the part you would change your driving for. A bar has an end, so it pins at full and
the real number stays on the panel that can show it.

Past the overload threshold the bar's fill goes amber — but that point is also **notched into the
bar**, so what says you are over it is the fill crossing a line rather than a colour. ADS ships 85%
and a player can move it; the notch travels with the setting, and is absent when it would land on the
bar's own end cap — where the fill then goes amber over its whole length instead, since the overload
is ADS's own flag and does not go away with the place to draw it. Without ADS there is no notch at
all: the base game charges nothing for a hard-working engine, and a zone we invented would be a
warning about something that is not wrong.

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

Three constraints that apply to **every** new panel, widget and mark. Each has cost a round of rework
already, and none of them is discoverable from the code you happen to be editing — so they live here.

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

### A machine drawn from the side faces left

One driving direction across the whole app: **right to left**, the machine's nose at the left edge of
whatever draws it. It is arbitrary in itself — what is not arbitrary is that two pictures of the same
tractor, on screen at once, must point the same way. The tractor schematic under the Lighting panel's
buttons faces left, so `ClusterIcons` draws its machines facing left, so the ISOBUS machine art does
too; the rig diagram followed the *game's* schema instead, which faces right, and issue #129 is what a
driver saw looking at both at once.

- Art (`mb_trac.png`, `isobus_mixer_wagon.png`) is drawn or sourced already facing left.
- Our own glyphs end up facing left. `ClusterIcons`' shared `TRACTOR` path is authored facing right,
  and both lamps built on it (`WorkFront`, `WorkRear`) are wrapped in its `mirrored` helper — which is
  a flip about the viewport, so a glyph that needs turning round costs one line and no re-tracing.
- A Material icon with a nose on it — `Icons.Filled.Agriculture` is a tractor facing **right** — is
  mirrored with `Modifier.scale(scaleX = -1f, scaleY = 1f)` wherever it is part of a side view. As a
  header or app icon it is a label rather than a picture of a machine, and stays as it is.
- An arrow that names a **position on the machine** points along it, not up and down it: `RigSlot.icon`
  marks the front slot `West` and the rear one `East`. Up and down are left to what really does move
  up and down — the raise/lower control, a sort direction, money in and out.
- `RigSchema` keeps the game's frame in `layoutRig` (forward is +x) and mirrors once at the point of
  drawing, in `drawnLeft` — which also flips the insets and the sign of any rotation. If the game's own
  silhouette atlas is ever adopted for the boxes (see `FUTURE.md`), that art faces right and has to be
  mirrored too.

Not every picture has a driving direction, and those are left alone: the section strip runs across the
boom, the map is heading-up, and the steering glyphs are drawn from above with the front axle at the
top.

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
the standard `function-naming` rule). Rules are tuned in the `.editorconfig` at the **repo**
root, a directory above this one — which is where the IDE's own ktlint reads it from, so there is
only ever one of them.

The code style is `intellij_idea`, and it is pinned twice: in that `.editorconfig` for every ktlint
that reads it, and in `build.gradle.kts` for `app`, whose step carries an `editorConfigOverride` and
therefore formats to Spotless's own preset whatever the file says. That divergence is why the repo
ran on two styles until August 2026 — `server` and `shared` on ktlint's `ktlint_official` default,
`app` on `intellij_idea` — and why both places have to agree.

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
