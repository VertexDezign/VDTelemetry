package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.app.apps.VehicleApp
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.Motor
import net.vertexdezign.vdt.model.MotorFillUnits
import net.vertexdezign.vdt.model.VdtData
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [AlertEngine] semantics: edge-triggering, hysteresis, and behavior on absent data. */
class AlertEngineTest {
  /** Enter at ≤ 10, re-arm above 15 — the same band the fuel alert uses. */
  private fun engine() = AlertEngine(
    listOf(
      AlertRule(
        id = "test.low",
        severity = AlertSeverity.Warning,
        title = "LOW",
        message = { "at ${it.fuel}%" },
        enter = { data -> data.fuel?.let { it <= 10 } == true },
        exit = { data -> data.fuel?.let { it > 15 } == true },
      ),
    ),
  )

  @Test
  fun firesOnceOnTheTransitionNotPerTick() {
    val engine = engine()
    assertEquals(0, engine.process(fuelData(50)).size)
    assertEquals(1, engine.process(fuelData(10)).size)
    // Telemetry keeps ticking below the threshold — no re-fire, but the alert stays active.
    assertEquals(0, engine.process(fuelData(9)).size)
    assertEquals(0, engine.process(fuelData(8)).size)
    assertEquals(1, engine.active.value.size)
  }

  @Test
  fun hysteresisBlocksReFiringInsideTheBand() {
    val engine = engine()
    engine.process(fuelData(9))
    // Back above enter but not above exit: still active, and dropping again must not re-fire.
    assertEquals(0, engine.process(fuelData(12)).size)
    assertEquals(1, engine.active.value.size)
    assertEquals(0, engine.process(fuelData(9)).size)
  }

  @Test
  fun reArmsOnlyAboveTheExitThreshold() {
    val engine = engine()
    engine.process(fuelData(9))
    assertEquals(0, engine.process(fuelData(40)).size)
    assertTrue(engine.active.value.isEmpty())
    // Refueled and drained again — that's a fresh transition.
    assertEquals(1, engine.process(fuelData(9)).size)
  }

  @Test
  fun absentDataFreezesRuleState() {
    val engine = engine()
    engine.process(fuelData(9))
    // On foot (no vehicle) and a connection gap (null tick): neither clears nor re-fires.
    assertEquals(0, engine.process(VdtData()).size)
    assertEquals(0, engine.process(null).size)
    assertEquals(1, engine.active.value.size)
    assertEquals(0, engine.process(fuelData(9)).size)
  }

  @Test
  fun messageIsSnapshottedAtRaiseTime() {
    val engine = engine()
    val raised = engine.process(fuelData(7)).single()
    assertEquals("at 7%", raised.message)
    engine.process(fuelData(5))
    assertEquals("at 7%", engine.active.value.single().message)
  }

  @Test
  fun duplicateRuleIdsAreRejected() {
    val rule = VehicleApp.alerts.single()
    assertFailsWith<IllegalArgumentException> { AlertEngine(listOf(rule, rule)) }
  }

  /** The real fuel rule wired by [VehicleApp], end to end through the engine. */
  @Test
  fun vehicleAppLowFuelRule() {
    val engine = AlertEngine(VehicleApp.alerts)
    assertEquals(0, engine.process(fuelData(11)).size)
    val raised = engine.process(fuelData(10)).single()
    assertEquals(VehicleApp.LOW_FUEL_ALERT_ID, raised.rule.id)
    assertEquals("Fuel at 10%", raised.message)
    // 15 is still inside the band; 16 re-arms.
    engine.process(fuelData(15))
    assertEquals(1, engine.active.value.size)
    engine.process(fuelData(16))
    assertTrue(engine.active.value.isEmpty())
  }
}

private fun fuelData(percent: Int) = VdtData(
  vehicle =
  Vehicle(
    motor = Motor(fillUnits = MotorFillUnits(fuel = FillUnit(fillLevelPercentage = percent))),
  ),
)

private val VdtData.fuel: Int?
  get() =
    vehicle
      ?.motor
      ?.fillUnits
      ?.fuel
      ?.fillLevelPercentage
