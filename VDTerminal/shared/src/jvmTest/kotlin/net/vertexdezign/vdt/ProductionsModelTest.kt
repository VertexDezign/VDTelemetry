package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.ProductionsData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/productions` fixtures through the real server path
 * ([VdtParser.parseProductions]) and asserts the field mapping, the omission defaults (empty
 * lists, absent output `mode`), and a lossless JSON round-trip — the productions channel's half of
 * the mod↔Kotlin contract.
 */
class ProductionsModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/productions/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/productions/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: ProductionsData) {
    val encoded = json.encodeToString(ProductionsData.serializer(), data)
    val decoded = json.decodeFromString(ProductionsData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicProductions() {
    val data = VdtParser.parseProductions(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(1, data.productionPoints.size)

    val point = data.productionPoints[0]
    assertEquals("BunkerMittel_1", point.id)
    assertEquals("Bunker Mittel", point.name)
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

    assertEquals(1, data.storages.size)
    val silo = data.storages[0]
    assertEquals("Zentrales Güllelager", silo.name)
    assertEquals(1, silo.fills.size)
    assertEquals("LIQUIDMANURE", silo.fills[0].type)
    assertEquals(145000, silo.fills[0].level)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyProductionsWithOmittedArrays() {
    // Own-farm-with-nothing / spectator: the mod writes just the version, so the Kotlin defaults must
    // fill the missing productionPoints / storages arrays.
    val data = VdtParser.parseProductions(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.productionPoints.isEmpty())
    assertTrue(data.storages.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun productionsRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseProductions(example("basic.json"))
    val message: ServerMessage = ServerMessage.Productions(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"productions\""), "expected the productions discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Productions))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `productions.json` is
   * absent (export disabled) and the app clears its overview on it.
   */
  @Test
  fun productionsCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Productions(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Productions).data)
  }
}
