package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.FillUnit
import kotlin.test.Test
import kotlin.test.assertEquals

private fun unit(
  type: String? = "WHEAT",
  title: String = "Wheat",
  value: Float = 0f,
  capacity: Int = 0,
  percent: Int = 0,
) = FillUnit(value = value, type = type, title = title, capacity = capacity, fillLevelPercentage = percent)

/**
 * [mergeFillUnits] is what the panel's merge toggle runs: several trailers' worth of fill units
 * collapsed to one bar per fill type. Every number in the result is derived rather than copied, and a
 * mistake here reads as a plausible bar rather than an error — which is why it is tested directly.
 */
class MergeFillUnitsTest {
  @Test
  fun unitsOfOneTypeBecomeOneBarSummingBothLevelAndCapacity() {
    // The bug this arithmetic was fixed for: summing the level but not the capacity made a
    // two-trailer 37000/18500 read as permanently full.
    val merged =
      mergeFillUnits(
        listOf(
          unit(value = 18500f, capacity = 18500, percent = 100),
          unit(value = 18500f, capacity = 18500, percent = 100),
        ),
      )

    val wheat = merged.single()
    assertEquals(37000f, wheat.value)
    assertEquals(37000, wheat.capacity)
    assertEquals(100, wheat.fillLevelPercentage)
  }

  @Test
  fun thePercentageComesFromTheTotalsNotFromTheReportedOnes() {
    // A full small tank beside an empty big one is not "50% full" — it is a quarter of the capacity.
    val merged =
      mergeFillUnits(
        listOf(
          unit(value = 1000f, capacity = 1000, percent = 100),
          unit(value = 0f, capacity = 3000, percent = 0),
        ),
      )

    assertEquals(25, merged.single().fillLevelPercentage)
  }

  @Test
  fun differentTypesStayApartAndKeepTheOrderTheyCameIn() {
    val merged =
      mergeFillUnits(
        listOf(
          unit(type = "DIESEL", title = "Diesel", value = 100f, capacity = 400),
          unit(type = "WHEAT", title = "Wheat", value = 500f, capacity = 1000),
          unit(type = "DIESEL", title = "Diesel", value = 100f, capacity = 400),
        ),
      )

    assertEquals(listOf("DIESEL", "WHEAT"), merged.map { it.type })
    assertEquals(200f, merged.first().value)
    assertEquals(800, merged.first().capacity)
  }

  @Test
  fun unitsWithoutATypeGroupByTitleInstead() {
    val merged =
      mergeFillUnits(
        listOf(
          unit(type = null, title = "Bale net", value = 1f),
          unit(type = "  ", title = "Bale net", value = 2f),
          unit(type = null, title = "Twine", value = 5f),
        ),
      )

    assertEquals(listOf("Bale net", "Twine"), merged.map { it.title })
    assertEquals(3f, merged.first().value) // a blank type falls back to the title, same as a null one
  }

  @Test
  fun capacitylessUnitsAverageTheReportedPercentagesInstead() {
    // Pass-through units report no capacity at all, so there is no ratio to derive — the percentages
    // they report are all there is.
    val merged =
      mergeFillUnits(
        listOf(
          unit(value = 1f, capacity = 0, percent = 100),
          unit(value = 1f, capacity = 0, percent = 50),
        ),
      )

    assertEquals(75, merged.single().fillLevelPercentage)
    assertEquals(0, merged.single().capacity)
  }

  @Test
  fun aSingleUnitPassesThroughUnchanged() {
    // Merging is on or off for the whole panel, so it also runs over rigs with nothing to merge.
    val one = unit(value = 250f, capacity = 1000, percent = 25)
    assertEquals(listOf(one), mergeFillUnits(listOf(one)))
  }

  @Test
  fun aReportedPercentageThatDisagreesWithTheLevelIsRecomputed() {
    // Derived, never copied: the game's own percentage is ignored wherever a ratio can be had.
    val merged = mergeFillUnits(listOf(unit(value = 250f, capacity = 1000, percent = 99)))
    assertEquals(25, merged.single().fillLevelPercentage)
  }

  @Test
  fun mergingNothingGivesNothing() {
    assertEquals(emptyList(), mergeFillUnits(emptyList()))
  }
}
