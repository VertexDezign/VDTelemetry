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
  /** Every other player in the session (version 2, issue #176) — never the local one. */
  val players: List<MapPlayer> = emptyList(),
)

@Serializable
data class MapVehicle(
  /**
   * Network object id, what [MapPlayer.vehicleId] names (version 2). Stable for a session, not
   * across saves — the same id the fleet channel keys on. Null from a version 1 mod.
   */
  val id: Int? = null,
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

/**
 * Another player in the session, wherever they are: on foot, driving, or in a passenger seat — the
 * set the game's own map marks with a player hotspot. The local player is never in the list: that
 * marker is [Player], at the telemetry's cadence.
 *
 * In a vehicle, the position and [heading] are the vehicle's, as the game's marker follows the
 * vehicle too, and [vehicleId] names the [MapVehicle] already drawn there.
 */
@Serializable
data class MapPlayer(
  /** The player's nickname. */
  val name: String = "",
  val posX: Float = 0f,
  val posZ: Float = 0f,
  /** Compass degrees, same convention as [MapVehicle.heading]. */
  val heading: Int = 0,
  /** The player's farm — join against [MapFarm] like [MapVehicle.farmId]; null on the spectator farm. */
  val farmId: Int? = null,
  /** The [MapVehicle.id] of the rig they are driving or riding in; null on foot. */
  val vehicleId: Int? = null,
) {
  val isOnFoot: Boolean get() = vehicleId == null
}
