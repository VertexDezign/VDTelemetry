package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.ControlTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RigSlot] is a three-way translation: the name persisted in a tile's config, the `position` the mod
 * exports, and the [ControlTarget] a command is addressed to. Two of the three disagree on the rear —
 * it is `REAR` on screen but `BACK` on the wire — so the mapping is worth pinning down.
 */
class RigSlotTest {
  @Test
  fun theRearIsCalledBackOnTheWire() {
    assertEquals("BACK", RigSlot.REAR.implementPosition)
    assertEquals(ControlTarget.BACK, RigSlot.REAR.target)
  }

  @Test
  fun theFrontAgreesWithItself() {
    assertEquals("FRONT", RigSlot.FRONT.implementPosition)
    assertEquals(ControlTarget.FRONT, RigSlot.FRONT.target)
  }

  @Test
  fun theVehicleIsNotAnImplementPosition() {
    // Looking the vehicle up among the implements would find whatever happens to sit at position "",
    // so it has no position at all and SlotPanel reads it straight off the vehicle instead.
    assertNull(RigSlot.VEHICLE.implementPosition)
    assertEquals(ControlTarget.VEHICLE, RigSlot.VEHICLE.target)
  }
}
