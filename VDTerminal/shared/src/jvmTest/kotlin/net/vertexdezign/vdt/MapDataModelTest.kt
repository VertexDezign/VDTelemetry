package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.MapData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/map` fixtures through the real server path
 * ([VdtParser.parseMap]) and asserts the field mapping, the omission defaults (empty arrays, null
 * owners, absent polygon), and a lossless JSON round-trip — the map channel's half of the
 * mod↔Kotlin contract.
 */
class MapDataModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/map/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/map/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: MapData) {
    val encoded = json.encodeToString(MapData.serializer(), data)
    val decoded = json.decodeFromString(MapData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicMap() {
    val data = VdtParser.parseMap(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(2048f, data.terrainSize)

    assertEquals(2, data.pois.size)
    val mill = data.pois[0]
    assertEquals("unloading", mill.type)
    assertEquals("Grain Mill", mill.name)
    assertEquals(0.51234f, mill.posX)
    assertEquals(0.33417f, mill.posZ)
    assertEquals(1, mill.ownerFarmId)

    // The shop omits name and ownerFarmId -> defaults fill in.
    val shop = data.pois[1]
    assertEquals("shop", shop.type)
    assertEquals("", shop.name)
    assertNull(shop.ownerFarmId)

    assertEquals(2, data.fields.size)
    val owned = data.fields[0]
    assertEquals(7, owned.id)
    assertEquals("7", owned.name)
    assertEquals(7, owned.farmlandId)
    assertEquals(1, owned.ownerFarmId)
    assertEquals(2.31f, owned.areaHa)
    assertEquals(0.41f, owned.labelX)
    assertEquals(0.52f, owned.labelZ)
    // Flat [x1,z1,...] outline: 4 points = 8 floats.
    assertEquals(8, owned.polygon.size)
    assertEquals(0.4021f, owned.polygon[0])
    assertEquals(0.5013f, owned.polygon[1])

    // Unowned field without polygon -> null owner, empty outline.
    val unowned = data.fields[1]
    assertEquals(12, unowned.id)
    assertNull(unowned.ownerFarmId)
    assertTrue(unowned.polygon.isEmpty())

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyMapWithOmittedArrays() {
    // The mod omits empty `pois`/`fields` arrays (the Json encoder can't distinguish [] from {}),
    // so the Kotlin defaults must fill in. "Loaded but nothing to show" — e.g. a map without fields.
    val data = VdtParser.parseMap(example("empty.json"))

    assertEquals("1", data.version)
    assertEquals(2048f, data.terrainSize)
    assertTrue(data.pois.isEmpty())
    assertTrue(data.fields.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun mapRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseMap(example("basic.json"))
    val message: ServerMessage = ServerMessage.MapUpdate(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"map\""), "expected the map discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.MapUpdate))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `map.json` is absent
   * (export disabled) and the app clears its overlays on it. If `data` were non-nullable the null
   * could never be broadcast and stale overlays would stick forever.
   */
  @Test
  fun mapCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.MapUpdate(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.MapUpdate).data)
  }
}
