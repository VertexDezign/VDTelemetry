package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.COVERAGE_LAYER_ID
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.SweptArea
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkSweep
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Roughly the ground one cell stands for.
 *
 * Finer than the mod's own planes, which sample the game's 512-cell map overlay, because this layer is
 * read for a different thing: not "what is growing over there" but "did I miss a strip". A metre is
 * about the narrowest miss worth seeing from the seat, and it is what a skipped pass between two
 * mowers actually measures.
 */
private const val CELL_METERS = 1f

/** Grid bounds. The upper one is also what keeps a corrupt terrain size from asking for gigabytes. */
private const val MIN_GRID = 64

/**
 * At [CELL_METERS] this gives metre cells on an ordinary 2 km map and two-metre cells on a 4x one,
 * which is the trade the memory is worth making: the mask is a byte per cell and the rendered PNG a
 * pixel per cell, both of which the browser holds.
 */
private const val MAX_GRID = 2048

/** Cell value for worked ground. One value, because "has this been covered" has one answer. */
private const val WORKED = 1

/**
 * Where the rig's tools have actually been — the coverage layer, accumulated by the **server** from
 * the telemetry it already receives.
 *
 * The game's own ground layers cannot answer this. A tedder spreads a windrow and changes nothing any
 * plane samples; a roller, a mower, a weeder all leave the map exactly as they found it. What does
 * record it is the work-area footprint the mod already exports for the map overlay, so this needs no
 * new export and costs the game nothing — which was the deciding argument for putting it here rather
 * than in the mod, where `Json.lua` is already the hot spot during active work.
 *
 * The geometry — which ground each sample covers, and when a trail may be continued across a gap —
 * is [WorkSweep], shared with the app so the live overlay and the durable mask cannot disagree. What
 * is left here is the mask itself: which cells those polygons land on, and how that reaches the app.
 *
 * ### What "landed on" means
 *
 * A cell is worked when its **centre** lies inside a swept polygon — not when a polygon touches it
 * anywhere. That distinction is the whole accuracy of the layer. Touch-filling over-reports by up to a
 * cell on every edge, so two mowers leaving a metre between them each bleed into the cell in the gap
 * and the miss disappears — at any resolution, which is why finer cells alone would not have fixed it.
 * Centre sampling costs nothing in coverage because [WorkSweep]'s polygons tile the corridor driven:
 * every point in it lies in exactly one of them.
 *
 * ### What it is not
 *
 * In memory, for as long as the server runs. Coverage restarts with the terminal, and loading a
 * different map (a changed terrain size) clears it. Persisting it would need a savegame identity the
 * telemetry does not carry; the seam for adding it later is exactly this class.
 *
 * Thread-safe: recording happens on the telemetry collector, snapshotting on the publish timer, and
 * [reset] on a request thread.
 */
