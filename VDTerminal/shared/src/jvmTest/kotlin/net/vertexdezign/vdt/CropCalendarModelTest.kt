package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.periodRuns
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/cropCalendar` fixtures through the real server path
 * ([VdtParser.parseCropCalendar]) and asserts the field mapping, the omission defaults, a lossless
 * round-trip, and the two derivations the grid is drawn from ([periodRuns],
 * [CropCalendarData.todayFraction]) — the crop calendar channel's half of the mod↔Kotlin contract.
 *
 * Three real captures, all German-locale:
 *  * `vanilla.json` — a base-game seasonal save, one day per period.
 *  * `modded.json` — a modded map: 38 crops, four cover crops, four days per period, year 2.
 *  * `noSeasons.json` — the same map on `GrowthMode.DAILY`, which is what makes the app's banner
 *    necessary and is the only fixture that shows what the mode actually does to the data.
 */
class CropCalendarModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/cropCalendar/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/cropCalendar/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: CropCalendarData) {
    val encoded = json.encodeToString(CropCalendarData.serializer(), data)
    val decoded = json.decodeFromString(CropCalendarData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesTheVanillaCapture() {
    val data = VdtParser.parseCropCalendar(example("vanilla.json"))

    assertEquals("1", data.version)
    assertEquals("SEASONAL", data.growthMode)
    assertTrue(data.isSeasonal)

    val today = assertNotNull(data.today)
    assertEquals(6, today.period)
    assertEquals(1, today.dayInPeriod)
    assertEquals(1, today.daysPerPeriod)
    assertEquals(1, today.year)

    // Twelve columns, spring-first, labelled by the game's own localisation — the reason the labels
    // cross the wire at all instead of being a table on this side.
    assertEquals(12, data.periods.size)
    assertEquals(listOf("März", "Apr", "Mai"), data.periods.take(3).map { it.label })
    assertEquals("Feb", data.periods.last().label)
    assertEquals(listOf("SPRING", "SPRING", "SPRING", "SUMMER"), data.periods.take(4).map { it.season })
    assertEquals("WINTER", data.periods.last().season)

    assertEquals(26, data.crops.size)
    val cotton = assertNotNull(data.crops.firstOrNull { it.id == "COTTON" })
    assertEquals("Baumwolle", cotton.name)
    assertEquals(listOf(1, 12), cotton.plant)
    assertEquals(listOf(8, 9), cotton.harvest)
    // The wrapped case the grid has to draw as two bars, in real data: cotton sows in March and again
    // in February.
    assertEquals(listOf(1..1, 12..12), cotton.plant.periodRuns())

    // catchCrop is omitted rather than written false — exactly one crop in this capture carries it.
    assertEquals(1, data.crops.count { it.catchCrop })
    assertFalse(cotton.catchCrop)
    assertEquals("OILSEEDRADISH", data.crops.first { it.catchCrop }.id)

    // period 6 of 12, the only day of that period -> centred half a day in
    assertEquals(5.5f / 12f, assertNotNull(data.todayFraction), 1e-6f)

    assertRoundTrips(data)
  }

  @Test
  fun cropsArriveInTheOrderTheGameSortsThem() {
    val data = VdtParser.parseCropCalendar(example("vanilla.json"))
    val names = data.crops.map { it.name }

    assertEquals(listOf("Baumwolle", "Buschbohnen", "Erbsen", "Gerste"), names.take(4))
    // Byte-wise, not locale-aware: "Ölrettich" lands last rather than beside "Oat". That is not a bug
    // to fix here — the mod sorts with the same `<` the game's own calendar frame uses, so the two
    // screens list the crops in the same order, which matters more than dictionary order.
    assertEquals("Ölrettich", names.last())
    assertEquals(names.sorted(), names)
  }

  @Test
  fun parsesTheModdedCapture() {
    val data = VdtParser.parseCropCalendar(example("modded.json"))

    assertTrue(data.isSeasonal)
    assertEquals(38, data.crops.size)
    assertEquals(4, data.crops.count { it.catchCrop })

    val today = assertNotNull(data.today)
    assertEquals(5, today.period)
    assertEquals(4, today.daysPerPeriod)
    assertEquals(2, today.year)
    // Four days to a period, sitting on the first -> an eighth of the way into period 5.
    assertEquals((4f + 0.125f) / 12f, assertNotNull(data.todayFraction), 1e-6f)

    // A map with mod crops is where the wrapped ranges actually show up in bulk.
    val fieldGrass = assertNotNull(data.crops.firstOrNull { it.id == "FIELDGRASS" })
    assertEquals(listOf(1..8, 12..12), fieldGrass.plant.periodRuns())
    assertEquals(listOf(1..9), fieldGrass.harvest.periodRuns())
    val wheat = assertNotNull(data.crops.firstOrNull { it.id == "WHEAT" })
    assertEquals(listOf(1..1, 12..12), wheat.plant.periodRuns())

    assertRoundTrips(data)
  }

  @Test
  fun parsesTheNonSeasonalCapture() {
    val data = VdtParser.parseCropCalendar(example("noSeasons.json"))

    assertEquals("DAILY", data.growthMode)
    assertFalse(data.isSeasonal)

    // The point of the fixture: outside seasonal growth the game answers "yes" for every period, so
    // every crop's bars run the whole year and say nothing. This is what the app's banner is for.
    assertEquals(26, data.crops.size)
    assertTrue(data.crops.all { it.plant == (1..12).toList() && it.harvest == (1..12).toList() })
    assertTrue(data.crops.all { it.plant.periodRuns() == listOf(1..12) })

    assertRoundTrips(data)
  }

  @Test
  fun parsesAnEmptyCalendarWithOmittedArrays() {
    // Inline: every captured crop carries both period lists, so nothing on disk exercises their
    // absence — but the mod omits an empty one rather than writing [], and a crop with no harvest
    // period (a tree) would arrive exactly like this.
    val data = VdtParser.parseCropCalendar("""{ "version": "1", "growthMode": "SEASONAL" }""")

    assertEquals("1", data.version)
    assertNull(data.today)
    assertTrue(data.periods.isEmpty())
    assertTrue(data.crops.isEmpty())
    assertNull(data.todayFraction)
    assertRoundTrips(data)
  }

  @Test
  fun todayFractionSurvivesAZeroDaysPerPeriod() {
    // The mod floors daysPerPeriod at 1, but a corrupt file must not divide by zero here either.
    val data =
      VdtParser.parseCropCalendar(
        """{ "version": "1", "today": { "period": 1, "dayInPeriod": 1, "daysPerPeriod": 0 } }""",
      )
    assertEquals(0.5f / 12f, assertNotNull(data.todayFraction), 1e-6f)
  }

  @Test
  fun periodRunsMergeContiguousPeriodsAndKeepGapsApart() {
    assertEquals(listOf(1..8, 12..12), listOf(1, 2, 3, 4, 5, 6, 7, 8, 12).periodRuns())
    assertEquals(listOf(9..10), listOf(9, 10).periodRuns())
    assertEquals(listOf(3..3), listOf(3).periodRuns())
    assertEquals(emptyList(), emptyList<Int>().periodRuns())
    assertEquals(listOf(1..1, 3..3, 5..5), listOf(1, 3, 5).periodRuns())
  }

  @Test
  fun periodRunsTolerateUnorderedAndDuplicatePeriods() {
    assertEquals(listOf(1..3, 12..12), listOf(12, 2, 1, 3).periodRuns())
    assertEquals(listOf(4..5), listOf(4, 4, 5).periodRuns())
  }

  @Test
  fun cropCalendarRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseCropCalendar(example("vanilla.json"))
    val message: ServerMessage = ServerMessage.CropCalendar(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"cropCalendar\""), "expected the cropCalendar discriminator")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.CropCalendar))
  }

  @Test
  fun cropCalendarCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.CropCalendar(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.CropCalendar).data)
  }
}
