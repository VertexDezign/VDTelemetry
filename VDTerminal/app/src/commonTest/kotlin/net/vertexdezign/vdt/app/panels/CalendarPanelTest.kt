package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.CalendarCrop
import net.vertexdezign.vdt.model.CalendarToday
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.WeatherKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the Calendar screen's controls actually do: which crops survive the search box and the two
 * "now" chips, and the small formatting the strip depends on.
 */
class CalendarPanelTest {
  private val wheat = CalendarCrop(id = "WHEAT", name = "Wheat", plant = listOf(9, 10), harvest = listOf(4, 5))
  private val oat = CalendarCrop(id = "OAT", name = "Oat", plant = listOf(1, 2), harvest = listOf(5, 6))
  private val grass =
    CalendarCrop(
      id = "MEADOW",
      name = "Meadow",
      catchCrop = true,
      plant = listOf(1, 2, 3, 4, 5, 6),
      harvest = listOf(5, 6, 7),
    )

  /** Today is period 5 — where wheat and oat can both be harvested but only grass can be sown. */
  private val data =
    CropCalendarData(
      version = "1",
      growthMode = "SEASONAL",
      today = CalendarToday(period = 5, dayInPeriod = 1, daysPerPeriod = 2),
      crops = listOf(wheat, oat, grass),
    )

  @Test
  fun anEmptyQueryAndNoFiltersKeepEveryCrop() {
    assertEquals(data.crops, filterCrops(data, "", sowNow = false, harvestNow = false))
  }

  @Test
  fun theQueryMatchesTheDisplayNameCaseInsensitivelyAndAnywhere() {
    assertEquals(listOf(wheat), filterCrops(data, "wheat", sowNow = false, harvestNow = false))
    assertEquals(listOf(wheat), filterCrops(data, "HEA", sowNow = false, harvestNow = false))
    assertTrue(filterCrops(data, "  oat  ", sowNow = false, harvestNow = false).contains(oat))
  }

  @Test
  fun theQueryAlsoMatchesTheInternalId() {
    // The row key is the fruit type's internal name; on a non-English client it is often the only
    // spelling the player knows from a mod description.
    assertEquals(listOf(grass), filterCrops(data, "meadow", sowNow = false, harvestNow = false))
  }

  @Test
  fun sowNowKeepsOnlyCropsPlantableInTheCurrentPeriod() {
    assertEquals(listOf(grass), filterCrops(data, "", sowNow = true, harvestNow = false))
  }

  @Test
  fun harvestNowKeepsOnlyCropsHarvestableInTheCurrentPeriod() {
    assertEquals(listOf(wheat, oat, grass), filterCrops(data, "", sowNow = false, harvestNow = true))
  }

  @Test
  fun bothFiltersMeanBothConditions() {
    // Not mutually exclusive: "what comes off and goes straight back in" is one question.
    assertEquals(listOf(grass), filterCrops(data, "", sowNow = true, harvestNow = true))
  }

  @Test
  fun theQueryAndTheFiltersCompose() {
    assertTrue(filterCrops(data, "wheat", sowNow = true, harvestNow = false).isEmpty())
    assertEquals(listOf(wheat), filterCrops(data, "wheat", sowNow = false, harvestNow = true))
  }

  @Test
  fun withNoTodayNothingIsSowableOrHarvestableNow() {
    // No calendar position means no current period; the filters must empty the list rather than
    // matching period 0 against something.
    val undated = data.copy(today = null)
    assertTrue(filterCrops(undated, "", sowNow = true, harvestNow = false).isEmpty())
    assertEquals(undated.crops, filterCrops(undated, "", sowNow = false, harvestNow = false))
  }

  @Test
  fun hoursArePrintedAsAFixed24HourClock() {
    assertEquals("00:00", formatHour(0))
    assertEquals("08:00", formatHour(8))
    assertEquals("23:00", formatHour(23))
  }

  @Test
  fun theWindArrowTurnsHalfATurnPastTheReportedAngleAndTheOtherWayRound() {
    // Half a turn because the exported angle is where the wind comes FROM; negated because the engine
    // measures counter-clockwise and Compose's rotate() turns clockwise. Without the negation the
    // arrow came out mirrored about the vertical axis.
    assertEquals(180f, windArrowRotation(0))
    assertEquals(135f, windArrowRotation(45))
    assertEquals(0f, windArrowRotation(180))
    assertEquals(270f, windArrowRotation(270))
    // Straight down the middle of both corrections: 90 and 270 must land on opposite sides.
    assertEquals(90f, windArrowRotation(90))
  }

  @Test
  fun theWindArrowRotationStaysInsideOneTurn() {
    // .mod, not %: a plain remainder goes negative past 180 and the arrow jumps a turn.
    for (degrees in 0..359) {
      val rotation = windArrowRotation(degrees)
      assertTrue(rotation >= 0f && rotation < 360f, "$degrees -> $rotation")
    }
  }

  @Test
  fun theRowShadeAlternates() {
    // The name column and the bars are laid out separately but must shade the same rows.
    assertEquals(listOf(false, true, false, true), (0..3).map { isStriped(it) })
  }

  @Test
  fun pressingARowSelectsItAndPressingItAgainClearsIt() {
    assertEquals("WHEAT", toggleSelection(null, "WHEAT"))
    assertEquals(null, toggleSelection("WHEAT", "WHEAT"))
    assertEquals("OAT", toggleSelection("WHEAT", "OAT"))
  }

  @Test
  fun theNonSeasonalNoticeNamesTheModeItFound() {
    assertTrue(growthModeNotice("DAILY").contains("Daily"))
    assertTrue(growthModeNotice("DISABLED").contains("disabled"))
    // An unrecognised mode still gets a sentence rather than an empty banner.
    assertTrue(growthModeNotice("SOMETHING_NEW").isNotEmpty())
  }

  @Test
  fun everyWeatherKindHasItsOwnGlyphAndLabel() {
    // The set is told apart by shape alone, so two conditions sharing a glyph would be unreadable.
    val glyphs = WeatherKind.entries.map { WeatherIcons.of(it) }
    for (i in glyphs.indices) {
      for (j in i + 1 until glyphs.size) {
        assertNotEquals(glyphs[i].name, glyphs[j].name, "${WeatherKind.entries[i]} and ${WeatherKind.entries[j]}")
      }
    }
    assertEquals(WeatherKind.entries.size, WeatherKind.entries.map { WeatherIcons.labelOf(it) }.toSet().size)
    assertSame(WeatherIcons.Unknown, WeatherIcons.of(WeatherKind.UNKNOWN))
  }
}
