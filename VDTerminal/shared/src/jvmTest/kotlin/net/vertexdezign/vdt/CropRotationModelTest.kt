package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.CropRotationData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/cropRotation` fixtures through the real server path
 * ([VdtParser.parseCropRotation]) and asserts the field mapping, the omitted-`rotations` case, and a
 * lossless JSON round-trip — the cropRotation channel's half of the mod↔Kotlin contract.
 */
class CropRotationModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/cropRotation/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/cropRotation/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: CropRotationData) {
    val encoded = json.encodeToString(CropRotationData.serializer(), data)
    val decoded = json.decodeFromString(CropRotationData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicCropRotation() {
    val data = VdtParser.parseCropRotation(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(2, data.rotations.size)

    val heavy = data.rotations[0]
    assertEquals(1, heavy.index)
    assertEquals("Heavy Soil", heavy.name)
    assertEquals(1, heavy.farmId)
    assertEquals(3, heavy.sequence.size)

    val wheat = heavy.sequence[0]
    assertEquals(3, wheat.state)
    assertEquals("Wheat", wheat.crop)
    assertEquals(0, wheat.catchCropState)

    // Middle step carries a catch crop.
    val canola = heavy.sequence[1]
    assertEquals("Canola", canola.crop)
    assertEquals(2, canola.catchCropState)
    assertEquals("Oilseed Radish", canola.catchCrop)

    // Fallow step: state 0.
    assertEquals(0, heavy.sequence[2].state)
    assertEquals("Fallow", heavy.sequence[2].crop)

    assertEquals("Root Crops", data.rotations[1].name)
    assertEquals(2, data.rotations[1].sequence.size)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyCropRotationWithOmittedRotations() {
    // The mod omits an empty `rotations` array (the Json encoder can't distinguish [] from {}), so the
    // Kotlin default fills in. "Installed but no plans" — distinct from the mod not being present.
    val data = VdtParser.parseCropRotation(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.rotations.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun cropRotationRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseCropRotation(example("basic.json"))
    val message: ServerMessage = ServerMessage.CropRotation(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"cropRotation\""), "expected the cropRotation discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.CropRotation))
  }
}
