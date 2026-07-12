package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **mapVehicles** channel the mod writes to `mapVehicles.json` (separate file,
 * its own ~1 s interval — neither the 100 ms telemetry tick nor the event-driven `map.json`
 * cadence; see the mod's `src/collect/MapVehiclesExporter.lua`): one marker per vehicle rig the
 * game's own map would show.
 *
 * [MapVehicle.posX]/[MapVehicle.posZ] are normalized `[0,1]` map coordinates in the exact frame of
 * [Player.posX]/[Player.posZ] and [MapData]'s coordinates.
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults.
 */
@Serializable
data class MapVehiclesData(
  val version: String = "",
  val vehicles: List<MapVehicle> = emptyList(),
)

@Serializable
data class MapVehicle(
  /**
   * The game's `VehicleHotspot.TYPE` key, camelCased — "tractor", "truck", "harvester", "trailer",
   * "toolTrailed", ... String token rather than an enum so an unknown type renders as a neutral
   * marker instead of breaking the parse.
   */
  val type: String = "other",
  val name: String = "",
  val posX: Float = 0f,
  val posZ: Float = 0f,
  /** Compass degrees, same convention as `Gps.heading` / [Player.heading]. */
  val heading: Int = 0,
  /** Owning farm — join against [MapFarm] for the marker color; null when unowned. */
  val farmId: Int? = null,
  /** An AI helper is driving. */
  val isAI: Boolean = false,
  /** A human is driving — in multiplayer also other players' vehicles. */
  val isControlled: Boolean = false,
  /** The LOCAL player is inside; the map hides this one (the player marker already shows it). */
  val isEntered: Boolean = false,
)
