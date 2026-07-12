package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **map** channel the mod writes to `map.json` (separate file, event-driven
 * cadence — see the mod's `src/collect/MapExporter.lua`): the near-static map overlay data (POIs +
 * fields). Rewritten only when farmland ownership or the placeable set changes, never on the
 * telemetry tick.
 *
 * All coordinates ([MapPoi.posX]/[MapPoi.posZ], [MapField.labelX]/[MapField.labelZ],
 * [MapField.polygon]) are normalized `[0,1]` map coordinates in the exact frame of
 * [Player.posX]/[Player.posZ], so the app projects everything with the player-marker math.
 * [terrainSize] converts them back to meters (and anchors the future ground-layer raster grid).
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults, so the mod can add fields ahead of the client.
 */
@Serializable
data class MapData(
  val version: String = "",
  /** World edge length in meters; the world origin sits at the terrain center. */
  val terrainSize: Float = 0f,
  val pois: List<MapPoi> = emptyList(),
  val fields: List<MapField> = emptyList(),
)

@Serializable
data class MapPoi(
  /**
   * The game's `PlaceableHotspot.TYPE` key, camelCased — "unloading", "loading", "shop",
   * "productionPoint", "fuel", animal-pen types, ... Passed through as a string token rather than
   * an enum so an unknown type renders as a neutral marker instead of breaking the parse.
   */
  val type: String = "other",
  val name: String = "",
  val posX: Float = 0f,
  val posZ: Float = 0f,
  /** Omitted (null) when the POI is accessible to everyone / has no owning farm. */
  val ownerFarmId: Int? = null,
)

@Serializable
data class MapField(
  /** The farmland id — FS25 keys fields by farmland, so this doubles as the displayed number. */
  val id: Int = 0,
  /** Display label; the map name when the map defines one, otherwise the id as a string. */
  val name: String = "",
  /** Same value as [id]; kept explicit for joining against farmland-keyed data. */
  val farmlandId: Int? = null,
  /** Omitted (null) when the field is unowned. */
  val ownerFarmId: Int? = null,
  val areaHa: Float = 0f,
  /** Anchor of the field-number label (the game's own indicator node, or the polygon center). */
  val labelX: Float = 0f,
  val labelZ: Float = 0f,
  /** Flat border outline `[x1, z1, x2, z2, ...]`; empty when the map defines no usable polygon. */
  val polygon: List<Float> = emptyList(),
)
