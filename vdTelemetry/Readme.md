# VDTelemetry

VDTelemetry exports the current game state into json files so external telemetry consumers can read it, and provides
some additional action events for accessing more stuff with direct key bindings. Consumers can also write
back through a command channel — the vehicle in the player's hands (lights, engine, cruise, implements, the steering
assist, Precision Farming's application rate) and the farm around it (contracts, production lines, object-storage
unload, the loan, and the supported mods' own data).

> This mod was originally built as *GameGlassInterface* to provide integration with
> [GameGlass](https://gameglass.gg/), which remains the primary intended consumer.

**Integration into GameGlass still pending**

Link to Discord Post: [GameGlass Discord](https://discord.com/channels/522506741213167617/1308554695958204588)

**Installing it as a player?** See [docs/setup.en.md](../docs/setup.en.md) —
[auf Deutsch](../docs/setup.de.md). This file is the mod's reference documentation.

## Requirements

* [FS25_additionalInputs](https://github.com/VertexDezign/AdditionalInputs) — major version 1, minor 1 or newer.
  Without it the mod logs an error and disables the export (there is no in-game warning yet).

## Output

The telemetry json is written into the mod's own settings folder, in a `telemetry/` subfolder
(`modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json`); the per-mod channels below land beside it.
It lives there — rather than the user directory root — because the engine only lets a mod delete files
inside its own `modSettings/<modName>/` folder, and disabling export removes the files.

Windows: `%USERPROFILE%\Documents\My Games\FarmingSimulator2025\modSettings\FS25_vdTelemetry\telemetry\vdTelemetry.json`

The shape of the written json is defined by the shared Kotlin model
(`VDTerminal/shared/.../model/`); see `examples/json/` for sample outputs.

### The other export channels

`vdTelemetry.json` is only the first of several **export channels** written into `telemetry/`, each on
its own cadence. The vehicle telemetry is rewritten every interval; the rest are either interval-driven
at a cadence that suits how fast their data really moves, or event-driven — written only when their
data actually changes — and none of them rides the 100 ms tick.

| File | Source | Written |
|---|---|---|
| `vdTelemetry.json` | vehicle + environment (core) | every interval |
| `map.json` | map overlay: POIs + fields + farms (core, `src/collect/MapExporter.lua`) | on farmland/placeable/farm change |
| `mapVehicles.json` | vehicle markers (core, `src/collect/MapVehiclesExporter.lua`) | own interval (1 s) |
| `mapLayers/` | ground layers: `index.json` (catalogue) + one raster file per plane — crops/growth/soil (core, `src/collect/MapLayersExporter.lua`) | own sweep cadence, per plane |
| `gpsCourse.json` | the steering assist's guidance lines for the field being driven (core, `src/collect/GpsCourseExporter.lua`) | when the course itself changes (polled) |
| `production.json` | own farm's production points + factories (core, `src/collect/ProductionExporter.lua`) | own interval (2 s) |
| `storage.json` | own farm's holding outside its productions: standalone silos + object storages, plus silage bunkers and the loose bales/pallets lying around (core, `src/collect/StorageExporter.lua`) | own interval (2 s) |
| `husbandry.json` | own farm's animal pens (core, `src/collect/HusbandryExporter.lua`) | own interval (5 s) |
| `fleet.json` | own farm's machines: condition, hours, value, and Advanced Damage System's maintenance record (core, `src/collect/FleetExporter.lua`) | own interval (5 s) |
| `missions.json` | the farm's contracts (core, `src/collect/MissionExporter.lua`) | on contract change + 10 s |
| `finance.json` | the farm's books: balance, loan, the monthly table, the money log (core, `src/collect/FinanceExporter.lua`) | on period/loan change + 5 s |
| `fieldInfo.json` | per-field agronomy, for the field-info popup (core, `src/collect/FieldInfoExporter.lua`) | own interval (30 s) |
| `cropCalendar.json` | which periods each crop may be sown and harvested in (core, `src/collect/CropCalendarExporter.lua`) | on day / season-length change, + a 2 s growth-mode watch |
| `weather.json` | the forecast: now, twelve two-hourly steps, six days (core, `src/collect/WeatherExporter.lua`) | on hour / day / weather change |
| `taskList.json` | [FS25_TaskList](https://www.farming-simulator.com/mod.php?mod_id=312938&title=fs2025) | on task/group change |
| `cropRotation.json` | [FS25_CropRotation](https://www.farming-simulator.com/mod.php?mod_id=347316&title=fs2025) | on planner change |
| `invoices.json` | [FS25_Invoices](https://github.com/Squallqt/FS25_Invoices) | on invoice or player-farm change |

Each channel file carries its **own `version`**, evolving independently of the telemetry one. The
farm-scoped ones (everything that says "own farm", plus contracts and invoices) are rewritten when the
player switches farm as well, since that changes who is asking rather than what is stored.

For the per-mod channels, **the file's absence means "that mod isn't installed"** — that is how
VDTerminal decides whether to show the panel at all. So the mod deletes, once at startup, the file of
every channel that this session will never write: uninstall one of the mods and its json goes away with
it, instead of leaving the terminal showing last session's data. The core channels read base-game data,
so their absence normally means "no data yet" and VDTerminal drops the affected view until they
reappear. Three things delete a core channel's file outright, and they are what to check when one never
appears at all: **export switched off** (nothing is written, so every channel goes), the channel's own
**`enabled="false"`**, and a **performance profile below the channel's minimum** — `low` switches
`mapLayers` off that way.

`map.json` carries the near-static map data: selling/loading stations, shops, productions and other
placeable POIs (typed via the game's own hotspot enum), every field's number, ownership, area and
border polygon, and the farms with their in-game map color (`Farm:getColor()`, converted to sRGB
`#rrggbb`) so the terminal tints ownership exactly like the game's own map. All coordinates are
normalized `[0,1]` map coordinates in the same frame as the player marker; `terrainSize` converts them
back to meters. Border polygons are thinned (5 m minimum spacing, capped at 256 points per field) to
keep the file small.

`mapVehicles.json` carries one marker per vehicle rig the game's own map would show (root vehicles
with `mapHotspotAvailable`, typed via `VehicleHotspot.TYPE`): position/heading in the same normalized
frame, owning farm, and AI/controlled/entered flags. It rewrites on its own 1 s interval — positions
change constantly, but a map overview needs neither the 100 ms telemetry cadence nor event-driven
writes.

The `mapLayers/` folder carries grid-sampled ground rasters — what's planted (crops), growth state,
and soil condition — at the in-game map overlay's own 512² resolution, classified and colored to match
`MapOverlayGenerator` exactly (fruit colors, growth-state gradient, weed/stone/fertilizer/plow/lime
legends). Sampling one world position per grid cell is expensive, so a sweep is spread over many
frames (a few thousand cells per tick) rather than done in one pass, then paused for a while before the
next sweep. Each layer is a one-byte-per-cell plane, encoded as right-trimmed hex row strings, with a
legend of only the values actually seen on this map.

**One file per plane** (`mapLayers/crops.json`, `growth.json`, `soil.json`), plus `index.json` naming
the planes this map offers. A field operation only touches some of the planes — fertilizing moves soil
alone, cultivating leaves crops alone — so writing them separately means a between-sweep patch
re-serializes only what actually changed, instead of the whole megabyte every time. `index.json` is
written without sampling anything, so VDTerminal can offer a layer before its raster has been swept.

**The game's colorblind mode is respected.** Both the base game and Precision Farming ship a second
palette for it, and the overlay uses whichever one the player has selected — so it keeps matching the
in-game map. Toggling the setting re-sweeps immediately rather than waiting for the next in-game day,
because in that mode the growth gradient has fewer steps, so the raster changes and not just its colors.

**With Precision Farming installed, its own value maps become planes too** — soil type, pH, nitrogen,
yield and seed rate, the five PF shows in its own map selector, each labelled and colored exactly as PF
does. They are ordinary planes from there on: one file each, their own legend, and the same
subscription gate, so they cost nothing until you select one. Without PF they aren't offered at all.
(The base-game fertilizer + lime soil layers stay suppressed under PF, as before — PF supersedes them.)

**Only what someone is looking at is sampled.** VDTerminal tells the mod which planes its dashboards
are currently showing, and the sweep classifies, encodes and writes those alone — a dashboard shows one
overlay at a time, so the planes nobody selected would otherwise be most of the channel's cost. With no
terminal running, or nobody on the map page, the channel does nothing at all: no sampling, no writes.
Selecting a layer starts a sweep for it right away, and a layer you switch away from keeps its last
file, so switching back shows that raster immediately while the fresh one is swept.

A resweep is triggered by in-game events (growth advancing, the day rolling over) rather than a
wall-clock timer, and in between, the ground around working vehicles is patched in place — so an idle
map costs nothing. **In multiplayer that isn't enough on its own:** the server streams the field density
maps to each client in bandwidth-limited batches, near-to-far, so for the first minutes after joining
much of the map simply hasn't arrived yet and the first sweep reads it as empty. The game exposes no
"sync finished" signal, so the mod checks instead — every 10 s of idle time it re-samples a small
scattered sample of the grid, and if any of it disagrees with what was exported, it schedules a full
resweep. Once the map has finished syncing this finds nothing and costs nothing. It also means another
player's work on the far side of the map eventually shows up. Singleplayer skips the check entirely (it
reads the real maps directly, so there is nothing to catch up with).

It is by far the mod's most expensive channel, so it is the one channel tied to the performance profile:
**under the `low` preset it is switched off entirely** — no sampling, no files — and the `mapLayers/`
files are deleted so VDTerminal drops the overlays. It runs from `medium` upwards, and under `custom` your own
`enabled` toggle decides.

### Keeping telemetry writes off the SSD (optional)

**How much is actually being written.** The tick json is 3–5 KB (see `examples/json/`), so the default
100 ms interval is ~140 MB an hour — roughly 0.2 TB a year at four hours a day, which is a fraction of
a percent of a modern SSD's rated endurance. The `mapLayers/` rasters are far larger (~1.5 MB for a
512² grid) but are event-driven rather than per-tick. So this section is a tidiness measure, not a
drive-saving one, and **the cheaper lever is the one already in the game**: raise the write interval,
or drop the performance profile to `low`, which switches the `mapLayers` channel off entirely and
removes the only big writer.

What makes redirecting the folder safe either way: the mod only ever `createFolder`s `telemetry/` (and
its subdirs) and `deleteFile`s individual files — it never removes the folder itself. Nothing there
needs to persist across a reboot, and only `telemetry/` wants redirecting; the settings XML lives one
level up and stays on disk.

#### Linux (tmpfs)

Mount tmpfs onto the folder via `/etc/fstab`. **The path contains a space (`My Games`), which must be
escaped as `\040`** — fstab uses whitespace as its field separator, and quotes/backslash-space do not
work. On Steam Proton the path is under `steamapps/compatdata/2300320/pfx/`; find it with:

```bash
find ~ -type d -path '*modSettings/FS25_vdTelemetry/telemetry' 2>/dev/null
```

Generate the escaped fstab line (avoids typos in the long path):

```bash
TDIR=$(find ~ -type d -path '*modSettings/FS25_vdTelemetry/telemetry' 2>/dev/null | head -1)
printf 'tmpfs  %s  tmpfs  rw,size=16M,uid=%s,gid=%s,mode=0755,noatime  0  0\n' \
  "${TDIR// /\\040}" "$(id -u)" "$(id -g)"
```

Add that line to `/etc/fstab`, then `sudo mount -a` (no error = valid fstab). Verify with
`findmnt --target "<real path with a normal space>"` — it should show `tmpfs` as the source.
16M leaves plenty of room: the telemetry json is a few KB, and the largest channel by far — the
`mapLayers/` rasters, together roughly 1.5 MB for a 512² grid — are rewritten in place, not accumulated.

#### Windows (RAM disk + junction)

Windows has no tmpfs, and no built-in RAM disk at all, so this takes two pieces: third-party RAM-disk
software (ImDisk Toolkit, OSFMount and SoftPerfect RAM Disk are the usual free ones), and a **directory
junction** redirecting the mod's fixed path onto it. The mod writes to
`modSettings\FS25_vdTelemetry\telemetry\` and cannot be told otherwise — `VDT_FILE` only moves where
*VDTerminal reads*, so it is no substitute.

With a RAM disk at `R:` and FS25 closed, in a normal (non-admin) prompt:

```bat
set TEL=%USERPROFILE%\Documents\My Games\FarmingSimulator2025\modSettings\FS25_vdTelemetry\telemetry
mkdir R:\vdtelemetry
rmdir /s /q "%TEL%"
mklink /J "%TEL%" R:\vdtelemetry
```

`mklink /J` makes a junction rather than a symlink deliberately: junctions to a local directory need no
administrator rights and no Developer Mode, where `mklink /D` needs one or the other. The existing
folder has to go first — `mklink` refuses to write over a directory that is already there. Adjust the
path if your Documents folder is redirected into OneDrive.

Two things to get right, or it fails quietly:

- **The RAM disk and `R:\vdtelemetry` must both exist before the game starts.** A junction whose target
  is missing is broken, not empty, and the mod's `createFolder` will not repair it. Most RAM-disk tools
  can restore a folder structure at boot; use that rather than trusting yourself to do it by hand.
- **Size it like the tmpfs above** — 32 MB is generous.

**Untested.** Nobody has run this: the structure is sound (the mod never deletes the folder, so the
junction survives every map load), but whether VDTerminal's file watcher — Java's `WatchService`, i.e.
`ReadDirectoryChangesW` — reports changes through a junction has not been confirmed on real hardware.
It should, since the handle resolves to the target directory, but treat it as a thing to verify rather
than a thing that works. If it doesn't, the symptom is a dashboard that connects and never updates.

## Configuration

Export can be toggled, the write interval chosen and the performance profile picked directly in-game:
**General Settings**. All three apply immediately and are saved back to the configuration file —
disabling export also removes every channel file (`vdTelemetry.json` and any per-mod one) so consumers
can tell it stopped, and re-enabling repopulates them at once rather than waiting for the next change.
Per-channel tuning has no in-game UI; it lives in the XML below.

The mod keeps its files under `modSettings/FS25_vdTelemetry/` (next to your `mods` folder): the
configuration file `vdTelemetrySettings.xml` at its root, the telemetry json under `telemetry/`, and
the command channel under `commands/`.

`commands/commands.xml` is the back-channel: VDTerminal writes commands into it (toggle lights, start
the engine, set cruise speed, accept a contract, pay an invoice, …) and the mod polls it — one command
type per file in `src/command/`. It is XML rather than json because the mod can
only *write* files via `io` — its sole file reader is the engine's `XMLFile.load`. The mod deletes any
leftover `commands.xml` on load, so stale commands never fire at session start.

````xml
<?xml version="1.0" encoding="utf-8" standalone="no"?>
<VDTS version="3">
    <export>
        <!-- Disable the telemetry export, useful for multiplayer where only one person has GameGlass to reduce load on the client -->
        <enabled>true</enabled>
        <!-- Milliseconds between telemetry samples (clamped to a sub-frame floor). The in-game selector offers 100/250/500/1000. -->
        <intervalMs>100</intervalMs>
    </export>
    <logging>
        <!-- Configure log levels for debugging purposes -->
        <level>INFO</level>
        <specLevel>INFO</specLevel>
    </logging>
    <json>
        <!-- Pretty-print the json output (indented + sorted keys) for easier live inspection during development -->
        <pretty>false</pretty>
    </json>
    <!-- Performance profile for the secondary channels below: low | medium | high | veryHigh | custom.
         A preset scales every interval-driven channel's cadence (low = 4x slower … veryHigh = 2x faster than the
         defaults shown below); "custom" instead uses the per-channel intervalMs values. Switch presets in-game
         (General Settings), or set "custom" here to hand the cadence to the intervalMs values below.
         A preset can also switch a channel off outright when it is too expensive for that tier: "low" disables
         the mapLayers channel (its file is deleted, like any disabled channel). Your own per-channel `enabled`
         toggles are kept as you set them, so raising the profile again brings the channel back. -->
    <profile>high</profile>
    <!-- Per-channel config for the secondary export channels (the live vehicle telemetry above is always on).
         `enabled` turns a channel off entirely if you don't use that base-game feature — no file is written and any
         existing one is deleted. `intervalMs` (interval-driven channels only) is the channel's cadence under the
         "custom" profile, clamped to a 100 ms floor. Read at load and XML-only — VDTerminal has no way to change it,
         and the mod rewrites this file on any in-game settings change — so edit here with the game closed. -->
    <channels>
        <channel id="map" enabled="true"/>
        <channel id="mapVehicles" enabled="true" intervalMs="1000"/>
        <channel id="mapLayers" enabled="true"/>
        <channel id="gpsCourse" enabled="true"/>
        <channel id="production" enabled="true" intervalMs="2000"/>
        <channel id="storage" enabled="true" intervalMs="2000"/>
        <channel id="husbandry" enabled="true" intervalMs="5000"/>
        <channel id="fleet" enabled="true" intervalMs="5000"/>
        <channel id="missions" enabled="true" intervalMs="10000"/>
        <channel id="finance" enabled="true" intervalMs="5000"/>
        <channel id="taskList" enabled="true"/>
        <channel id="cropRotation" enabled="true"/>
        <channel id="invoices" enabled="true"/>
        <channel id="fieldInfo" enabled="true" intervalMs="30000"/>
        <channel id="cropCalendar" enabled="true"/>
        <channel id="weather" enabled="true"/>
    </channels>
</VDTS>

````

### Supported Mods

These are all **optional** — VDTelemetry detects each at runtime and simply omits its data when it
isn't installed. Because they are read through their *internals*, each is pinned to the version it was
developed against (see the header comment of the file named below) and fails soft: a field a future mod
version renames costs you that panel, never a Lua error.

* **Precision Farming** — the game's own internal mod (`FS25_precisionFarming`), detected the same way
  the game detects it (`src/integrations/PrecisionFarming.lua`). It changes three things at once:
    * **Application rates on the tool** — nitrogen and pH against their targets, what is actually
      leaving the machine per hectare in PF's own units, the manual step and the per-nozzle spray
      states, all on `vehicle.precisionFarming`. **Read and write:** VDTerminal can switch auto/manual
      and change the manual step (`src/command/PrecisionFarmingControl.lua`)
    * **Its five menu-visible value maps become ground-layer planes** — soil type, pH, nitrogen, yield
      and seed rate, labelled and coloured as PF does
    * **The base-game data it supersedes is dropped** where the game drops it: no fertilized /
      needs-lime soil layers, and no yield-bonus / fertilized / needs-lime rows in the field-info popup
* [EnhancedVehicle](https://github.com/ZhooL/FS25_EnhancedVehicle) — extra fields on the vehicle
  telemetry (`src/integrations/EnhancedVehicle.lua`)
    * Differential
    * AWD
    * Parking Brake
* [FS25_AdvancedDamageSystem](https://github.com/id577/FS25_AdvancedDamageSystem) **0.9.2.7-beta** —
  replaces the vanilla damage model, and drives the cluster's warning lamps
  (`src/integrations/AdvancedDamageSystem.lua`, `vehicle.ads`). Still a beta, so its internals move
  faster than the others': the version above is the one this was written against. **Read only:** every
  workshop procedure stays in game, as with vanilla repair.
    * The six dashboard lamps ADS drives, each with its severity — and only the lamps a machine of
      that production year actually has
    * The engine temperature, which **replaces** `motor.temperatur.value`. ADS's thermal model is the
      real one under ADS, and the vanilla figure it stands in for is never synced to a multiplayer
      client at all (`motorTemperature.valueSend` is dead code in the base game)
    * A CVT's own transmission temperature, which ADS models separately
    * The load ADS wears the engine on — the plain engine load plus the draft term it adds while an
      implement is down and working, which is what its own dashboard prints and can read past 100%.
      `motor.load` stays exported beside it, unchanged: the plain engine load is still true, and this
      is a second number rather than a correction of it
    * The service interval — hours since the last maintenance, and the hours this machine's
      manufacturer recommends between them
    * The system voltage the machine's electrics see
    * What the last workshop inspection found. **Not** the live condition/stress/service values: ADS
      hides those behind an inspection on purpose, so the terminal never knows more than the driver.
      The pre-shift chores (radiator, air intake, lubrication) are left out for the same reason, even
      though ADS reports them in coarse bands — you learn them by getting out and walking round the
      machine, and a dashboard that printed them would hand you that walk
* [FS25_TaskList](https://www.farming-simulator.com/mod.php?mod_id=312938&title=fs2025) `1.2.0.1`
  ([source](https://github.com/Ozz-Modding/FS25_TaskList)) — the farm task list, in its own
  `taskList.json` channel (`src/integrations/TaskList.lua`). **Read and write:** VDTerminal can
  complete, delete, create and edit tasks, which it does by driving the mod's own multiplayer-correct
  wrappers (`src/command/TaskListControl.lua`) — VDTelemetry stores nothing of its own.
* [FS25_CropRotation](https://www.farming-simulator.com/mod.php?mod_id=347316&title=fs2025) `1.0.1.0` —
  the crop-rotation **planner** (the saved rotation plans, not the field history map), in its own
  `cropRotation.json` channel (`src/integrations/CropRotation.lua`), including the per-slot yield-bonus
  % the game shows. **Read and write:** VDTerminal can edit a plan's crops and catch crops, add/remove
  slots, and create/delete plans, again through the mod's own event wrappers
  (`src/command/CropRotationControl.lua`).
* [FS25_EnhancedLoanSystem](https://www.farming-simulator.com/mod.php?mod_id=314906&title=fs2025)
  `1.0.0.0` — **replaces** the base-game loan with annuity loans, so its presence suppresses the
  base-game loan block on the finance channel and adds an `enhancedLoans` block in its place
  (`src/integrations/EnhancedLoanSystem.lua`). **Read and write:** VDTerminal can take a loan and make
  a special redemption payment against one (`src/command/EnhancedLoanControl.lua`).
* [FS25_Invoices](https://github.com/Squallqt/FS25_Invoices) `1.2.0.0` —
  invoices raised between farms, in its own `invoices.json` channel
  (`src/integrations/Invoices.lua`), together with the work-type catalogue priced for this server and
  the farms that can be billed. **Read and write:** VDTerminal can pay, withdraw, validate and refuse
  invoices, and raise new ones from work-type lines, all through the mod's own server-authoritative
  events (`src/command/InvoiceControl.lua`). Note this mod is **multiplayer-only in practice** — an
  invoice needs two farms, and singleplayer has one.

## Tests

The pure-Lua utility code (e.g. the JSON encoder in `src/utils/Json.lua`) is unit-tested with
[busted](https://lunarmodules.github.io/busted/). Tests live in `spec/` (excluded from the packaged
mod via `.fsignore`) and run in CI on Lua 5.1 — the version the GIANTS engine uses.

Run them locally from this directory:

```bash
luarocks install busted   # once
busted                    # discovers and runs spec/*_spec.lua
```

## Packing

The mod is packed with [FSTools](https://github.com/VertexDezign/FSTools), from this directory:

```bash
fs pack             # -> FS25_vdTelemetry.zip here
fs pack -o build    # somewhere else
fs pack -d          # and deploy it into the FS25 mods folder
fs validate         # sanity-check modDesc.xml on its own
```

The release workflow runs the same `fs pack`, pinned to a release tag, so there is only ever one answer to what belongs
in the zip. Two files decide that:

- **`.fsignore`** — what does *not* ship, gitignore-style. `fs pack` already drops `*.md`, `.idea` and similar by
  default; the entries here are the ones it cannot guess (`spec/`, `fsTypes/`, `stylua.toml`). Anything new and
  repo-only belongs here, or it goes out to players.
- **`fstools.toml`** — `author` and `title` are rewritten **into the packed zip's `modDesc.xml`**, leaving the file on
  disk alone. `version` is deliberately *not* set there, because the release does not read it: the workflow derives the
  mod's four-number version from the git tag and passes it to `fs pack --mod-version`, which rewrites it the same way.
  `modDesc.xml` on disk carries `0.0.0.0`, a placeholder that exists only because `fs validate` demands the element and
  the mod runs unpacked in development — cutting a release never edits it. Set `version` in `fstools.toml` locally if
  you want a hand-packed dev build marked as one.

## Formatting

Lua is formatted with [StyLua](https://github.com/JohnnyMorganz/StyLua) (config in `stylua.toml`).
CI checks it; run it locally from this directory to apply:

```bash
stylua .            # format in place
stylua --check .    # verify only (what CI runs)
```
