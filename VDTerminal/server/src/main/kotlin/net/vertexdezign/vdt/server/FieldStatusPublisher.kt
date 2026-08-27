package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.FieldIndexGrid
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapLayerData

/**
 * The plane the per-field breakdown is counted off.
 *
 * Growth, because that is the one that answers "what does this field need next": which part of it is
 * ready, cut, growing, ploughed or withered. The crops plane would add "which crop, where" for a mixed
 * field — a second subscription, a second histogram, and fruit-type indices the app would have to
 * resolve — for a question `fieldInfo.crop` already answers coarsely. The mechanism generalises to it
 * for free, so this is a one-line change when it is wanted.
 */
const val FIELD_STATUS_LAYER_ID = "growth"

/**
 * Keeps the per-field status histogram up to date without recomputing it for nothing.
 *
 * Two caches, because the two inputs move at completely different rates. The field index grid depends
 * only on the map, which changes when a farmland is bought or a save is loaded — rarely; the histogram
 * depends on it and on one plane's raster, which changes every sweep. Without the split, buying a
 * field and harvesting one would cost the same work.
 *
 * The raster side is keyed on [MapLayerData.contentVersion] rather than on the raster object, because
 * the flow this is driven from carries **every** plane: a soil sweep re-emits the whole keyed map, and
 * the growth histogram must not be rebuilt because some other plane moved. The version is memoized on
 * the parsed instance, so the check is a string compare.
 *
 * Returning the *same* [FieldStatusData] instance when nothing changed is load-bearing, not a
 * micro-optimisation: it is handed to a `MutableStateFlow`, which drops an equal value, so an
 * unchanged histogram broadcasts nothing at all.
 *
 * Thread-safe — it is updated from a collector and could be read from anywhere.
 */
class FieldStatusPublisher(private val layerId: String = FIELD_STATUS_LAYER_ID) {
  private var gridMap: MapData? = null
  private var gridSize = 0
  private var grid: FieldIndexGrid? = null

  private var statusVersion: String? = null
  private var status: FieldStatusData? = null

  /**
   * The breakdown for the current map and raster, or null when there is nothing to derive one from.
   *
   * Null is "no raster yet" — the map hasn't loaded, the layer channel is off, or nobody has
   * subscribed to the plane so the mod has never swept it. It is never "the mod isn't installed":
   * this is derived here, not exported. The app says "sampling…" for it rather than drawing a zeroed
   * breakdown, which matters most right after the field app opens — a subscription starts a full
   * sweep, and a full sweep is seconds.
   */
  @Synchronized
  fun update(map: MapData?, rasters: Map<String, MapLayerData>): FieldStatusData? {
    val layer = rasters[layerId]
    if (map == null || map.fields.isEmpty() || layer == null || layer.gridSize <= 0) {
      gridMap = null
      grid = null
      statusVersion = null
      status = null
      return null
    }

    // Compared by value, not by identity: the watcher happens to keep the previous instance when a
    // reparse produces an equal one, but a cache that silently depends on that would be wrong the day
    // it stops being true, and the compare is a few thousand floats against rebuilding the grid.
    if (grid == null || gridMap != map || gridSize != layer.gridSize) {
      gridMap = map
      gridSize = layer.gridSize
      grid = FieldIndexGrid.of(map, layer.gridSize)
      statusVersion = null // the grid moved under it, so the last histogram is not this map's
    }

    val version = layer.contentVersion
    if (status == null || statusVersion != version) {
      statusVersion = version
      status = grid?.histogram(layer)
    }
    return status
  }

  /** The last value [update] produced, without recomputing anything. */
  @Synchronized
  fun current(): FieldStatusData? = status
}
