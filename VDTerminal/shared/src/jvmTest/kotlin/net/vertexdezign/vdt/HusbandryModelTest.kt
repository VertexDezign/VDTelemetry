package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.HusbandriesData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/husbandry` fixtures through the real server path
 * ([VdtParser.parseHusbandry]) and asserts the field mapping, the omission defaults, and a lossless
 * round-trip — the husbandry channel's half of the mod↔Kotlin contract.
 */
class HusbandryModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/husbandry/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/husbandry/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: HusbandriesData) {
    val encoded = json.encodeToString(HusbandriesData.serializer(), data)
    val decoded = json.decodeFromString(HusbandriesData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicHusbandry() {
    val data = VdtParser.parseHusbandry(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(1, data.husbandries.size)

    val pen = data.husbandries[0]
    assertEquals("CowBarn_1", pen.id)
    assertEquals("Cow Barn (medium)", pen.name)
    assertEquals(12, pen.numAnimals)
    assertEquals(20, pen.maxNumAnimals)
    assertEquals(0.82f, pen.productivity)

    // Food is its own list (getFoodInfos), separate from the condition bars — with liters + capacity.
    assertEquals(2, pen.food.size)
    assertEquals("Total Mixed Ration (100%)", pen.food[0].title)
    assertEquals(0.55f, pen.food[0].ratio)
    assertEquals(2750, pen.food[0].value)
    assertEquals(5000, pen.food[0].capacity)

    assertEquals(4, pen.conditions.size)
    assertEquals("Water", pen.conditions[0].title)
    assertEquals(0.9f, pen.conditions[0].ratio)
    assertEquals(4500, pen.conditions[0].value)
    // Condition bars carry no capacity (only food groups do) -> defaults to 0.
    assertEquals(0, pen.conditions[0].capacity)
    assertFalse(pen.conditions[0].inverted)
    // The manure output bar is inverted; the omitted `inverted` on the others defaults to false.
    assertTrue(pen.conditions[3].inverted)

    assertEquals(2, pen.animals.size)
    val holstein = pen.animals[0]
    assertEquals("Holstein", holstein.name)
    assertEquals(8, holstein.count)
    assertEquals(24, holstein.age)
    assertEquals(95, holstein.health)
    assertEquals(60, holstein.reproduction)
    assertTrue(holstein.supportsReproduction)
    // The calf omits reproduction fields -> defaults (0 / false).
    val calf = pen.animals[1]
    assertEquals(0, calf.reproduction)
    assertFalse(calf.supportsReproduction)

    assertRoundTrips(data)
  }

  /**
   * Channel v2 put a fill type on the condition bars whose liters are real storage, which is what
   * lets the storage channel stop reporting the manure heap behind the barn (it would otherwise be
   * counted twice). Inline JSON rather than a fixture: `basic.json` predates v2, and a capture with
   * a heap wired into a pen is still wanted — see FUTURE.md.
   */
  @Test
  fun conditionBarsCarryTheFillTypeBehindThem() {
    val data =
      VdtParser.parseHusbandry(
        """
        {
          "version": "2",
          "husbandries": [
            {
              "id": "CowBarn_1",
              "name": "Kuhstall (mittel)",
              "food": [
                { "title": "Gras (30%)", "ratio": 0.2, "value": 1000, "capacity": 5000 }
              ],
              "conditions": [
                { "title": "Stroh", "type": "STRAW", "ratio": 0.3, "value": 1500 },
                { "title": "Mist", "type": "MANURE", "ratio": 0.42, "value": 3111, "inverted": true },
                { "title": "Gülle", "type": "LIQUIDMANURE", "ratio": 0.7, "value": 21000, "inverted": true },
                { "title": "Eier", "ratio": 0.1, "value": 400, "inverted": true }
              ]
            }
          ]
        }
        """.trimIndent(),
      )

    val conditions = data.husbandries[0].conditions
    // The pen's own store: these are the liters nothing else in the export names any more.
    assertEquals("STRAW", conditions[0].type)
    assertEquals("MANURE", conditions[1].type)
    assertEquals(3111, conditions[1].value)
    assertEquals("LIQUIDMANURE", conditions[2].type)
    // A pallet output stays untyped: those liters are still waiting to become an egg pallet, and it
    // is the pallet on the ground that storage.json counts, once it exists.
    assertEquals("", conditions[3].type)
    // A food bar is a group over several fill types, so it never carries one either.
    assertEquals("", data.husbandries[0].food[0].type)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyHusbandryWithOmittedArray() {
    val data = VdtParser.parseHusbandry(example("empty.json"))
    assertEquals("1", data.version)
    assertTrue(data.husbandries.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun husbandryRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseHusbandry(example("basic.json"))
    val message: ServerMessage = ServerMessage.Husbandry(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"husbandry\""), "expected the husbandry discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Husbandry))
  }

  @Test
  fun husbandryCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Husbandry(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Husbandry).data)
  }
}
