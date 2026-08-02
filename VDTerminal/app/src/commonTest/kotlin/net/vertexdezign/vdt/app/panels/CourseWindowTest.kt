package net.vertexdezign.vdt.app.panels

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The test the "nearby lines only" course window rests on: is this line within N swaths of the
 * machine?
 *
 * Worth pinning because the failure is invisible rather than loud — a window measured to the line's
 * *ends* would hide the very line being driven, since a guidance line is two points a field apart and
 * the machine is somewhere in the middle of them.
 */
class CourseWindowTest {
  /** A guidance line: two points, half the map apart, at x. */
  private fun line(x: Float) = listOf(x, 0.2f, x, 0.8f)

  @Test
  fun measuresToTheLineAndNotToItsEnds() {
    // Standing halfway along it, a hair to one side. Both ends are 0.3 away; the line is 0.001 away.
    assertTrue(polylineWithin(line(0.5f), Offset(0.501f, 0.5f), radius = 0.01f))
    assertFalse(polylineWithin(line(0.5f), Offset(0.52f, 0.5f), radius = 0.01f))
  }

  @Test
  fun keepsTheNeighboursAndDropsTheRest() {
    // What "±1" means: a window of 1.5 swaths, so the line you are on and the one either side.
    val swath = 0.01f
    val window = swath * 1.5f
    val here = Offset(0.5f, 0.5f)
    assertTrue(polylineWithin(line(0.5f), here, window), "the line being driven")
    assertTrue(polylineWithin(line(0.5f + swath), here, window), "one swath over")
    assertFalse(polylineWithin(line(0.5f + 2 * swath), here, window), "two swaths over")
  }

  @Test
  fun findsTheNearestPieceOfARing() {
    // A headland ring arrives as a chain of pieces; only the one you are beside should keep the ring
    // on screen, and it is not the piece the coordinates happen to start with.
    val ring = listOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.6f, 0.4f, 0.6f, 0.4f, 0.4f)
    assertTrue(polylineWithin(ring, Offset(0.5f, 0.605f), radius = 0.01f), "beside the far edge")
    assertFalse(polylineWithin(ring, Offset(0.5f, 0.5f), radius = 0.01f), "in the middle of the ring")
  }

  @Test
  fun refusesGeometryItCannotMeasure() {
    // A degenerate polyline is not "everywhere", it is nothing — the overlay skips these too.
    assertFalse(polylineWithin(emptyList(), Offset(0.5f, 0.5f), radius = 1f))
    assertFalse(polylineWithin(listOf(0.5f, 0.5f), Offset(0.5f, 0.5f), radius = 1f))
    // A zero-length piece still measures to where it sits, rather than dividing by its own length.
    assertTrue(polylineWithin(listOf(0.5f, 0.5f, 0.5f, 0.5f), Offset(0.5f, 0.505f), radius = 0.01f))
  }
}
