package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **mapLayers** channel the mod writes to `mapLayers.json` (separate file, its
 * own sweep cadence — see the mod's `src/collect/MapLayersExporter.lua`): three grid-sampled ground
 * rasters (crops planted, growth state, soil condition), colored and classified to match the
 * in-game map's own overlay exactly.
 *
 * [gridSize] cells span [terrainSize] meters in both axes, same world origin (terrain center) as
 * [MapData]'s coordinates. Each [MapLayer.rows] entry is a right-trimmed hex string, 2 chars per
 * cell — see [MapLayer.decodeCells].
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults.
 */
@Serializable
data class MapLayersData(
  val version: String = "",
  val terrainSize: Float = 0f,
  val gridSize: Int = 0,
  val layers: List<MapLayer> = emptyList(),
)

@Serializable
data class MapLayer(
  /** "crops", "growth", or "soil" — also the `/api/map-layer/{id}` path segment. */
  val id: String = "",
  /** Values actually seen in [rows] during the sweep that produced this layer, sorted by [MapLayerLegendEntry.v]. */
  val legend: List<MapLayerLegendEntry> = emptyList(),
  /** One right-trimmed hex string per grid row (2 chars/cell); an all-zero row is `""`. */
  val rows: List<String> = emptyList(),
) {
  /**
   * Decode [rows] into a flat `gridSize * gridSize` array of cell values (row-major, 0..255 each).
   * A short or entirely missing row zero-pads; a malformed byte pair decodes as 0 — junk degrades to
   * blank rather than throwing.
   */
  fun decodeCells(gridSize: Int): IntArray {
    val cells = IntArray(gridSize * gridSize)
    for (row in 0 until gridSize) {
      val hex = rows.getOrNull(row) ?: continue
      val cellCount = minOf(gridSize, hex.length / 2)
      for (col in 0 until cellCount) {
        cells[row * gridSize + col] = hex.substring(col * 2, col * 2 + 2).toIntOrNull(16) ?: 0
      }
    }
    return cells
  }
}

@Serializable
data class MapLayerLegendEntry(
  val v: Int = 0,
  val label: String = "",
  /** `#rrggbb`; null when the mod couldn't resolve a color for this value. */
  val color: String? = null,
)

/**
 * Slim broadcast variant of [MapLayersData]: legends only, never [MapLayer.rows] — the raster
 * itself is fetched separately as a PNG (`GET /api/map-layer/{id}?v={version}`), never over the
 * WebSocket, since a 512x512 grid x 3 layers is far too heavy to push on every sweep.
 *
 * [version] is content-derived ([MapLayersData.hashCode] of the **full** data, including [MapLayer.rows]),
 * so a changed cell always changes the version even though the cells themselves never cross the wire —
 * that's what tells the app to refetch the PNG.
 */
@Serializable
data class MapLayersInfo(
  val version: Int = 0,
  val layers: List<MapLayerInfo> = emptyList(),
) {
  companion object {
    fun from(data: MapLayersData): MapLayersInfo =
      MapLayersInfo(
        version = data.hashCode(),
        layers = data.layers.map { MapLayerInfo(it.id, it.legend) },
      )
  }
}

@Serializable
data class MapLayerInfo(
  val id: String = "",
  val legend: List<MapLayerLegendEntry> = emptyList(),
)
