package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.BaleDoor
import net.vertexdezign.vdt.model.BaleUnloadAction
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.VdtData
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WrapperState
import net.vertexdezign.vdt.model.at
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The baler and bale-wrapper aspects (mod version 26) over the nine singleplayer captures in
 * `examples/json/telemetry/vanilla/baler/`:
 *
 * - `roundBaler_filling`, `roundBaler_Opening`, `roundBaler_open` — one KRONE VariPack towed by an
 *   MB-trac, through a whole drop: filling, door opening on a finished bale, door open and emptied;
 * - `selfDrivingRoundBaler` — a Vermeer ZR5, the baler *being* the vehicle;
 * - `squareBaler` — a KRONE BiG Pack with two bales in its channel, collector not configured;
 * - `roundBaler_wrapperCombo_wrapping` — a John Deere C441R baler-wrapper, one machine carrying both
 *   aspects;
 * - `squareBaler_collector` — a KRONE BiG Pack 1290 HDP VC (`balerLoader`) with its bale collector;
 * - `stationaryBalerWrapper` — a GÖWEIL VARIO-Master V140: a *stationary* baler-wrapper fed from a
 *   buffer, the capture of a non-stop [net.vertexdezign.vdt.model.BalerBuffer]. No platform baler has
 *   been captured;
 * - `wrapper_loading`, `wrapper_wrapping` — a standalone GÖWEIL G5020.
 *
 * `consumable` and `collector` are only on the three recaptured after they were added:
 * `squareBaler_collector`, `stationaryBalerWrapper` and `selfDrivingRoundBaler`.
 */
class BalerModelTest {
  private val json = Json { encodeDefaults = true }

