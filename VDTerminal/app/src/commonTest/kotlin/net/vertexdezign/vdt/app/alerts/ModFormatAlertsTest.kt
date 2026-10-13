package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.ModFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModFormatAlertsTest {
  private val current = ModFormat.expected.mapValues { it.value.toString() }

  private fun process(engine: AlertEngine, versions: Map<String, String>?) =
    engine.process(AlertInputs(formatVersions = versions))

  @Test
  fun matchingVersionsRaiseNothing() {
    val engine = AlertEngine(ModFormatAlerts.rules)
    assertTrue(process(engine, current).isEmpty())
    assertTrue(process(engine, null).isEmpty())
  }

  @Test
  fun anOlderChannelAsksForTheModAndNamesIt() {
    val engine = AlertEngine(ModFormatAlerts.rules)
    val raised = process(engine, current + ("storage" to "3"))
    assertEquals(listOf(ModFormatAlerts.MOD_OUTDATED_ID), raised.map { it.rule.id })
    assertTrue("storage" in raised.single().message)
  }

  @Test
  fun aNewerChannelAsksForTheTerminal() {
    val engine = AlertEngine(ModFormatAlerts.rules)
    val raised = process(engine, current + ("telemetry" to "999"))
    assertEquals(listOf(ModFormatAlerts.TERMINAL_OUTDATED_ID), raised.map { it.rule.id })
  }

  @Test
  fun aChannelThatGoesAwayClearsTheAlert() {
    val engine = AlertEngine(ModFormatAlerts.rules)
    process(engine, current + ("invoices" to "0"))
    process(engine, current - "invoices")
    assertTrue(engine.active.value.isEmpty())
  }

  @Test
  fun theMessageNamesEveryOffChannelAndDropsFixedOnes() {
    val engine = AlertEngine(ModFormatAlerts.rules)
    process(engine, current + ("storage" to "1"))
    val second = process(engine, current + ("storage" to "1") + ("finance" to "0")).single()
    assertTrue("storage" in second.message && "finance" in second.message)

    assertTrue(process(engine, current + ("finance" to "0")).isEmpty())
    val message = engine.active.value.single().message
    assertTrue("finance" in message)
    assertFalse("storage" in message)
  }
}
