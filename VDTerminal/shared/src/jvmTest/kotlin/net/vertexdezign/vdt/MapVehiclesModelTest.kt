package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.MapVehiclesData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/mapVehicles` fixtures through the real server path
 * ([VdtParser.parseMapVehicles]) and asserts the field mapping, the omission defaults (the
 * enterable flags are absent for non-enterables), and a lossless JSON round-trip — the vehicle
 * channel's half of the mod↔Kotlin contract.
 */
class MapVehiclesModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/mapVehicles/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/mapVehicles/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: MapVehiclesData) {
    val encoded = json.encodeToString(MapVehiclesData.serializer(), data)
    val decoded = json.decodeFromString(MapVehiclesData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicVehicles() {
    val data = VdtParser.parseMapVehicles(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(3, data.vehicles.size)

    // The locally driven tractor: the app hides it behind the player marker.
    val tractor = data.vehicles[0]
    assertEquals("tractor", tractor.type)
    assertEquals("Valtra T195 Active", tractor.name)
    assertEquals(0.45325f, tractor.posX)
    assertEquals(0.42799f, tractor.posZ)
    assertEquals(91, tractor.heading)
    assertEquals(1, tractor.farmId)
    assertTrue(tractor.isControlled)
    assertTrue(tractor.isEntered)
    assertFalse(tractor.isAI)

    // Another farm's AI harvester.
    val harvester = data.vehicles[1]
    assertEquals("harvester", harvester.type)
    assertEquals(2, harvester.farmId)
    assertTrue(harvester.isAI)
    assertFalse(harvester.isControlled)
    assertFalse(harvester.isEntered)

    // A parked trailer: not enterable, so the mod omits the flags and the defaults fill in.
    val trailer = data.vehicles[2]
    assertEquals("trailer", trailer.type)
    assertFalse(trailer.isControlled)
    assertFalse(trailer.isEntered)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyVehiclesWithOmittedArray() {
    // The mod omits an empty `vehicles` array (the Json encoder can't distinguish [] from {}).
    val data = VdtParser.parseMapVehicles(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.vehicles.isEmpty())
    assertRoundTrips(data)
  }

  /**
   * Version 2's players, inline until a multiplayer capture replaces it (FUTURE.md → "Captures
   * wanted as fixtures"): one on foot and one seated, joined to the vehicle by its id.
   */
  @Test
  fun parsesOtherPlayers() {
    val data =
      VdtParser.parseMapVehicles(
        """
        {
          "version": "2",
          "vehicles": [
            { "id": 42, "type": "tractor", "name": "Valtra T195 Active", "posX": 0.5, "posZ": 0.25,
              "heading": 91, "farmId": 2, "isAI": false, "isControlled": true, "isEntered": false }
          ],
          "players": [
            { "name": "Grisu118", "posX": 0.1, "posZ": 0.2, "heading": 270, "farmId": 1 },
            { "name": "Farmhand", "posX": 0.5, "posZ": 0.25, "heading": 91, "vehicleId": 42 }
          ]
        }
        """.trimIndent(),
      )

    assertEquals(42, data.vehicles.single().id)
    val (walker, driver) = data.players
    assertEquals("Grisu118", walker.name)
    assertEquals(270, walker.heading)
    assertEquals(1, walker.farmId)
    assertTrue(walker.isOnFoot)
    // The spectator farm is omitted, and the seat names the vehicle drawn there.
    assertNull(driver.farmId)
    assertEquals(data.vehicles.single().id, driver.vehicleId)
    assertFalse(driver.isOnFoot)
    assertRoundTrips(data)
  }

  @Test
  fun versionOneHasNoIdsAndNoPlayers() {
    val data = VdtParser.parseMapVehicles(example("basic.json"))

    assertTrue(data.players.isEmpty())
    assertTrue(data.vehicles.all { it.id == null })
  }

  @Test
  fun vehiclesRideTheServerMessageDiscriminator() {
    val data = VdtParser.parseMapVehicles(example("basic.json"))
    val message: ServerMessage = ServerMessage.MapVehicles(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"mapVehicles\""), "expected the mapVehicles discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.MapVehicles))
  }

  /** "File gone" must cross the wire so the app clears its markers (same rule as the map channel). */
  @Test
  fun vehiclesCarryTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.MapVehicles(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.MapVehicles).data)
  }
}
