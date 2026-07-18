package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.FieldInfoData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/fieldInfo` fixtures through the real server path
 * ([VdtParser.parseFieldInfo]) and asserts the per-field mapping, the omission defaults (empty
 * `fields`, null optional numbers, absent `cropRotation`), and a lossless JSON round-trip — the
 * fieldInfo channel's half of the mod↔Kotlin contract.
 */
class FieldInfoModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/fieldInfo/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/fieldInfo/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: FieldInfoData) {
    val encoded = json.encodeToString(FieldInfoData.serializer(), data)
    val decoded = json.decodeFromString(FieldInfoData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicFieldInfo() {
    val data = VdtParser.parseFieldInfo(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(2, data.fields.size)

    // Growing field with the CropRotation rows attached (mod installed).
    val maize = data.fields[0]
    assertEquals(49, maize.id)
    assertEquals("Maize", maize.crop)
    assertEquals(6, maize.growthState)
    assertEquals(7, maize.maxGrowthState)
    assertEquals("growing", maize.growth)
    assertEquals(12, maize.yieldBonusPercent)
    assertEquals(100, maize.sprayLevelPercent)
    assertTrue(maize.needsPlowing)
    assertTrue(!maize.needsLime && !maize.needsRolling)
    assertEquals("", maize.weed)
    val cr = assertNotNull(maize.cropRotation)
    assertEquals("Beetroot", cr.lastCrop)
    assertEquals("Potato", cr.prevCrop)
    assertEquals(115, cr.yieldPercent)
    // Explicit null catch crop -> "no catch crop".
    assertNull(cr.catchCrop)

    // Harvest-ready field, no CropRotation block (mod absent for this entry), yield bonus omitted.
    val wheat = data.fields[1]
    assertEquals(7, wheat.id)
    assertEquals("Wheat", wheat.crop)
    assertEquals("readyToHarvest", wheat.growth)
    assertNull(wheat.yieldBonusPercent)
    assertEquals(50, wheat.sprayLevelPercent)
    assertEquals("Weeds (light)", wheat.weed)
    assertNull(wheat.cropRotation)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyFieldInfoWithOmittedArray() {
    // The mod omits an empty `fields` array (the Json encoder can't distinguish [] from {}), so the
    // Kotlin default must fill in — "channel live but nothing to show yet".
    val data = VdtParser.parseFieldInfo(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.fields.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun fieldInfoRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseFieldInfo(example("basic.json"))
    val message: ServerMessage = ServerMessage.FieldInfo(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"fieldInfo\""), "expected the fieldInfo discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.FieldInfo))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `fieldInfo.json` is
   * absent (export disabled) and the app drops the agronomy rows back to the map geometry alone.
   */
  @Test
  fun fieldInfoCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.FieldInfo(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.FieldInfo).data)
  }
}
