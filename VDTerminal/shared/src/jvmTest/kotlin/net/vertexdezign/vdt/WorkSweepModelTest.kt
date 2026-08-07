package net.vertexdezign.vdt

import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.SweptArea
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.WorkSweep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What ground a work area claims between two samples, and which previous footprint it claims it from
 * — the two things that decide where the swath is drawn, on the map and in the server's coverage mask.
 *
 * The pairing case worth pinning is a tool whose parts switch on and off while it works: a spot
 * sprayer does that several times a second. Pair an area with its neighbour's last footprint and the
 * sweep claims the ground between the two, which is the one thing this whole feature exists not to do.
 *
 * The shape case is the spreader (issue #62), whose footprint is a rhombus rather than a rectangle.
 */
class WorkSweepModelTest {
  private val terrain = 2048f

  /** A work area quad: [x]/[z] is the start corner, [width] across the tool and [depth] along travel. */
  private fun area(
    x: Float,
    z: Float,
    width: Float = 6f,
    depth: Float = 0.4f,
    active: Boolean = true,
  ) = WorkArea(
    // The engine's per-object index, which is 1 on both of these: they are two areas of one tool.
    index = 1,
    active = active,
    shape =
      listOf(
        x / terrain,
        z / terrain,
        (x + width) / terrain,
        z / terrain,
        x / terrain,
        (z + depth) / terrain,
      ),
  )

  private fun rig(vararg areas: WorkArea) = Vehicle(implement = listOf(Implement(workAreas = areas.toList())))

  private fun SweptArea.metresX() = xs.map { it * terrain }

  private fun SweptArea.metresZ() = zs.map { it * terrain }

  @Test
  fun keepsEachAreaOnItsOwnTrailWhenAnotherSwitchesOff() {
    // Two sections of one boom, 12 m apart — well inside the distance guard, so nothing else would
    // catch a mispairing. The left one is switched off for a sample and back on afterwards.
    val sweep = WorkSweep()

    fun left(
      z: Float,
      active: Boolean = true,
    ) = area(x = 500f, z = z, active = active)

    fun right(z: Float) = area(x = 512f, z = z)

    sweep.advance(rig(left(500f), right(500f)), terrain, nowMs = 0)

    val oneOff = sweep.advance(rig(left(505f, active = false), right(505f)), terrain, nowMs = 100)
    assertEquals(1, oneOff.size, "only the section still working sweeps anything")
    val swept = oneOff.single()
    assertTrue(
      swept.metresX().all { it >= 512f },
      "the working section swept from its own last footprint, not the switched-off one's: ${swept.metresX()}",
    )
    assertTrue(swept.metresZ().minOf { it } <= 500.4f, "and it bridged the ground back to 500")
    assertTrue(swept.metresZ().maxOf { it } >= 505f)

    // Switched back on, the left section starts a new trail — the ground it was lifted over is not
    // worked — while the right one carries on with its own history.
    val both = sweep.advance(rig(left(510f), right(510f)), terrain, nowMs = 200)
    assertEquals(2, both.size)
    val leftSwept = both.first { swept -> swept.metresX().all { it < 512f } }
    val rightSwept = both.first { swept -> swept.metresX().all { it >= 512f } }
    assertTrue(
      leftSwept.metresZ().minOf { it } >= 509.9f,
      "the left section stamps its footprint, with no stripe back over the ground it was off for",
    )
    assertTrue(rightSwept.metresZ().minOf { it } <= 505.1f, "the right section bridges 505 -> 510 unbroken")
  }

  /**
   * A solid spreader's fan, in the shape the engine describes it by and the fixtures capture: `start`
   * on the centre line at the disc, `width` and `height` at the two ends of the spread, and the
   * derived fourth corner back on the centre line at the far end. Sweeping the `start -> width` edge
   * — which is the leading edge of every *rectangular* area — covers half of this one.
   */
  private fun fan(
    z: Float,
    centre: Float = 500f,
    width: Float = 36f,
    depth: Float = 10f,
  ) = WorkArea(
    index = 1,
    active = true,
    shape =
      listOf(
        centre / terrain,
        z / terrain,
        (centre - width / 2f) / terrain,
        (z + depth / 2f) / terrain,
        (centre + width / 2f) / terrain,
        (z + depth / 2f) / terrain,
      ),
  )

  /** Positive or negative according to which way the ring is wound; only the sign is read. */
  private fun SweptArea.signedArea(): Float {
    var sum = 0f
    for (i in xs.indices) {
      val j = (i + 1) % xs.size
      sum += xs[i] * zs[j] - xs[j] * zs[i]
    }
    return sum
  }

  @Test
  fun sweepsTheWholeSpreadOfARhombusFootprint() {
    val sweep = WorkSweep()

    val stamped = sweep.advance(rig(fan(500f)), terrain, nowMs = 0).single()
    assertEquals(482f, stamped.metresX().minOf { it }, 0.01f, "the footprint alone already covers the fan")
    assertEquals(518f, stamped.metresX().maxOf { it }, 0.01f)

    // Three meters on, the tool has swept the whole 36 m of ground behind it — not the 18 m from the
    // centre line to whichever end the i3d happens to call `width`.
    val bridged = sweep.advance(rig(fan(503f)), terrain, nowMs = 100).single()
    assertEquals(482f, bridged.metresX().minOf { it }, 0.01f, "the far end of the fan is swept too")
    assertEquals(518f, bridged.metresX().maxOf { it }, 0.01f)
    assertEquals(500f, bridged.metresZ().minOf { it }, 0.01f, "and it bridges the ground back to where it was")
    assertEquals(513f, bridged.metresZ().maxOf { it }, 0.01f)

    // Both branches wind the same way, which is what stops the app's merged trail path from
    // cancelling itself out where two polygons overlap.
    assertTrue(
      stamped.signedArea() * bridged.signedArea() > 0f,
      "the stamped footprint and the bridge are wound alike",
    )
  }
}
