# VDTelemetry

VDTelemetry exports the current game state into json files so external telemetry consumers can read it, and provides
some additional action events for accessing more stuff with direct key bindings. Consumers can also write
back through a command channel (drive the vehicle's lights/engine/cruise, edit the supported mods' data).

> This mod was originally built as *GameGlassInterface* to provide integration with
> [GameGlass](https://gameglass.gg/), which remains the primary intended consumer.

**Integration into GameGlass still pending**

Link to Discord Post: [GameGlass Discord](https://discord.com/channels/522506741213167617/1308554695958204588)

## Requirements

* [FS25_additionalInputs](https://github.com/VertexDezign/AdditionalInputs)

## Output

The telemetry json is written into the mod's own settings folder, in a `telemetry/` subfolder
(`modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json`); the per-mod channels below land beside it.
It lives there — rather than the user directory root — because the engine only lets a mod delete files
inside its own `modSettings/<modName>/` folder, and disabling export removes the files.

Windows: `%USERPROFILE%\Documents\My Games\FarmingSimulator2025\modSettings\FS25_vdTelemetry\telemetry\vdTelemetry.json`

The shape of the written json is defined by the shared Kotlin model
(`VDTerminal/shared/.../Model.kt`); see `examples/json/` for sample outputs.

### Event-driven channels

`vdTelemetry.json` is only the first of several **export channels** written into `telemetry/`, each on
its own cadence. The vehicle telemetry is rewritten every interval; the event-driven channels change
rarely, so they are written only when their data actually changes — they never ride the 100 ms tick.

| File | Source | Written |
|---|---|---|
| `vdTelemetry.json` | vehicle + environment (core) | every interval |
| `map.json` | map overlay: POIs + fields + farms (core, `src/collect/MapExporter.lua`) | on farmland/placeable/farm change |
| `mapVehicles.json` | vehicle markers (core, `src/collect/MapVehiclesExporter.lua`) | own interval (1 s) |
| `taskList.json` | [FS25_TaskList](https://www.farming-simulator.com/mod.php?mod_id=312938&title=fs2025) | on task/group change |
| `cropRotation.json` | [FS25_CropRotation](https://www.farming-simulator.com/mod.php?mod_id=347316&title=fs2025) | on planner change |

Each channel file carries its **own `version`**, evolving independently of the telemetry one.

For the per-mod channels, **the file's absence means "that mod isn't installed"** — that is exactly how
VDTerminal decides whether to show the panel at all. So the mod deletes, once at startup, the file of
every channel that this session will never write: uninstall one of the mods and its json goes away with
it, instead of leaving the terminal showing last session's data. (With export disabled nothing is
written at all, so all of them go.) `map.json` and `mapVehicles.json` read base-game data and are
always written; their absence just means "no data yet", and VDTerminal drops the affected map
overlays until they reappear.

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

### Linux: keep telemetry writes off the SSD (optional)

The json is rewritten every interval (100 ms by default), so on Linux you can back the `telemetry/`
subfolder with RAM (tmpfs) to avoid the constant SSD writes. Only the `telemetry/` folder needs this;
the settings XML lives one level up and stays on disk, and the mod recreates the (empty) folder on
every map load, so nothing needs to persist there.

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
16M is far more than enough; the json is a few KB.

## Configuration

Export can be toggled and the write interval chosen directly in-game: **General Settings**.
Both apply immediately and are saved back to the configuration file — disabling export also removes
every channel file (`vdTelemetry.json` and any per-mod one) so consumers can tell it stopped, and
re-enabling repopulates them at once rather than waiting for the next change.

The mod keeps its files under `modSettings/FS25_vdTelemetry/` (next to your `mods` folder): the
configuration file `vdTelemetrySettings.xml` at its root, the telemetry json under `telemetry/`, and
the command channel under `commands/`.

`commands/commands.xml` is the back-channel: VDTerminal writes commands into it (toggle lights, start
the engine, set cruise speed, …) and the mod polls it. It is XML rather than json because the mod can
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
         (General Settings); VDTerminal writes "custom" when you fine-tune a single channel. -->
    <profile>high</profile>
    <!-- Per-channel config for the secondary export channels (the live vehicle telemetry above is always on).
         `enabled` turns a channel off entirely if you don't use that base-game feature — no file is written and any
         existing one is deleted. `intervalMs` (interval-driven channels only) is the channel's cadence under the
         "custom" profile, clamped to a 100 ms floor. Applied at load; edit here (or from VDTerminal) and restart. -->
    <channels>
        <channel id="map" enabled="true"/>
        <channel id="mapVehicles" enabled="true" intervalMs="1000"/>
        <channel id="production" enabled="true" intervalMs="2000"/>
        <channel id="storage" enabled="true" intervalMs="2000"/>
        <channel id="husbandry" enabled="true" intervalMs="5000"/>
        <channel id="taskList" enabled="true"/>
        <channel id="cropRotation" enabled="true"/>
        <channel id="fieldInfo" enabled="true" intervalMs="30000"/>
    </channels>
</VDTS>

````

### Supported Mods

All three are **optional** — VDTelemetry detects each at runtime and simply omits its data when it
isn't installed. Because they are read through their *internals*, each is pinned to the version it was
developed against (see the header comment of the file named below) and fails soft: a field a future mod
version renames costs you that panel, never a Lua error.

* [EnhancedVehicle](https://github.com/ZhooL/FS25_EnhancedVehicle) — extra fields on the vehicle
  telemetry (`src/integrations/EnhancedVehicle.lua`)
    * Differential
    * AWD
    * Parking Brake
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

## Tests

The pure-Lua utility code (e.g. the JSON encoder in `src/utils/Json.lua`) is unit-tested with
[busted](https://lunarmodules.github.io/busted/). Tests live in `spec/` (excluded from the packaged
mod via `.fsignore`) and run in CI on Lua 5.1 — the version the GIANTS engine uses.

Run them locally from this directory:

```bash
luarocks install busted   # once
busted                    # discovers and runs spec/*_spec.lua
```

## Formatting

Lua is formatted with [StyLua](https://github.com/JohnnyMorganz/StyLua) (config in `stylua.toml`).
CI checks it; run it locally from this directory to apply:

```bash
stylua .            # format in place
stylua --check .    # verify only (what CI runs)
```
