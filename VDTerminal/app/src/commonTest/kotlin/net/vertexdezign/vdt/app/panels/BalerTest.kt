package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.Bale
import net.vertexdezign.vdt.model.BaleCollector
import net.vertexdezign.vdt.model.BaleDoor
import net.vertexdezign.vdt.model.BaleType
import net.vertexdezign.vdt.model.BaleUnloadAction
import net.vertexdezign.vdt.model.BaleWrapper
import net.vertexdezign.vdt.model.Baler
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FillUnits
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WrapperState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The baler screen's logic away from Compose: what the picture shows, what the header says, and the
 * labels that turn the export's numbers into words. The machines are shaped like the committed
 * captures in `examples/json/telemetry/vanilla/baler/` (which `BalerModelTest` reads for real).
 */
class BalerTest {
  private val round = listOf(BaleType(diameter = 1.25, width = 1.2), BaleType(diameter = 1.5, width = 1.2))
  private val square = listOf(
    BaleType(width = 1.2, height = 0.9, length = 1.8),
    BaleType(width = 1.2, height = 0.9, length = 2.2),
  )

  private fun machine(
    baler: Baler? = null,
    wrapper: BaleWrapper? = null,
    units: List<FillUnit> = emptyList(),
    lowered: Boolean? = true,
  ): IsoBusMachine = Implement(
    position = "BACK",
    name = "Baler",
    lowered = lowered,
    isTurnedOn = true,
    fillUnits = FillUnits(units),
    baler = baler,
    baleWrapper = wrapper,
  ).isoBus()

  private val straw = FillUnit(value = 968f, type = "STRAW", capacity = 5000, fillLevelPercentage = 19)

  @Test
  fun aBalerOrAWrapperOpensTheSection() {
    assertTrue(machine(baler = Baler(round = true)).hasSection)
    assertTrue(machine(wrapper = BaleWrapper()).hasSection)
    assertFalse(machine().hasSection)
    val rig = Vehicle(implement = listOf(Implement(position = "BACK", name = "Wrapper", baleWrapper = BaleWrapper())))
    assertEquals("Wrapper", isoBusMachine(rig, null)?.name)
  }

  @Test
  fun theRoundBaleIsDrawnByItsArea() {
    val p =
      assertNotNull(
        balerPicture(machine(Baler(round = true, fillUnit = 1, door = BaleDoor.CLOSED), units = listOf(straw))),
      )
    assertEquals(BalerKind.ROUND, p.kind)
    assertEquals(968f / 5000f, p.fill, 1e-4f)
    // Half full is half the AREA: the radius is √0.5, not 0.5.
    assertEquals(0.7071f, roundBaleRadius(0.5f), 1e-3f)
    assertFalse(p.doorOpen)
    assertFalse(p.baleInChamber)
  }

  @Test
  fun theDoorOpensOnAWaitingBaleAndTheBaleLandsBehind() {
    val opening =
      assertNotNull(balerPicture(machine(Baler(round = true, door = BaleDoor.OPENING, bales = listOf(Bale())))))
    assertTrue(opening.doorOpen)
    assertTrue(opening.baleInChamber)
    assertFalse(opening.baleOnGround)

    val open = assertNotNull(balerPicture(machine(Baler(round = true, door = BaleDoor.OPEN))))
    assertTrue(open.baleOnGround, "open and emptied: the bale has been dropped")
    val closing = assertNotNull(balerPicture(machine(Baler(round = true, door = BaleDoor.CLOSING))))
    assertFalse(closing.doorOpen)
  }

  @Test
  fun squareBalesLieNoseToTailAheadOfTheOneForming() {
    val p = assertNotNull(
      balerPicture(
        machine(
          Baler(
            round = false,
            baleTypes = square,
            baleType = 2,
            bales = listOf(Bale(0.594), Bale(0.242)),
            working = true,
          ),
        ),
      ),
    )
    assertEquals(BalerKind.SQUARE, p.kind)
    assertEquals(2.2f, p.baleLength)
    assertEquals(2, p.channelBales)
    // The newest bale's rear is the forming bale's front; each older one is a length further on.
    assertEquals(channelRear(0, 0.25f, 60f) + 60f, channelRear(1, 0.25f, 60f))
    assertEquals(channelRear(0, 0f, 60f) + 15f, channelRear(0, 0.25f, 60f))
  }

