package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.app.apps.TasksApp
import net.vertexdezign.vdt.app.apps.VehicleApp
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.Motor
import net.vertexdezign.vdt.model.MotorFillUnits
import net.vertexdezign.vdt.model.Task
import net.vertexdezign.vdt.model.TaskGroup
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [AlertEngine] semantics: edge-triggering, hysteresis, keyed entities, and absent data. */
class AlertEngineTest {
  /** Enter at ≤ 10, re-arm above 15 — the same band the fuel alert uses. */
  private fun engine() = AlertEngine(
    listOf(
      ThresholdAlertRule(
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
    // On foot (no vehicle) and a connection gap (empty inputs): neither clears nor re-fires.
    assertEquals(0, engine.process(AlertInputs(telemetry = VdtData())).size)
    assertEquals(0, engine.process(AlertInputs()).size)
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

  // ---------------------------------------------------------------------------
  // Keyed rules (via the real TasksApp tasks-due rule)
  // ---------------------------------------------------------------------------

  @Test
  fun keyedRuleFiresPerNewEntityNotPerTick() {
    val engine = AlertEngine(TasksApp.alerts)
    val raised = engine.process(taskData("a" to "Feed cows")).single()
    assertEquals(TasksApp.TASKS_DUE_ALERT_ID, raised.rule.id)
    assertEquals("Feed cows", raised.message)
    // Same snapshot ticking again: nothing new.
    assertEquals(0, engine.process(taskData("a" to "Feed cows")).size)
    // A second task turns due: one raise, mentioning only the newcomer.
    val second = engine.process(taskData("a" to "Feed cows", "b" to "Harvest field 3")).single()
    assertEquals("Harvest field 3", second.message)
  }

  @Test
  fun keyedRuleBatchesSimultaneousEntities() {
    val engine = AlertEngine(TasksApp.alerts)
    val raised = engine.process(taskData("a" to "One", "b" to "Two")).single()
    assertEquals("2 tasks: One, Two", raised.message)
  }

  @Test
  fun keyedRuleClearsWhenNoEntityRemainsAndCanReFire() {
    val engine = AlertEngine(TasksApp.alerts)
    engine.process(taskData("a" to "Feed cows"))
    assertEquals(1, engine.active.value.size)
    // Task completed: alert clears; the same task recurring later is a fresh raise.
    assertEquals(0, engine.process(taskData()).size)
    assertTrue(engine.active.value.isEmpty())
    assertEquals(1, engine.process(taskData("a" to "Feed cows")).size)
  }

  @Test
  fun keyedRuleFreezesOnAbsentChannel() {
    val engine = AlertEngine(TasksApp.alerts)
    engine.process(taskData("a" to "Feed cows"))
    // Channel gone (mod missing / not yet received): neither clears nor re-fires.
    assertEquals(0, engine.process(AlertInputs()).size)
    assertEquals(1, engine.active.value.size)
    assertEquals(0, engine.process(taskData("a" to "Feed cows")).size)
  }
}

private fun fuelData(percent: Int) = AlertInputs(
  telemetry =
  VdtData(
    vehicle =
    Vehicle(
      motor = Motor(fillUnits = MotorFillUnits(fuel = FillUnit(fillLevelPercentage = percent))),
    ),
  ),
)

/** A task list with one group whose given tasks (id → description) are all currently due. */
private fun taskData(vararg due: Pair<String, String>) = AlertInputs(
  taskList =
  TaskListData(
    groups =
    listOf(
      TaskGroup(
        id = "g",
        tasks = due.map { (id, description) -> Task(id = id, description = description, active = true) },
      ),
    ),
  ),
)

private val AlertInputs.fuel: Int?
  get() =
    telemetry
      ?.vehicle
      ?.motor
      ?.fillUnits
      ?.fuel
      ?.fillLevelPercentage
