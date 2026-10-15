package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.i18n.de.German
import net.vertexdezign.vdt.app.i18n.en.English
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
    assertEquals("R 14 cm", deviationLabel(English, 0.14f))
    assertEquals("L 14 cm", deviationLabel(English, -0.14f))
    assertEquals("R 99 cm", deviationLabel(English, 0.99f))
    assertEquals("L 1.2 m", deviationLabel(English, -1.23f))
  }

  @Test
  fun readsMetresToOneDecimalOnEveryTarget() {
    // The metre label is built from integer tenths, because Double.toString() differs between the
    // JVM these tests run on and the browser the dashboard runs in — "1.0 m" vs "1 m".
    assertEquals("R 1.0 m", deviationLabel(English, 1f))
    assertEquals("R 1.1 m", deviationLabel(English, 1.14f))
    assertEquals("L 2.0 m", deviationLabel(English, -1.96f))
    // A hair under a metre still rounds up into the metre label rather than reading "R 100 cm".
    assertEquals("R 1.0 m", deviationLabel(English, 0.999f))
  }

  @Test
  fun dropsTheSideAtDeadCentre() {
    // Otherwise the label flickers between L and R while the error hovers around zero.
    assertEquals("0 cm", deviationLabel(English, 0f))
    assertEquals("0 cm", deviationLabel(English, 0.004f))
    assertEquals("0 cm", deviationLabel(English, -0.004f))
  }

  @Test
  fun readsOutInGerman() {
    // The decimal comma, and the compass's Ost where English says East.
    assertEquals("L 1,2 m", deviationLabel(German, -1.23f))
    assertEquals("O", directionFromHeading(German, 90))
    assertEquals("SO", directionFromHeading(German, 135))
  }

  @Test
  fun cutsTheCompassIntoTheGamesFortyFiveDegreeSectors() {
    assertEquals("N", directionFromHeading(English, 0))
    assertEquals("N", directionFromHeading(English, 22))
    assertEquals("NE", directionFromHeading(English, 23))
    assertEquals("E", directionFromHeading(English, 90))
    assertEquals("S", directionFromHeading(English, 180))
    assertEquals("W", directionFromHeading(English, 270))
    assertEquals("NW", directionFromHeading(English, 337))
    assertEquals("N", directionFromHeading(English, 338))
  }
}
