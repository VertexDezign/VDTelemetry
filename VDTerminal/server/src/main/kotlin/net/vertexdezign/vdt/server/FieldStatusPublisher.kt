package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.FieldIndexGrid
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.FieldStatuses
import net.vertexdezign.vdt.model.GROWTH_LAYER_ID
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.SOIL_LAYER_ID

/**
 * The planes the per-field breakdown is counted off.
 *
 * **Growth** answers "what stage is this field at" — which part of it is ready, cut, growing,
 * ploughed or withered. **Soil** answers "what condition is it in" — weeds, stones, needs plowing,
 * needs lime, fertilizer. Both matter to the same question ("what does this field need next"), and
 * both are the same shape of answer: a share of the field rather than a reading at one point.
 *
 * Counting a second plane is cheap where it matters, which is the mod: `classifyCell` gates its reads
 * per plane and growth and soil share the `GROUND_TYPE` read, so a subscription to both is one cell
 * walk with a few more density reads, not two sweeps.
 *
 * The crops plane stays out. Its entries are fruit types the app would have to resolve, and
 * `fieldInfo.crop` already names the dominant one; the mechanism generalises to it for free if that
 * ever stops being enough.
 */
val FIELD_STATUS_LAYER_IDS = listOf(GROWTH_LAYER_ID, SOIL_LAYER_ID)

/**
 * Keeps the per-field status histograms up to date without recomputing them for nothing.
 *
 * Two levels of cache, because the inputs move at completely different rates. The field index grid
 * depends only on the map, which changes when a farmland is bought or a save is loaded — rarely; each
 * histogram depends on it and on one plane's raster, which changes every sweep of *that* plane.
 * Without the split, buying a field and harvesting one would cost the same work, and a soil sweep
 * would rebuild the growth counts.
 *
 * Each plane is keyed on its own [MapLayerData.contentVersion] rather than on the raster object,
 * because the flow this is driven from carries **every** plane: any sweep re-emits the whole keyed
 * map. The version is memoized on the parsed instance, so the check is a string compare.
 *
 * Returning the *same* [FieldStatuses] instance when nothing changed is load-bearing, not a
 * micro-optimisation: it is handed to a `MutableStateFlow`, which drops an equal value, so an
 * unchanged breakdown broadcasts nothing at all.
 *
 * Thread-safe — it is updated from a collector and could be read from anywhere.
 */
class FieldStatusPublisher(private val layerIds: List<String> = FIELD_STATUS_LAYER_IDS) {
  private var gridMap: MapData? = null
  private var gridSize = 0
  private var grid: FieldIndexGrid? = null

  /** Per layer id: the raster version the cached histogram was counted off, and the histogram. */
  private val versions = mutableMapOf<String, String>()
  private val histograms = mutableMapOf<String, FieldStatusData>()
  private var statuses: FieldStatuses? = null

  /**
   * The breakdowns for the current map and rasters, or null when there is nothing to derive one from.
   *
   * Null is "no raster yet" — the map hasn't loaded, the layer channel is off, or nobody has
   * subscribed to any of these planes so the mod has never swept them. It is never "the mod isn't
   * installed": this is derived here, not exported. The app says "sampling…" for it rather than
   * drawing a zeroed breakdown, which matters most right after the field app opens — a subscription
   * starts a full sweep, and a full sweep is seconds.
   *
   * A plane that has been swept while another has not simply appears alone; there is no waiting for
   * the set to be complete, because the field list can already say something useful with one of them.
   */
  @Synchronized
  fun update(map: MapData?, rasters: Map<String, MapLayerData>): FieldStatuses? {
    val present = layerIds.mapNotNull { id -> rasters[id]?.takeIf { it.gridSize > 0 }?.let { id to it } }
    if (map == null || map.fields.isEmpty() || present.isEmpty()) {
      reset()
      return null
    }

    // Every plane the mod writes shares one grid size, so the first present raster sizes the index and
    // any plane that disagrees is simply not counted (histogram refuses a mismatched raster anyway).
    val size = present.first().second.gridSize
    // Compared by value, not by identity: the watcher happens to keep the previous instance when a
    // reparse produces an equal one, but a cache that silently depends on that would be wrong the day
    // it stops being true, and the compare is a few thousand floats against rebuilding the grid.
    if (grid == null || gridMap != map || gridSize != size) {
      gridMap = map
      gridSize = size
      grid = FieldIndexGrid.of(map, size)
      // The grid moved under them, so no cached histogram describes this map any more.
      versions.clear()
      histograms.clear()
    }

    var changed = false
    for ((id, layer) in present) {
      val version = layer.contentVersion
      if (versions[id] == version && histograms.containsKey(id)) continue
      versions[id] = version
      histograms[id] = grid?.histogram(layer) ?: continue
      changed = true
    }
    // A plane that stopped being written (channel switched off mid-session) leaves rather than lingers.
    val gone = histograms.keys - present.map { it.first }.toSet()
    if (gone.isNotEmpty()) {
      gone.forEach {
        histograms.remove(it)
        versions.remove(it)
      }
      changed = true
    }

    if (changed || statuses == null) {
      statuses = FieldStatuses(layerIds.mapNotNull { histograms[it] })
    }
    return statuses
  }

  /** The last value [update] produced, without recomputing anything. */
  @Synchronized
  fun current(): FieldStatuses? = statuses

  private fun reset() {
    gridMap = null
    grid = null
    versions.clear()
    histograms.clear()
    statuses = null
  }
}
