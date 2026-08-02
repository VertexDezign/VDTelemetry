package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.COVERAGE_LAYER_ID
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.activeWorkAreas
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Roughly the ground one cell stands for. Two meters is the plan's figure: finer than any tool. */
private const val CELL_METERS = 2f

/** Grid bounds. The upper one is also what keeps a corrupt terrain size from asking for gigabytes. */
private const val MIN_GRID = 64
private const val MAX_GRID = 1024

/** Cell value for worked ground. One value, because "has this been covered" has one answer. */
private const val WORKED = 1

/**
 * How stale a previous sample may be and still be bridged from. Generous next to the ~100 ms
 * telemetry tick, and short enough that a pause (a menu, a stutter, the app catching up) ends the
 * trail rather than drawing a stripe across whatever was skipped.
 */
private const val MAX_BRIDGE_MS = 1000L

/** …and how far it may have moved, in meters. Past this it is a teleport or a different tool. */
private const val MAX_BRIDGE_METERS = 40f

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
 * ### What gets painted
 *
 * Each [WorkArea.shape] is three corners of the tool's footprint parallelogram, already in the
 * normalized `[0,1]` map frame — so the raster needs no projection of its own, and an articulated
 * trailer mid-turn is placed correctly rather than guessed at from the tractor's heading and width.
 * Only areas the engine calls *active* count; see [activeWorkAreas] for why that and not `processing`.
 *
 * Between two samples the tool has moved, and at 40 km/h it moves about a meter per tick while the
 * footprint itself is a few tens of centimeters deep — so painting the quads alone would leave the
 * ground striped. Each area is therefore also bridged to where it was on the previous sample, which is
 * what turns a row of stamps into a swath. Both guards on that bridge exist to stop it lying: a sample
 * too old, or too far away, means the trail is broken and gets no stripe drawn across the gap.
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
  /** A tool's footprint on one sample: three corners in normalized map coordinates, and when. */
  private data class Footprint(
    val startX: Float,
    val startZ: Float,
    val widthX: Float,
    val widthZ: Float,
    val heightX: Float,
    val heightZ: Float,
    val atMs: Long,
  )

  private var cells = ByteArray(0)
  private var gridSize = 0
  private var terrainSize = 0f

  /** Last sample per work area, keyed by its place in the flattened rig — see [record]. */
  private var previous: Map<Int, Footprint> = emptyMap()

  /** Whether anything has changed since the last snapshot was taken. */
  private var dirty = false

  /**
   * Fold one telemetry sample into the mask.
   *
   * [terrainSize] is the map's edge in meters, which only sizes the grid — the coordinates are already
   * normalized. A changed one means a different map was loaded, and the mask starts over: coverage of
   * a field on another map painted onto this one would be worse than none.
   *
   * Areas are keyed by position in the flattened rig, which is stable while the rig is, and changes
   * when a tool is hitched or dropped. A wrong pairing after such a change would bridge one tool's
   * footprint to another's — which is what [MAX_BRIDGE_METERS] is really guarding, since the two are
   * meters apart on the same machine.
   */
  @Synchronized
  fun record(
    vehicle: Vehicle?,
    terrainSize: Float,
    nowMs: Long,
  ) {
    if (terrainSize <= 0f) return // no map yet: nothing to size a grid against
    resize(terrainSize)

    val areas = vehicle?.activeWorkAreas()?.filter { it.shape.size >= 6 }.orEmpty()
    if (areas.isEmpty()) {
      // The tool is up, or there is no tool, or the driver is on foot. Forgetting the last sample is
      // what stops the next lowering from drawing a stripe back to wherever it was last down.
      previous = emptyMap()
      return
    }

    val maxJump = MAX_BRIDGE_METERS / terrainSize
    val next = HashMap<Int, Footprint>(areas.size)
    areas.forEachIndexed { index, area ->
      val now = area.footprint(nowMs)
      fillPolygon(now.cornersX(), now.cornersZ())
      previous[index]?.let { last ->
        if (nowMs - last.atMs <= MAX_BRIDGE_MS && last.near(now, maxJump)) {
          // The ground between the tool's leading edge then and now — the part the stamps miss.
          fillPolygon(
            floatArrayOf(last.startX, last.widthX, now.widthX, now.startX),
            floatArrayOf(last.startZ, last.widthZ, now.widthZ, now.startZ),
          )
        }
      }
      next[index] = now
    }
    previous = next
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
    previous = emptyMap()
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
    previous = emptyMap()
    if (replacing) dirty = true
  }

  private fun WorkArea.footprint(atMs: Long) =
    Footprint(
      startX = shape[0],
      startZ = shape[1],
      widthX = shape[2],
      widthZ = shape[3],
      heightX = shape[4],
      heightZ = shape[5],
      atMs = atMs,
    )

  /**
   * The footprint's four corners, wound as a ring. The mod sends three and leaves the fourth — the
   * one opposite the start, at `width + height - start` — to be derived, the same way the app's map
   * overlay draws it.
   */
  private fun Footprint.cornersX() = floatArrayOf(startX, widthX, widthX + heightX - startX, heightX)

  private fun Footprint.cornersZ() = floatArrayOf(startZ, widthZ, widthZ + heightZ - startZ, heightZ)

  private fun Footprint.near(
    other: Footprint,
    maxJump: Float,
  ): Boolean = abs(startX - other.startX) <= maxJump && abs(startZ - other.startZ) <= maxJump

  /**
   * Paint a polygon given in normalized coordinates, by scanline.
   *
   * The rows a polygon crosses are filled between its outermost edge crossings, which for the convex
   * quads this deals in is exactly the polygon. Where a turn makes the bridge slightly concave the
   * min/max span paints its convex hull instead — an over-paint of ground the tool did sweep on the
   * inside of that turn anyway.
   *
   * Vertices lying inside a row are folded into that row's span as well, and that is not a refinement:
   * a boom is tens of meters wide and a few tens of centimeters deep, so a footprint quad regularly
   * falls entirely between two scanline centres. On crossings alone such a quad paints **nothing**,
   * which is the whole width of the machine going unrecorded.
   */
  private fun fillPolygon(
    xs: FloatArray,
    zs: FloatArray,
  ) {
    if (gridSize <= 0) return
    val size = gridSize
    // Normalized -> cell coordinates. Everything below is in cells.
    val gx = FloatArray(xs.size) { xs[it] * size }
    val gz = FloatArray(zs.size) { zs[it] * size }

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
        // A vertex inside this row's band contributes directly — see the doc comment.
        if (gz[i] >= row && gz[i] < row + 1f) {
          if (gx[i] < lo) lo = gx[i]
          if (gx[i] > hi) hi = gx[i]
        }
        // Half-open on purpose: an edge crossing the scanline is counted by exactly one of the two
        // edges meeting at a vertex sitting on it, so a shared vertex doesn't double-count.
        val crosses = (z1 <= centre && z2 > centre) || (z2 <= centre && z1 > centre)
        if (!crosses) continue
        val x = gx[i] + (centre - z1) / (z2 - z1) * (gx[j] - gx[i])
        if (x < lo) lo = x
        if (x > hi) hi = x
      }
      if (lo > hi) continue

      val firstCol = floor(lo).toInt().coerceIn(0, size - 1)
      val lastCol = floor(hi).toInt().coerceIn(0, size - 1)
      val base = row * size
      for (col in firstCol..lastCol) {
        if (cells[base + col] == WORKED.toByte()) continue
        cells[base + col] = WORKED.toByte()
        dirty = true
      }
    }
  }
}
