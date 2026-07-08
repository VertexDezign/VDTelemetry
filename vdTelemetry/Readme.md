# VDTelemetry

VDTelemetry exports the current game state into a json file so external telemetry consumers can read it, and provides
some additional action events for accessing more stuff with direct key bindings.

> This mod was originally built as *GameGlassInterface* to provide integration with
> [GameGlass](https://gameglass.gg/), which remains the primary intended consumer.

**Integration into GameGlass still pending**

Link to Discord Post: [GameGlass Discord](https://discord.com/channels/522506741213167617/1308554695958204588)

## Requirements

* [FS25_additionalInputs](https://github.com/VertexDezign/AdditionalInputs)

## Output

The json is written into the mod's own settings folder, in a `telemetry/` subfolder
(`modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json`). It lives there — rather than the user
directory root — because the engine only lets a mod delete files inside its own
`modSettings/<modName>/` folder, and disabling export removes the file.

Windows: `%USERPROFILE%\Documents\My Games\FarmingSimulator2025\modSettings\FS25_vdTelemetry\telemetry\vdTelemetry.json`

The shape of the written json is defined by the shared Kotlin model
(`VDTerminal/shared/.../Model.kt`); see `examples/json/` for sample outputs.

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
Both apply immediately and are saved back to the configuration file — disabling export also
removes the stale `vdTelemetry.json` so consumers can tell it stopped.

The mod keeps its files under `modSettings/FS25_vdTelemetry/` (next to your `mods` folder): the
configuration file `vdTelemetrySettings.xml` at its root, and the telemetry json under `telemetry/`.

````xml
<?xml version="1.0" encoding="utf-8" standalone="no"?>
<VDTS version="2">
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
</VDTS>

````

### Supported Mods

* [EnhancedVehicle](https://github.com/ZhooL/FS25_EnhancedVehicle)
    * Differential
    * AWD
    * Parking Brake

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
