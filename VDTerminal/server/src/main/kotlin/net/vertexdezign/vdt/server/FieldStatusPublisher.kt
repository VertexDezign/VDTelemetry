package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.FIELD_STATUS_PLANES
import net.vertexdezign.vdt.model.FieldIndexGrid
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.FieldStatuses
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.SliceGrouping

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
class FieldStatusPublisher(private val planes: Map<String, SliceGrouping> = FIELD_STATUS_PLANES) {
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
    val present = planes.keys.mapNotNull { id -> rasters[id]?.takeIf { it.gridSize > 0 }?.let { id to it } }
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
    var index = grid
    if (index == null || gridMap != map || gridSize != size) {
      gridMap = map
      gridSize = size
      index = FieldIndexGrid.of(map, size)
      grid = index
      // The grid moved under them, so no cached histogram describes this map any more.
      versions.clear()
      histograms.clear()
    }

    var changed = false
    // The planes that could actually be laid over this index. A plane that disagrees with it about
    // resolution or terrain size counts as nothing here rather than as a swept plane full of zeroes:
    // histogram returns the same empty result either way, and publishing that as a breakdown is how
    // "we cannot tell yet" reaches the app as "0 ha ready to harvest" (see fieldTotals, which asks
    // whether the plane is there and not whether it said anything).
    val counted = mutableSetOf<String>()
    for ((id, layer) in present) {
      if (!index.accepts(layer)) continue
      counted += id
      val version = layer.contentVersion
      if (versions[id] == version && histograms.containsKey(id)) continue
      versions[id] = version
      histograms[id] = index.histogram(layer, planes[id] ?: SliceGrouping.KIND)
      changed = true
    }
    // A plane that stopped being counted leaves rather than lingers — the channel switched off
    // mid-session, or the raster it now writes no longer fits this map's grid. Either way what is
    // cached describes a sweep that has been superseded.
    val gone = histograms.keys - counted
    if (gone.isNotEmpty()) {
      gone.forEach {
        histograms.remove(it)
        versions.remove(it)
      }
      changed = true
    }

    if (changed || statuses == null) {
      val kept = planes.keys.mapNotNull { histograms[it] }
      // No plane fits the map: the same "no raster yet" the app already knows how to say, rather than
      // a FieldStatuses carrying nothing, which reads as a breakdown that came back blank.
      statuses = if (kept.isEmpty()) null else FieldStatuses(kept)
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
