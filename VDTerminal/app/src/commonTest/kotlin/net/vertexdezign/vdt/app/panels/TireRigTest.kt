package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.TireMode
import net.vertexdezign.vdt.model.TirePressure
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TireRigTest {
  private fun tyres(mode: TireMode) = TirePressure(mode = mode, bar = 1f, targetBar = 1f)

  @Test
  fun nothingOnTheRigWithTheSystemIsNoRig() {
    assertNull(TireRig.of(Vehicle(name = "Fendt", implement = listOf(Implement(name = "Plough")))))
  }

  @Test
  fun collectsTheImplementsDepthFirst() {
    val vehicle =
      Vehicle(
        name = "Fendt",
        tirePressure = tyres(TireMode.ROAD),
        implement =
        listOf(
          Implement(
            name = "Dolly",
            tirePressure = tyres(TireMode.ROAD),
            implement = listOf(Implement(name = "Tanker", tirePressure = tyres(TireMode.FIELD))),
          ),
          Implement(name = "Front weight"),
        ),
      )
    val rig = TireRig.of(vehicle)!!
    assertEquals("Fendt", rig.vehicle?.name)
    assertEquals(listOf("Dolly", "Tanker"), rig.implements.map { it.name })
    // The trailers disagree, so neither the implements nor the whole rig has one mode.
    assertNull(rig.implementsMode)
    assertNull(rig.mode)
  }

  @Test
  fun aRigInOneModeHasThatMode() {
    val rig =
      TireRig.of(
        Vehicle(
          tirePressure = tyres(TireMode.FIELD),
          implement = listOf(Implement(tirePressure = tyres(TireMode.FIELD))),
        ),
      )!!
    assertEquals(TireMode.FIELD, rig.mode)
    assertEquals(TireMode.FIELD, rig.implementsMode)
  }

  @Test
  fun aTractorWithItsAxlesApartIsInNoOneMode() {
    val rig = TireRig.of(Vehicle(tirePressure = tyres(TireMode.MIXED)))!!
    assertNull(rig.mode)
  }

  @Test
  fun implementsWithoutTheTractorStillMakeARig() {
    val rig = TireRig.of(Vehicle(implement = listOf(Implement(name = "Tanker", tirePressure = tyres(TireMode.ROAD)))))!!
    assertNull(rig.vehicle)
    assertEquals(TireMode.ROAD, rig.mode)
  }
}
