package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.ProductionData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/production` fixtures through the real server path
 * ([VdtParser.parseProduction]) and asserts the field mapping, the omission defaults (empty lists,
 * absent output `mode`), and a lossless JSON round-trip — the production channel's half of the
 * mod↔Kotlin contract. Standalone storages are covered by [StorageModelTest].
 */
class ProductionModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/production/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/production/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: ProductionData) {
    val encoded = json.encodeToString(ProductionData.serializer(), data)
    val decoded = json.decodeFromString(ProductionData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicProduction() {
    val data = VdtParser.parseProduction(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(1, data.productionPoints.size)

    val point = data.productionPoints[0]
    assertEquals("BunkerMittel_1", point.id)
    assertEquals("Bunker Mittel", point.name)
    // A real production point (not a factory) — the omitted key falls back to the default.
    assertFalse(point.isFactory)
    assertEquals(2, point.lines.size)
    assertEquals(4, point.storage.size)

    val mist = point.lines[0]
    assertEquals("mist", mist.id)
    assertEquals("Mist", mist.name)
    assertEquals("missingInputs", mist.status)
    assertTrue(mist.enabled)
    assertEquals(360, mist.cyclesPerMonth)
    assertEquals(60, mist.costsPerMonth)

    assertEquals(1, mist.inputs.size)
    val input = mist.inputs[0]
    assertEquals("MANURE", input.type)
    assertEquals("Mist", input.title)
    assertEquals(400, input.amount)
    // Inputs carry no distribution mode.
    assertNull(input.mode)

    assertEquals(1, mist.outputs.size)
    val output = mist.outputs[0]
    assertEquals("FERMENTERMANURE", output.type)
    assertEquals("autoDeliver", output.mode)
    assertFalse(output.sellDirectly)

    // A disabled, inactive line exercises the boolean/enum defaults from the other direction.
    val silage = point.lines[1]
    assertEquals("inactive", silage.status)
    assertFalse(silage.enabled)

    // A storage row joins to a line's input/output by type.
    val manureStore = point.storage.firstOrNull { it.type == "MANURE" }
    assertEquals(0, assertNotNull(manureStore).level)
    assertEquals(20000, manureStore.capacity)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyProductionWithOmittedArrays() {
    // Own-farm-with-nothing / spectator: the mod writes just the version, so the Kotlin defaults must
    // fill the missing productionPoints array.
    val data = VdtParser.parseProduction(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.productionPoints.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun productionRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseProduction(example("basic.json"))
    val message: ServerMessage = ServerMessage.Production(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(
      encoded.contains("\"type\":\"production\""),
      "expected the production discriminator in $encoded",
    )
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Production))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `production.json` is
   * absent (export disabled) and the app clears its overview on it.
   */
  @Test
  fun productionCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Production(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Production).data)
  }
}
