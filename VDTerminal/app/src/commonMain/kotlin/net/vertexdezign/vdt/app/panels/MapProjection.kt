package net.vertexdezign.vdt.app.panels

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map's one transform: normalized `[0,1]` map space → pixels inside the square the map is drawn
 * into.
 *
 * Every channel the map overlays — field polygons, POI dots, vehicle markers, the player, the
 * guidance course, a search hit — arrives in the same normalized frame (see `MapData`), and each of
 * them used to re-derive `norm * side * scale + offset` at its own draw site: the tap hit-test, the
 * zoom-scaled image layer, the player marker and the overlay canvas each had their own copy. They
 * agreed only as long as nobody edited one of them.
 *
 * Collecting it here is what made course-up one change instead of five. The pipeline is
 * **scale → translate → rotate**: a normalized point is scaled by [factor], shifted by [offset], and
 * finally turned by [rotationDeg] about [pivot] — a screen-space point that therefore does not move,
 * which is why the vehicle stays put while the world turns under it.
 *
 * [side] is the edge length in px of the square the map fills (the map is always square — the game's
 * PDA image is), [scale] the zoom factor, and [offset] the pan translation applied *after* scaling
 * and *before* rotation.
 */
@Immutable
data class MapProjection(
  val side: Float,
  val scale: Float,
  val offset: Offset,
  /** Clockwise screen rotation in degrees; 0 is north-up. Course-up passes `-heading`. */
  val rotationDeg: Float = 0f,
  /** The screen point [rotationDeg] turns about — where the vehicle sits in course-up. */
  val pivot: Offset = Offset.Zero,
) {
  /** Pixels per unit of normalized space — one normalized unit spans the whole map at zoom 1. */
  val factor: Float get() = side * scale

  private val radians: Float get() = rotationDeg * (PI.toFloat() / 180f)

  fun toScreen(normX: Float, normZ: Float): Offset =
    rotate(Offset(normX * factor + offset.x, normZ * factor + offset.y))

  fun toScreen(norm: Offset): Offset = toScreen(norm.x, norm.y)

  /** The inverse: a point in the map box's pixel space back to normalized map space. */
  fun toNorm(screen: Offset): Offset {
    val flat = unrotate(screen)
    return Offset((flat.x - offset.x) / factor, (flat.y - offset.y) / factor)
  }

  /** Turn a point about [pivot] by [rotationDeg] — flat space to screen space. */
  fun rotate(point: Offset): Offset = turn(point, radians)

  /** Undo [rotate]: a screen point back to the unrotated space [offset] and [factor] work in. */
  fun unrotate(point: Offset): Offset = turn(point, -radians)

  /**
   * The same for a *direction* — a drag delta, which has no position and so must not be moved by the
   * pivot. A pan of one screen pixel to the right has to become one pixel to the right on the rotated
   * map, which means the offset it feeds moves along the unrotated axes.
   */
  fun unrotateVector(delta: Offset): Offset {
    if (rotationDeg == 0f) return delta
    val c = cos(-radians)
    val s = sin(-radians)
    return Offset(delta.x * c - delta.y * s, delta.x * s + delta.y * c)
  }

  private fun turn(point: Offset, angle: Float): Offset {
    if (rotationDeg == 0f) return point
    val c = cos(angle)
    val s = sin(angle)
    val dx = point.x - pivot.x
    val dy = point.y - pivot.y
    return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
  }

  /**
   * Whether a projected point is worth drawing: inside the box, plus [margin] px of slack so a
   * marker whose anchor sits just off-screen still draws its part that reaches back in.
   */
  fun isVisible(pos: Offset, margin: Float): Boolean =
    pos.x in -margin..side + margin && pos.y in -margin..side + margin

  companion object {
    /** The offset that puts [norm] at the centre of a [side]-px box at [scale]. */
    fun centeredOn(norm: Offset, side: Float, scale: Float): Offset =
      anchoredAt(norm, Offset(side / 2f, side / 2f), side, scale)

    /**
     * The offset that puts [norm] at an arbitrary screen point.
     *
     * Course-up wants the vehicle low on the screen rather than in the middle, so that the space the
     * map spends is on the ground ahead of it — which is the whole reason to turn the map at all.
     * Rotation does not disturb this: [anchor] doubles as the rotation pivot, and the pivot is the
     * one point rotation leaves where it is.
     */
    fun anchoredAt(norm: Offset, anchor: Offset, side: Float, scale: Float): Offset =
      Offset(anchor.x - norm.x * side * scale, anchor.y - norm.y * side * scale)
  }
}

/**
 * The offset that keeps [focal] (a point in the *unrotated* pixel space — see
 * [MapProjection.unrotate]) pinned where it is while the zoom goes [from] → [to], starting from the
 * current [base] offset.
 *
 * Deliberately free of [MapProjection] and of `side`: the pinch and wheel handlers run inside a
 * `pointerInput(Unit)` that never restarts, so anything they captured from composition would go stale
 * on a resize. This only needs the two scales and the offsets, which they read live.
 */
internal fun zoomedOffset(base: Offset, focal: Offset, from: Float, to: Float): Offset {
  val f = to / from
  return Offset(focal.x - (focal.x - base.x) * f, focal.y - (focal.y - base.y) * f)
}
