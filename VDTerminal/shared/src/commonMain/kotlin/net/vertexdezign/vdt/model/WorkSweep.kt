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
class SweptArea(val xs: FloatArray, val zs: FloatArray)

/**
 * Ground the rig has actually covered, from one telemetry sample to the next.
 *
 * Shared because two very different things need the same answer and must not disagree about it: the
 * server rasterizes these polygons into the durable coverage mask, and the app fills them straight
 * onto the map so the swath appears behind the machine at once rather than at the server's publish
 * cadence. One implementation means one set of guards to be right about.
 *
 * ### Why the polygons span samples rather than sitting at them
 *
 * A [WorkArea]'s footprint on a boom or a cultivator is tens of meters wide and a few tens of
 * centimeters deep, and at working speed the tool moves further between ticks than its own footprint
 * is deep. Stamping the footprints alone therefore leaves the pass striped. What is claimed instead is
 * the hull of where the footprint *was* and where it *is* — the ground it covered at both samples and
 * everything it crossed on the way.
 *
 * ### Why the hull, and not the leading edge
 *
 * This used to sweep the `start -> width` edge alone, on the reading that it is the tool's leading
 * edge and that consecutive sweeps of it therefore tile the corridor driven with no seam and no
 * overlap. That reading holds for a rectangular work area and for no other kind. A solid spreader's is
 * a **rhombus**: `start` sits on the centre line at the disc, `width` and `height` are the two side
 * corners, and the derived fourth corner is back on the centre line at the far end of the fan. Its
 * `start -> width` edge spans exactly half the swath — for the AgriSpread AS2100 the fixtures capture,
 * 18 m of a 36 m spread — so a sweep built from it painted one side of every pass and left the other
 * side bare (issue #62). Which side depends only on which corner the i3d calls `width`.
 *
 * The hull needs to know nothing about which corner is which, so it is right for the rhombus, the
 * rectangle, and whatever a mod ships next. What it gives up is the tiling: consecutive polygons now
 * overlap by a footprint. Neither consumer minds — the mask is a boolean per cell, and the trail is
 * one merged path filled once — and the hull is wound consistently, which is what keeps overlapping
 * subpaths from cancelling each other out where they cross.
 *
 * It still claims nothing to either side of where the tool really went: the hull of two footprints is
 * no wider than the footprints unless the machine crabbed sideways, in which case it did cover the
 * ground in between. So a metre missed between two mowers stays missed, which is the whole point of
 * the layer.
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
  /** The four corners of one work area at one sample, wound as a ring. */
  private class Footprint(val xs: FloatArray, val zs: FloatArray, val atMs: Long)

  private var previous: Map<Int, Footprint> = emptyMap()

  /**
   * The ground covered since the previous sample, as polygons in normalized map coordinates.
   *
   * [terrainSize] is the map edge in meters, used only to put [MAX_SWEEP_METERS] into the same frame
   * as the coordinates.
   *
   * Only areas that work ground are swept ([coversGround]) — a combine's straw chopper is active and
   * processing the whole time it threshes, but it spreads over ground the header already cut, wider
   * than the header cut it. Sweeping it made every combine pass read as one the width of the spread.
   *
   * Areas are keyed by their slot in the rig's **full** area list ([allWorkAreas]) rather than their
   * place among the working ones: a boom section switching off — which spot spraying does several
   * times a second — would otherwise shift every area behind it up a place and pair it with its
   * neighbour's last footprint, drawing a swath across the ground between the two. The slot still
   * changes when a tool is hitched or dropped, and a wrong pairing after *that* is what the distance
   * guard is really there for, since two tools on one machine are meters apart.
   */
  fun advance(vehicle: Vehicle?, terrainSize: Float, nowMs: Long): List<SweptArea> {
    if (terrainSize <= 0f) return emptyList()
    val areas =
      vehicle
        ?.allWorkAreas()
        ?.withIndex()
        ?.filter { (_, area) -> area.active && area.coversGround && area.shape.size >= 6 }
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
      swept +=
        if (last != null && nowMs - last.atMs <= MAX_SWEEP_MS && last.near(now, maxJump)) {
          // Both footprints and the ground between them.
          hullOf(last.xs + now.xs, last.zs + now.zs)
        } else {
          // No trail to continue: the tool has just come down, or arrived from somewhere it cannot
          // have driven. Its own footprint is all that can honestly be claimed.
          hullOf(now.xs, now.zs)
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

  /**
   * The area's four corners at this sample.
   *
   * The mod sends three and leaves the fourth — the one opposite the start, at `width + height -
   * start` — to be derived, as the map overlay also does.
   */
  private fun WorkArea.footprint(atMs: Long) = Footprint(
    xs = floatArrayOf(shape[0], shape[2], shape[2] + shape[4] - shape[0], shape[4]),
    zs = floatArrayOf(shape[1], shape[3], shape[3] + shape[5] - shape[1], shape[5]),
    atMs = atMs,
  )

  /** Measured at the start corner, the one the engine anchors the area to. */
  private fun Footprint.near(other: Footprint, maxJump: Float): Boolean =
    abs(xs[0] - other.xs[0]) <= maxJump && abs(zs[0] - other.zs[0]) <= maxJump
}

/**
 * The convex hull of [xs]/[zs], wound consistently whatever order the points arrive in.
 *
 * Andrew's monotone chain, over the eight points at most that a sweep ever holds. Duplicate and
 * collinear points fall out of it, which is what a stationary tool (two identical footprints) and a
 * folded one (a footprint with no area) produce; when too few survive to make a polygon the points are
 * handed back as they came, since a degenerate ring fills nothing either way.
 */
internal fun hullOf(xs: FloatArray, zs: FloatArray): SweptArea {
  val n = xs.size
  if (n < 3) return SweptArea(xs, zs)
  val order = (0 until n).sortedWith(compareBy({ xs[it] }, { zs[it] }))

  fun turn(o: Int, a: Int, b: Int): Float = (xs[a] - xs[o]) * (zs[b] - zs[o]) - (zs[a] - zs[o]) * (xs[b] - xs[o])

  val chain = IntArray(2 * n)
  var k = 0
  for (i in order) {
    while (k >= 2 && turn(chain[k - 2], chain[k - 1], i) <= 0f) k--
    chain[k++] = i
  }
  val lower = k + 1
  for (i in order.size - 2 downTo 0) {
    val point = order[i]
    while (k >= lower && turn(chain[k - 2], chain[k - 1], point) <= 0f) k--
    chain[k++] = point
  }

  // The last point closes the ring onto the first, so it is not part of the polygon.
  val size = k - 1
  if (size < 3) return SweptArea(xs, zs)
  return SweptArea(FloatArray(size) { xs[chain[it]] }, FloatArray(size) { zs[chain[it]] })
}
