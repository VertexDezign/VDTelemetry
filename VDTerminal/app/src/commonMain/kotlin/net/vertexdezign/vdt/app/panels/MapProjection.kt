package net.vertexdezign.vdt.app.panels

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset

/**
 * The map's one transform: normalized `[0,1]` map space → pixels inside the square the map is drawn
 * into.
 *
 * Every channel the map overlays — field polygons, POI dots, vehicle markers, the player, a search
 * hit — arrives in the same normalized frame (see `MapData`), and each of them used to re-derive
 * `norm * side * scale + offset` at its own draw site: the tap hit-test, the zoom-scaled image
 * layer, the player marker and the overlay canvas each had their own copy. They agreed only as long
 * as nobody edited one of them.
 *
 * Collecting it here is what makes the next steps tractable rather than four parallel edits: a
 * course-up view is a rotation folded into [toScreen], and a perspective tilt is a divide added to
 * it. Both then reach every overlay at once.
 *
 * [side] is the edge length in px of the square the map fills (the map is always square — the game's
 * PDA image is), [scale] the zoom factor, and [offset] the pan translation applied *after* scaling.
 */
@Immutable
data class MapProjection(val side: Float, val scale: Float, val offset: Offset) {
  /** Pixels per unit of normalized space — one normalized unit spans the whole map at zoom 1. */
  val factor: Float get() = side * scale

  fun toScreen(normX: Float, normZ: Float): Offset = Offset(normX * factor + offset.x, normZ * factor + offset.y)

  fun toScreen(norm: Offset): Offset = toScreen(norm.x, norm.y)

  /** The inverse: a point in the map box's pixel space back to normalized map space. */
  fun toNorm(screen: Offset): Offset = Offset((screen.x - offset.x) / factor, (screen.y - offset.y) / factor)

  /**
   * Whether a projected point is worth drawing: inside the box, plus [margin] px of slack so a
   * marker whose anchor sits just off-screen still draws its part that reaches back in.
   */
  fun isVisible(pos: Offset, margin: Float): Boolean =
    pos.x in -margin..side + margin && pos.y in -margin..side + margin

  companion object {
    /** The offset that puts [norm] at the centre of a [side]-px box at [scale]. */
    fun centeredOn(norm: Offset, side: Float, scale: Float): Offset =
      Offset(side / 2f - norm.x * side * scale, side / 2f - norm.y * side * scale)
  }
}

/**
 * The offset that keeps [focal] (a point in the box's pixel space) pinned where it is while the zoom
 * goes [from] → [to], starting from the current [base] offset.
 *
 * Deliberately free of [MapProjection] and of `side`: the pinch and wheel handlers run inside a
 * `pointerInput(Unit)` that never restarts, so anything they capture from composition would go stale
 * on a resize. This only needs the two scales and the offsets, which they read live.
 */
internal fun zoomedOffset(base: Offset, focal: Offset, from: Float, to: Float): Offset {
  val f = to / from
  return Offset(focal.x - (focal.x - base.x) * f, focal.y - (focal.y - base.y) * f)
}
