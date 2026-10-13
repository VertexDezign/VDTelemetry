package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.AutoDriveData
import net.vertexdezign.vdt.model.AutoDriveMode
import net.vertexdezign.vdt.model.AutoDriveTarget
import net.vertexdezign.vdt.model.VdtData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AutoDrive (issue #97, mod version 34 + the `autoDrive.json` channel), read off the captures in
 * `examples/json/autodrive/`: the network, a machine set up for pick-up & deliver but parked, and one
 * driving to a destination.
 */
class AutoDriveModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/autodrive/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/autodrive/$name from ${File(".").absolutePath}")
  }

  private fun telemetry(name: String): VdtData {
    val data = VdtParser.parseJson(example(name))
    assertEquals(data, json.decodeFromString(VdtData.serializer(), json.encodeToString(VdtData.serializer(), data)))
    assertEquals("34", data.version)
    return data
  }

  @Test
  fun decodesAParkedPickUpAndDeliverWithItsTrailersFillTypes() {
    // A John Deere 7810 with a grain trailer, AutoDrive set up but not started.
    val vehicle = assertNotNull(telemetry("tractor_withTrailer.json").vehicle)
    val ad = assertNotNull(vehicle.autoDrive)
    assertFalse(ad.active)
    assertFalse(vehicle.ai?.active == true)
    assertEquals(AutoDriveMode.PICKUP_AND_DELIVER, ad.mode)
    assertEquals(AutoDriveMode.entries.filter { it != AutoDriveMode.OTHER }, ad.modes)
    assertEquals(AutoDriveTarget(2, "Hof"), ad.target)
    assertEquals(8, ad.unloadTarget?.id)
    assertEquals("WHEAT", ad.fillType)
    // A grain trailer is offered every bulk fill type there is — why the panel lists them, not chips.
    val fillTypes = assertNotNull(ad.fillTypes)
    assertTrue(fillTypes.size > 50, "${fillTypes.size} fill types")
    assertTrue(fillTypes.any { it.name == ad.fillType })
    assertEquals(0, ad.loops)
    // Nothing of a drive while parked.
    assertNull(ad.task)
    assertNull(ad.remainingSeconds)
    assertNull(ad.heading)
  }

  @Test
  fun decodesADriveToOnItsWayAndTheHelperLamp() {
    val vehicle = assertNotNull(telemetry("tractor_driving.json").vehicle)
    val ad = assertNotNull(vehicle.autoDrive)
    assertTrue(ad.active)
    assertTrue(vehicle.ai?.active == true, "AutoDrive driving is a helper driving")
    assertEquals(AutoDriveMode.DRIVE_TO, ad.mode)
    assertEquals(AutoDriveTarget(12, "BGA - Gärreste"), ad.target)
    assertEquals(ad.target, ad.heading)
    assertEquals(33, ad.remainingSeconds)
    assertEquals("Fahre zu Zielpunkt", ad.task)
    // Drive-to has neither an unload destination nor a fill type.
    assertNull(ad.unloadTarget)
    assertNull(ad.fillTypes)
  }

  @Test
  fun decodesAPickUpAndDeliverUnderWay() {
    // The 7810 again, now running pick-up & deliver: on its way to the loading point, endless rounds.
    val vehicle = assertNotNull(telemetry("autodrive_pickupAndDeliver.json").vehicle)
    val ad = assertNotNull(vehicle.autoDrive)
    assertTrue(ad.active)
    assertTrue(vehicle.ai?.active == true)
    assertEquals(AutoDriveMode.PICKUP_AND_DELIVER, ad.mode)
    assertEquals(AutoDriveTarget(15, "F50.F"), ad.target)
    assertEquals(ad.target, ad.heading, "on the way to the pick-up, the marker it heads for is the destination")
    assertEquals(8, ad.unloadTarget?.id)
    assertEquals("FORAGE_MIXING", ad.fillType)
    assertTrue(ad.fillTypes.orEmpty().any { it.name == ad.fillType })
    assertEquals(0, ad.loops)
    assertEquals(368, ad.remainingSeconds)
    assertEquals("Fahre zu Aufladepunkt", ad.task)
  }

  @Test
  fun aModeFromALaterAutoDriveReadsAsOtherAndOffersNothing() {
    val data = VdtParser.parseJson("""{"version":"34","vehicle":{"autoDrive":{"mode":"BGA","modes":["DRIVE_TO"]}}}""")
    val ad = assertNotNull(data.vehicle?.autoDrive)
    assertEquals(AutoDriveMode.OTHER, ad.mode)
    assertFalse(ad.mode.hasUnloadTarget)
    assertNull(ad.target)
  }

  @Test
  fun theModesSayWhichRowsTheyUse() {
    assertFalse(AutoDriveMode.DRIVE_TO.hasUnloadTarget)
    assertTrue(AutoDriveMode.UNLOAD.hasUnloadTarget)
    assertFalse(AutoDriveMode.UNLOAD.hasFillType)
    assertTrue(AutoDriveMode.LOAD.hasFillType)
    assertTrue(AutoDriveMode.PICKUP_AND_DELIVER.hasLoops)
    assertFalse(AutoDriveMode.LOAD.hasLoops)
  }

  @Test
  fun decodesTheDestinationsChannel() {
    val data = VdtParser.parseAutoDrive(example("autoDrive.json"))
    assertEquals("1", data.version)
    assertEquals(19, data.destinations.size)
    // AutoDrive's catch-all "All" folder is left out, so an unfiled marker has none.
    assertNull(data.destinations.first { it.name == "Shop" }.folder)
    assertEquals(setOf("Einkauf", "Hof", "Verkauf"), data.destinations.mapNotNull { it.folder }.toSet())
    assertTrue(data.destinations.all { it.posX in 0f..1f && it.posZ in 0f..1f })
    // Both captured machines name markers this list has.
    assertTrue(data.destinations.any { it.id == 12 && it.name == "BGA - Gärreste" })
    assertEquals(
      data,
      json.decodeFromString(AutoDriveData.serializer(), json.encodeToString(AutoDriveData.serializer(), data)),
    )

    // No network yet: the mod omits the empty array.
    assertTrue(VdtParser.parseAutoDrive("""{"version":"1"}""").destinations.isEmpty())
  }

  @Test
  fun theChannelCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.AutoDrive(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)
    assertTrue(encoded.contains("\"type\":\"autoDrive\""), encoded)
    assertNull(
      assertNotNull(json.decodeFromString(ServerMessage.serializer(), encoded) as? ServerMessage.AutoDrive).data,
    )
  }

  @Test
  fun theLoopCounterIsBoundedOnTheWire() {
    assertEquals(99, ClientMessage.SetAutoDriveLoops(99).loops)
    assertFailsWith<IllegalArgumentException> { ClientMessage.SetAutoDriveLoops(100) }
    assertFailsWith<IllegalArgumentException> { ClientMessage.SetAutoDriveLoops(-1) }
  }
}