  @Test
  fun theCollectorCountsFromItsOwnFillUnit() {
    val rack = FillUnit(value = 1f, type = "SQUAREBALE", capacity = 3)
    val baler = Baler(round = false, collector = BaleCollector(fillUnit = 2))
    val m = machine(baler, units = listOf(straw, rack))
    val p = assertNotNull(balerPicture(m))
    assertEquals(1, p.collectorBales)
    assertEquals(3, p.collectorPlaces)
    assertEquals("Collector" to "1 / 3", balesOnMachine(baler, FillUnits(m.fillUnits)))
  }

  @Test
  fun aStandaloneWrapperHasNoPickupAndNoChamber() {
    val p = assertNotNull(balerPicture(machine(wrapper = BaleWrapper(state = WrapperState.WRAPPING, progress = 0.49))))
    assertEquals(BalerKind.WRAPPER, p.kind)
    assertNull(p.pickupLowered)
    assertEquals(0.49f, assertNotNull(p.wrapper).progress, 1e-4f)
  }

  @Test
  fun theTableTipsOnlyToDrop() {
    assertEquals(1f, tableTilt(WrapperState.DROPPING))
    assertEquals(0f, tableTilt(WrapperState.WRAPPING))
    assertEquals(0f, tableTilt(WrapperState.WRAPPED))
  }

  @Test
  fun theHeaderFollowsTheWrapperWhileItIsBusy() {
    val combo = machine(Baler(round = true, working = true), BaleWrapper(state = WrapperState.WRAPPING))
    assertEquals("Wrapping", balerState(combo)?.label)
    val idleTable = machine(Baler(round = true, working = true), BaleWrapper(state = WrapperState.EMPTY))
    assertEquals("Baling", balerState(idleTable)?.label)
    assertEquals("Bale ready", balerState(machine(Baler(round = true, bales = listOf(Bale()))))?.label)
    assertEquals("Tailgate open", balerState(machine(Baler(round = true, door = BaleDoor.OPEN)))?.label)
    val idle = assertNotNull(balerState(machine(Baler(round = true))))
    assertEquals("Idle", idle.label)
    assertFalse(idle.active)
  }

  @Test
  fun sizesReadAsTheGameReadsThem() {
    assertEquals("125 cm", baleSizeLabel(round[0]))
    assertEquals("220 cm", baleSizeLabel(square[1]))
  }

  @Test
  fun rollsCountThePartUsedOne() {
    assertEquals("2 / 2", rollsLabel(FillUnit(value = 1.925f, capacity = 2)))
    assertEquals("1 / 2", rollsLabel(FillUnit(value = 1f, capacity = 2)))
    assertEquals("0 / 2", rollsLabel(FillUnit(value = 0f, capacity = 2)))
  }

  @Test
  fun everyDropActionHasItsOwnWords() {
    assertEquals(BaleUnloadAction.entries.size, BaleUnloadAction.entries.map(::unloadLabel).toSet().size)
  }

  @Test
  fun unitsTheScreenDoesNotDrawStillReachIt() {
    // The VARIO-Master's shape: chamber, buffer, net, film, and a 450 l tank nothing else draws.
    val tank = FillUnit(value = 0f, type = "", capacity = 450)
    val m = machine(
      Baler(round = true, fillUnit = 1, consumable = 3, buffer = net.vertexdezign.vdt.model.BalerBuffer(fillUnit = 2)),
      BaleWrapper(consumable = 4),
      units = listOf(straw, FillUnit(type = "CHAFF"), FillUnit(), FillUnit(type = "BALE_WRAP"), tank),
    )
    assertEquals(listOf(tank), otherUnits(m))
  }
}
