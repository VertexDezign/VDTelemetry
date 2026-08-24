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

    assertEquals("2", data.version)
    assertEquals(1, data.husbandries.size)

    val pen = data.husbandries[0]
    assertEquals("placeablebc4f20e4b1794d29a61be8056cdf319d", pen.id)
    assertEquals("Moderner Kuhstall", pen.name)
    assertEquals(52, pen.numAnimals)
    assertEquals(70, pen.maxNumAnimals)
    assertEquals(1f, pen.productivity)

    // Food is its own list (getFoodInfos), separate from the condition bars — with liters + capacity,
    // and every group sharing the pen's one food capacity.
    assertEquals(4, pen.food.size)
    assertEquals("Totalmischration (100%)", pen.food[0].title)
    assertEquals(0.9031f, pen.food[0].ratio)
    assertEquals(30480, pen.food[0].value)
    assertEquals(33750, pen.food[0].capacity)
    // A food bar is a group summed over several fill types, so it never carries a fill type.
    assertEquals("", pen.food[0].type)

    // Condition bars carry no capacity (only food groups do) -> defaults to 0.
    assertEquals(4, pen.conditions.size)
    assertEquals("Stroh", pen.conditions[1].title)
    assertEquals(0.9384f, pen.conditions[1].ratio)
    assertEquals(20059, pen.conditions[1].value)
    assertEquals(0, pen.conditions[1].capacity)
    // The output bars read inversely (a high level wants emptying); the intake bar does not, and its
    // omitted `inverted` defaults to false.
    assertTrue(pen.conditions[0].inverted)
    assertFalse(pen.conditions[1].inverted)

    assertEquals(19, pen.animals.size)
    val holstein = pen.animals[0]
    assertEquals("Holstein Kuh", holstein.name)
    assertEquals(7, holstein.count)
    assertEquals(22, holstein.age)
    assertEquals(100, holstein.health)
    assertEquals(30, holstein.reproduction)
    assertTrue(holstein.supportsReproduction)
    // A bull omits supportsReproduction -> defaults to false.
    val bull = pen.animals[4]
    assertEquals("Holstein Bulle", bull.name)
    assertEquals(0, bull.reproduction)
    assertFalse(bull.supportsReproduction)

    assertRoundTrips(data)
  }

  /**
   * Channel v2 put a fill type on the condition bars whose liters are real storage, which is what
   * lets the storage channel stop reporting the manure heap behind the barn (it would otherwise be
   * counted twice). The capture is a cow barn, so all four of its bars are typed — the untyped
   * pallet output is covered separately below.
   */
  @Test
  fun conditionBarsCarryTheFillTypeBehindThem() {
    val pen = VdtParser.parseHusbandry(example("basic.json")).husbandries[0]

    // Milk and straw the game would name in the player's language either way; manure and slurry are
    // the ones nothing else in the export names any more, since storage.json leaves the heap out.
    assertEquals(listOf("MILK", "STRAW", "MANURE", "LIQUIDMANURE"), pen.conditions.map { it.type })
    assertEquals("Mist", pen.conditions[2].title)
    assertEquals(3834, pen.conditions[2].value)
    assertEquals("Gülle", pen.conditions[3].title)
    assertEquals(233, pen.conditions[3].value)
  }

  /**
   * Inline JSON, because no captured pen has one: a pallet output's liters are still waiting to
   * become an egg pallet, so the bar stays untyped — nobody's stock until the pallet exists, and it
   * is `storage.json`'s `loosePallets` that counts it then.
   */
  @Test
  fun aPalletOutputBarStaysUntyped() {
    val data =
      VdtParser.parseHusbandry(
        """
        {
          "version": "2",
          "husbandries": [
            {
              "id": "ChickenCoop_1",
              "name": "Hühnerstall",
              "conditions": [
                { "title": "Stroh", "type": "STRAW", "ratio": 0.3, "value": 1500 },
                { "title": "Eier", "ratio": 0.1, "value": 400, "inverted": true }
              ]
            }
          ]
        }
        """.trimIndent(),
      )

    val conditions = data.husbandries[0].conditions
    assertEquals("STRAW", conditions[0].type)
    assertEquals("", conditions[1].type)
    assertTrue(conditions[1].inverted)

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
