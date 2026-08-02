package net.vertexdezign.vdt.model

import kotlin.math.abs

/**
 * How stale a previous sample may be and still be swept from. Generous next to the ~100 ms telemetry
 * tick, and short enough that a pause — a menu, a stutter, a dashboard catching up — ends the trail
 * rather than drawing a stripe across whatever was skipped.
 */
private const val MAX_SWEEP_MS = 1000L

/** …and how far it may have moved, in meters. Past this it is a teleport or a different tool. */
private const val MAX_SWEEP_METERS = 40f

/** A convex polygon of ground, as x/z pairs in the normalized `[0,1]` map frame. */
class SweptArea(
  val xs: FloatArray,
  val zs: FloatArray,
)

/**
 * Ground the rig has actually covered, from one telemetry sample to the next.
 *
 * Shared because two very different things need the same answer and must not disagree about it: the
 * server rasterizes these polygons into the durable coverage mask, and the app fills them straight
 * onto the map so the swath appears behind the machine at once rather than at the server's publish
 * cadence. One implementation means one set of guards to be right about.
 *
 * ### Why the polygons are between samples, not at them
 *
 * A [WorkArea]'s footprint is tens of meters wide and a few tens of centimeters deep, and at working
 * speed the tool moves further between ticks than its own footprint is deep. Stamping the footprints
 * alone therefore leaves the pass striped. What is swept is the ground between where the tool's
 * leading edge *was* and where it *is*, and consecutive sweeps share that edge exactly — so they tile
 * the corridor the tool drove with no seam and no overlap.
 *
 * That tiling is what lets a renderer test cell centres rather than "did the polygon touch this cell
 * at all": every point of the corridor lies in exactly one polygon, so nothing is missed, and nothing
 * is claimed a hand's breadth to either side of where the tool really went.
 *
 * ### The guards
 *
 * Both exist to stop the sweep claiming ground the tool was never on. A sample older than
 * [MAX_SWEEP_MS], or one that has moved further than [MAX_SWEEP_METERS], breaks the trail — the
 * footprint is stamped where it is and no stripe is drawn back to where it was. So does the tool
 * coming up: [advance] forgets everything when nothing is working, which is what stops the next
 * lowering from painting the headland turn in between.
 *
 * Not thread-safe; hold one per consumer.
 */
class WorkSweep {
  private class Footprint(
    val startX: Float,
    val startZ: Float,
    val widthX: Float,
    val widthZ: Float,
    val heightX: Float,
    val heightZ: Float,
    val atMs: Long,
  )

  private var previous: Map<Int, Footprint> = emptyMap()

  /**
   * The ground covered since the previous sample, as polygons in normalized map coordinates.
   *
   * [terrainSize] is the map edge in meters, used only to put [MAX_SWEEP_METERS] into the same frame
   * as the coordinates.
   *
   * Areas are keyed by their slot in the rig's **full** area list ([allWorkAreas]) rather than their
   * place among the working ones: a boom section switching off — which spot spraying does several
   * times a second — would otherwise shift every area behind it up a place and pair it with its
   * neighbour's last footprint, drawing a swath across the ground between the two. The slot still
   * changes when a tool is hitched or dropped, and a wrong pairing after *that* is what the distance
   * guard is really there for, since two tools on one machine are meters apart.
   */
  fun advance(
    vehicle: Vehicle?,
    terrainSize: Float,
    nowMs: Long,
  ): List<SweptArea> {
    if (terrainSize <= 0f) return emptyList()
    val areas =
      vehicle
        ?.allWorkAreas()
        ?.withIndex()
        ?.filter { (_, area) -> area.active && area.shape.size >= 6 }
        .orEmpty()
    if (areas.isEmpty()) {
      previous = emptyMap()
      return emptyList()
    }

    val maxJump = MAX_SWEEP_METERS / terrainSize
    val swept = mutableListOf<SweptArea>()
    val next = HashMap<Int, Footprint>(areas.size)
    areas.forEach { (slot, area) ->
      val now = area.footprint(nowMs)
      val last = previous[slot]
      if (last != null && nowMs - last.atMs <= MAX_SWEEP_MS && last.near(now, maxJump)) {
        // The ground between the tool's leading edge then and now. Shares its far edge with the next
        // sweep's near edge, which is what makes consecutive sweeps tile.
        swept +=
          SweptArea(
            floatArrayOf(last.startX, last.widthX, now.widthX, now.startX),
            floatArrayOf(last.startZ, last.widthZ, now.widthZ, now.startZ),
          )
      } else {
        // No trail to continue: the tool has just come down, or arrived from somewhere it cannot have
        // driven. Its own footprint is all that can honestly be claimed.
        swept += SweptArea(now.cornersX(), now.cornersZ())
      }
      next[slot] = now
    }
    previous = next
    return swept
  }

  /** Break the trail without a sample — the next [advance] starts a new one. */
  fun forget() {
    previous = emptyMap()
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
   * The footprint's four corners, wound as a ring. The mod sends three and leaves the fourth — the one
   * opposite the start, at `width + height - start` — to be derived, as the map overlay also does.
   */
  private fun Footprint.cornersX() = floatArrayOf(startX, widthX, widthX + heightX - startX, heightX)

  private fun Footprint.cornersZ() = floatArrayOf(startZ, widthZ, widthZ + heightZ - startZ, heightZ)

  private fun Footprint.near(
    other: Footprint,
    maxJump: Float,
  ): Boolean = abs(startX - other.startX) <= maxJump && abs(startZ - other.startZ) <= maxJump
}