  private fun capture(name: String): Vehicle {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/telemetry/vanilla/baler/$name.json")
      if (candidate.exists()) {
        val data = VdtParser.parseJson(candidate.readText())
        val encoded = json.encodeToString(VdtData.serializer(), data)
        assertEquals(data, json.decodeFromString(VdtData.serializer(), encoded), "JSON round-trip should be lossless")
        return assertNotNull(data.vehicle)
      }
      dir = dir.parentFile
    }
    error("Could not locate the baler capture $name")
  }

  /** The towed machine behind the tractor. */
  private fun towed(name: String): Implement = capture(name).implement.single()

  @Test
  fun aRoundBalerFillingPointsAtItsChamber() {
    val machine = towed("roundBaler_filling")
    val baler = assertNotNull(machine.baler)
    assertTrue(baler.round)
    assertEquals(BaleDoor.CLOSED, baler.door)
    assertTrue(baler.bales.isEmpty())
    assertNull(baler.unload, "auto-drop off and the bale not finished: the drop key offers nothing")

    val chamber = assertNotNull(baler.chamberIn(machine.fillUnits))
    assertEquals("STRAW", chamber.type)
    assertEquals(5000, chamber.capacity)

    assertEquals(listOf(1.25, 1.5, 1.8), baler.baleTypes.map { it.diameter })
    assertEquals(1.25, baler.currentBaleType?.diameter)
    assertNull(baler.nextBaleType)
    assertEquals(false, baler.autoDrop?.on)
    assertEquals(true, baler.autoDrop?.canToggle)
    assertNull(baler.platform)
    assertNull(baler.buffer)
  }

  @Test
  fun theFinishedBaleWaitsBehindTheOpeningDoor() {
    val baler = assertNotNull(towed("roundBaler_Opening").baler)
    assertEquals(BaleDoor.OPENING, baler.door)
    val waiting = baler.bales.single()
    assertNull(waiting.position, "a round bale travels no channel")
    assertNull(baler.unload, "a moving door offers no key")
  }

  @Test
  fun anOpenDoorOffersClose() {
    val machine = towed("roundBaler_open")
    val baler = assertNotNull(machine.baler)
    assertEquals(BaleDoor.OPEN, baler.door)
    assertEquals(BaleUnloadAction.CLOSE, baler.unload)
    assertTrue(baler.bales.isEmpty(), "the bale is on the ground")
    assertEquals(0f, baler.chamberIn(machine.fillUnits)?.value)
    assertEquals(1, machine.baleCounter?.session)
  }

  @Test
  fun aSelfPropelledBalerIsTheVehicleAndItsDieselIsNotTheChamber() {
    val vehicle = capture("selfDrivingRoundBaler")
    assertTrue(vehicle.implement.isEmpty())
    val baler = assertNotNull(vehicle.baler)
    assertTrue(baler.working)
    // The engine's chamber is not its first fill unit on a machine with a motor; the export's is.
    assertEquals("GRASS_WINDROW", baler.chamberIn(vehicle.fillUnits)?.type)
  }

  @Test
  fun aSquareBalerHasBalesInItsChannelAndNoDoor() {
    val machine = towed("squareBaler")
    val baler = assertNotNull(machine.baler)
    assertFalse(baler.round)
    assertNull(baler.door)
    assertNull(baler.autoDrop, "nothing to drop by hand: bales leave the channel as the next one pushes")
    assertNull(baler.unload)

    val positions = baler.bales.map { assertNotNull(it.position) }
    assertEquals(2, positions.size)
    assertTrue(positions.all { it in 0.0..1.0 })
    assertTrue(positions[0] > positions[1], "the older bale is further along")

    assertEquals(2, baler.baleType)
    assertEquals(2.2, baler.currentBaleType?.length)
    assertEquals(listOf(1.8, 2.2, 2.4), baler.baleTypes.map { it.length })
    assertTrue(baler.baleTypes.all { it.diameter == null })
    assertEquals("STRAW", baler.chamberIn(machine.fillUnits)?.type)
  }

  @Test
  fun theCollectorCountsItsBalesInAFillUnit() {
    val machine = towed("squareBaler_collector")
    assertEquals("balerLoader", machine.type)
    val baler = assertNotNull(machine.baler)
    val rack = assertNotNull(machine.fillUnits.at(assertNotNull(baler.collector).fillUnit))
    assertEquals("SQUAREBALE", rack.type)
    assertEquals(1f, rack.value)
    assertEquals(3, rack.capacity)
    assertEquals(2, baler.bales.size, "and two more still in the channel")
    assertEquals("BALE_TWINE", baler.consumableIn(machine.fillUnits)?.type)
    assertNull(towed("squareBaler").baler?.collector, "that BiG Pack has no collector configured")
  }

  @Test
  fun theNetIsFoundWhereItsFillUnitHasNoType() {
    // The case `consumable` exists for: the VARIO-Master exports its net roll with no fill type.
    val machine = towed("stationaryBalerWrapper")
    val net = assertNotNull(assertNotNull(machine.baler).consumableIn(machine.fillUnits))
    assertEquals("", net.type)
    assertEquals(2, net.capacity)
    val film = assertNotNull(assertNotNull(machine.baleWrapper).consumableIn(machine.fillUnits))
    assertEquals("BALE_WRAP", film.type)

    val zr5 = capture("selfDrivingRoundBaler")
    assertEquals("BALE_NET", zr5.baler?.consumableIn(zr5.fillUnits)?.type)
  }

  @Test
  fun aBalerWrapperIsOneMachineWithBothAspects() {
    val machine = towed("roundBaler_wrapperCombo_wrapping")
    assertEquals("balerWrapper", machine.type)
    val baler = assertNotNull(machine.baler)
    val wrapper = assertNotNull(machine.baleWrapper)
    assertEquals(WrapperState.WRAPPING, wrapper.state)
    assertTrue(wrapper.round)
    val progress = assertNotNull(wrapper.progress)
    assertTrue(progress > 0.0 && progress < 1.0)
    assertFalse(wrapper.canDrop)
    // Two auto-drops, set independently: the chamber hands its bale to the table by itself, the table
    // waits for the driver.
    assertEquals(true, baler.autoDrop?.on)
    assertFalse(wrapper.autoDrop.on)
  }

  @Test
  fun theStationaryBalerFeedsFromItsBuffer() {
    val machine = towed("stationaryBalerWrapper")
    assertEquals("balerStationary", machine.type)
    val baler = assertNotNull(machine.baler)
    val buffer = assertNotNull(baler.buffer)
    assertTrue(buffer.overloading)
    assertEquals("CHAFF", machine.fillUnits.at(buffer.fillUnit)?.type)
    assertEquals("ROUNDBALE_GRASS", baler.chamberIn(machine.fillUnits)?.type)
    assertNull(baler.platform)
    assertEquals(false, baler.autoDrop?.canToggle)
    assertEquals(WrapperState.WRAPPING, machine.baleWrapper?.state)
  }

  @Test
  fun aStandaloneWrapperHasNoBaler() {
    val loading = towed("wrapper_loading")
    assertNull(loading.baler)
    assertNull(loading.baleCounter)
    val wrapper = assertNotNull(loading.baleWrapper)
    assertEquals(WrapperState.LOADING, wrapper.state)
    assertNull(wrapper.progress)

    val wrapping = assertNotNull(towed("wrapper_wrapping").baleWrapper)
    assertEquals(WrapperState.WRAPPING, wrapping.state)
    assertTrue(assertNotNull(wrapping.progress) in 0.0..1.0)
    assertTrue(wrapping.autoDrop.on)
  }
}
