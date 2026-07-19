package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.StorageData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/storage` fixtures through the real server path
 * ([VdtParser.parseStorage]) and asserts the field mapping, the omission defaults (empty lists), and a
 * lossless JSON round-trip — the storage channel's half of the mod↔Kotlin contract. Production points
 * are covered by [ProductionModelTest].
 */
class StorageModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/storage/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/storage/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: StorageData) {
    val encoded = json.encodeToString(StorageData.serializer(), data)
    val decoded = json.decodeFromString(StorageData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicStorage() {
    val data = VdtParser.parseStorage(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(2, data.storages.size)

    val silo = data.storages[0]
    assertEquals("Zentrales Güllelager", silo.name)
    // No `kind` in the fixture -> the liter-silo default.
    assertEquals("fill", silo.kind)
    assertEquals(1, silo.fills.size)
    assertEquals("LIQUIDMANURE", silo.fills[0].type)
    assertEquals(145000, silo.fills[0].level)

    // Object storage: count-based, with a per-type breakdown instead of liter fills.
    val barn = data.storages[1]
    assertEquals("object", barn.kind)
    assertEquals(32, barn.count)
    assertEquals(250, barn.capacity)
    assertEquals(25, barn.maxUnloadAmount)
    assertTrue(barn.fills.isEmpty())
    assertEquals(2, barn.objects.size)
    assertEquals(1, barn.objects[0].index)
    assertEquals("Round bale (Straw)", barn.objects[0].title)
    assertEquals(20, barn.objects[0].count)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyStorageWithOmittedArrays() {
    // Own-farm-with-nothing / spectator: the mod writes just the version, so the Kotlin defaults must
    // fill the missing storages array.
    val data = VdtParser.parseStorage(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.storages.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun storageRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseStorage(example("basic.json"))
    val message: ServerMessage = ServerMessage.Storage(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(
      encoded.contains("\"type\":\"storage\""),
      "expected the storage discriminator in $encoded",
    )
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Storage))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `storage.json` is absent
   * (export disabled) and the app clears its overview on it.
   */
  @Test
  fun storageCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Storage(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Storage).data)
  }
}
