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

The json is written directly into the farming simulator directory in the user folder, right besides your mod folder.
Windows: `%USERPROFILE%\Documents\My Games\FarmingSimulator2025\vdTelemetry.json`

The shape of the written json is defined by the shared Kotlin model
(`VDTerminal/shared/.../Model.kt`); see `examples/json/` for sample outputs.

## Configuration

The mod has a configuration file named `vdTelemetrySettings.xml` in the modSettings folder. The folder is located
at the same place where your mod folder is.

````xml
<?xml version="1.0" encoding="utf-8" standalone="no"?>
<VDTS version="1">
    <!-- Disable the telemetry export, useful for multiplayer where only one person has GameGlass to reduce load on the client -->
    <exportEnabled>true</exportEnabled>
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