class CoverageRecorder(
  private val cellMeters: Float = CELL_METERS,
) {
  private val sweep = WorkSweep()

  private var cells = ByteArray(0)
  private var gridSize = 0
  private var terrainSize = 0f

  /** Whether anything has changed since the last snapshot was taken. */
  private var dirty = false

  /**
   * Fold one telemetry sample into the mask.
   *
   * [terrainSize] is the map's edge in meters, which only sizes the grid — the coordinates are already
   * normalized. A changed one means a different map was loaded, and the mask starts over: coverage of
   * a field on another map painted onto this one would be worse than none.
   */
  @Synchronized
  fun record(
    vehicle: Vehicle?,
    terrainSize: Float,
    nowMs: Long,
  ) {
    if (terrainSize <= 0f) return // no map yet: nothing to size a grid against
    resize(terrainSize)
    for (area in sweep.advance(vehicle, terrainSize, nowMs)) fill(area)
  }

  /**
   * The mask as a ground-layer raster, or null when nothing has changed since the last one.
   *
   * A [MapLayerData] like any the mod writes, so it reaches the app through the catalogue, the
   * content-versioned PNG route and the layer picker it already has, with no special case anywhere
   * along the way. The null is what keeps the app from refetching a raster it already holds: the
   * version is content-derived, so an unchanged mask would produce an identical one anyway, but this
   * avoids hashing hundreds of kilobytes to discover that.
   */
  @Synchronized
  fun snapshotIfChanged(): MapLayerData? {
    if (!dirty) return null
    dirty = false
    return snapshot()
  }

  /** The mask as a raster, changed or not. */
  @Synchronized
  fun snapshot(): MapLayerData {
    val rows =
      (0 until gridSize).map { row ->
        val base = row * gridSize
        // Right-trimmed, like the mod's own rows: a field is a small part of a map, so most rows are
        // empty and the rest end long before the map does.
        var last = -1
        for (col in gridSize - 1 downTo 0) {
          if (cells[base + col] != 0.toByte()) {
            last = col
            break
          }
        }
        if (last < 0) {
          ""
        } else {
          buildString((last + 1) * 2) {
            for (col in 0..last) append(if (cells[base + col] == 0.toByte()) "00" else "01")
          }
        }
      }
    return MapLayerData(
      version = "1",
      terrainSize = terrainSize,
      gridSize = gridSize,
      id = COVERAGE_LAYER_ID,
      legend = listOf(MapLayerLegendEntry(v = WORKED, label = "Worked", color = "#2d8633")),
      rows = rows,
    )
  }

  /** Forget everything worked so far. The next snapshot publishes the cleared mask. */
  @Synchronized
  fun reset() {
    cells.fill(0)
    sweep.forget()
    dirty = true
  }

  /** Whether anything has been recorded — for the route, which has nothing to offer before that. */
  @Synchronized
  fun hasGrid(): Boolean = gridSize > 0

  /** Allocate for this map, and start over when it is a different one. */
  private fun resize(terrain: Float) {
    if (gridSize > 0 && terrain == terrainSize) return
    // Replacing a grid is itself a change worth publishing: whoever is watching holds the previous
    // map's mask and has to be told it is gone. Allocating the FIRST one is not — nothing has been
    // worked yet, and an empty raster published on map load is a version, a fetch and a decode for a
    // fully transparent picture.
    val replacing = gridSize > 0
    gridSize = (terrain / cellMeters).roundToInt().coerceIn(MIN_GRID, MAX_GRID)
    cells = ByteArray(gridSize * gridSize)
    terrainSize = terrain
    sweep.forget()
    if (replacing) dirty = true
  }

  /**
   * Mark every cell whose centre lies inside [area], by scanline.
   *
   * Rows are tested at their own centre and columns are clipped to the cells whose centres fall within
   * the span — so a polygon claims a cell only when it actually covers the point that cell stands for.
   * Where a turn makes a swept polygon slightly concave the outermost crossings paint its convex hull
   * instead, which is ground the tool did sweep on the inside of that turn anyway.
   */
  private fun fill(area: SweptArea) {
    if (gridSize <= 0) return
    val size = gridSize
    // Normalized -> cell coordinates. Everything below is in cells.
    val gx = FloatArray(area.xs.size) { area.xs[it] * size }
    val gz = FloatArray(area.zs.size) { area.zs[it] * size }

    var minZ = Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    for (z in gz) {
      if (z < minZ) minZ = z
      if (z > maxZ) maxZ = z
    }
    if (minZ > maxZ) return
    val firstRow = floor(minZ).toInt().coerceIn(0, size - 1)
    val lastRow = ceil(maxZ).toInt().coerceIn(0, size - 1)

    for (row in firstRow..lastRow) {
      val centre = row + 0.5f
      var lo = Float.MAX_VALUE
      var hi = -Float.MAX_VALUE

      for (i in gx.indices) {
        val j = (i + 1) % gx.size
        val z1 = gz[i]
        val z2 = gz[j]
        // Half-open on purpose: an edge crossing the scanline is counted by exactly one of the two
        // edges meeting at a vertex sitting on it, so a shared vertex doesn't double-count — and two
        // polygons meeting along that edge don't both claim the row.
        val crosses = (z1 <= centre && z2 > centre) || (z2 <= centre && z1 > centre)
        if (!crosses) continue
        val x = gx[i] + (centre - z1) / (z2 - z1) * (gx[j] - gx[i])
        if (x < lo) lo = x
        if (x > hi) hi = x
      }
      if (lo > hi) continue

      // Cell `col` stands for the point at col + 0.5, so the cells covered are those whose centre lies
      // in [lo, hi] — not every cell the span grazes. Bounds-checked before clamping, or a span that
      // lies entirely off one side of the map would clamp both ends onto the edge cell and paint it.
      val fromCol = ceil(lo - 0.5f).toInt()
      val toCol = floor(hi - 0.5f).toInt()
      if (toCol < 0 || fromCol > size - 1 || fromCol > toCol) continue
      val firstCol = fromCol.coerceAtLeast(0)
      val lastCol = toCol.coerceAtMost(size - 1)
      val base = row * size
      for (col in firstCol..lastCol) {
        if (cells[base + col] == WORKED.toByte()) continue
        cells[base + col] = WORKED.toByte()
        dirty = true
      }
    }
  }
}
