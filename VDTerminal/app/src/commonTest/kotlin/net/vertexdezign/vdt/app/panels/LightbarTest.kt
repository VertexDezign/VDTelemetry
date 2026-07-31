package net.vertexdezign.vdt.app.panels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lightbar's two decisions: which cell the marker lands in, and how the error reads as text.
 *
 * Worth pinning down because the sign convention is load-bearing — the mod signs cross-track error
 * "+ = right of the line" (see its `GpsCourse.lineState`), and a bar that leans the wrong way is worse
 * than no bar at all. These tests are the app end of that agreement.
 */
class LightbarTest {
  private val centre = LIGHTBAR_CELLS / 2

  @Test
  fun sitsOnTheCentreCellWhenOnTheLine() {
    assertEquals(centre, lightbarCell(0f))
    // Rounding, not truncation: a tenth of the full scale is still half a cell here.
    assertEquals(centre, lightbarCell(0.05f))
  }

  @Test
  fun leansRightForAPositiveError() {
    // + is right of the line, so the marker moves right — the bubble-level reading, matching the map.
    assertTrue(lightbarCell(0.4f) > centre)
    assertTrue(lightbarCell(-0.4f) < centre)
    assertEquals(centre + 2, lightbarCell(0.4f))
    assertEquals(centre - 2, lightbarCell(-0.4f))
  }

  @Test
  fun pinsToTheEndsRatherThanRunningOffTheBar() {
    assertEquals(LIGHTBAR_CELLS - 1, lightbarCell(LIGHTBAR_FULL_SCALE_M))
    assertEquals(LIGHTBAR_CELLS - 1, lightbarCell(50f))
    assertEquals(0, lightbarCell(-LIGHTBAR_FULL_SCALE_M))
    assertEquals(0, lightbarCell(-50f))
  }

  @Test
  fun readsOutCentimetresThenMetres() {
    assertEquals("R 14 cm", deviationLabel(0.14f))
    assertEquals("L 14 cm", deviationLabel(-0.14f))
    assertEquals("R 99 cm", deviationLabel(0.99f))
    assertEquals("L 1.2 m", deviationLabel(-1.23f))
  }

  @Test
  fun dropsTheSideAtDeadCentre() {
    // Otherwise the label flickers between L and R while the error hovers around zero.
    assertEquals("0 cm", deviationLabel(0f))
    assertEquals("0 cm", deviationLabel(0.004f))
    assertEquals("0 cm", deviationLabel(-0.004f))
  }
}
