package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.ModFormat

/**
 * The shell's own alert rules, which belong to no app: the mod and the terminal disagreeing about a
 * channel's format (see [ModFormat] for why that is a normal state once the mod ships on ModHub).
 *
 * Keyed per channel, so the alert names the files that are off and a second channel going stale later
 * raises again. A Warning rather than Critical: everything that still matches keeps working, and
 * nothing in the game is at stake -- but it needs the player to go and install something, which an
 * Info nobody hears would not get done.
 */
object ModFormatAlerts {
  const val MOD_OUTDATED_ID = "shell.modOutdated"
  const val TERMINAL_OUTDATED_ID = "shell.terminalOutdated"

  val rules: List<AlertRule> =
    listOf(
      KeyedAlertRule(
        id = MOD_OUTDATED_ID,
        severity = AlertSeverity.Warning,
        title = "MOD OUTDATED",
        activeEntities = { channels(it, ModFormat.Mismatch.ModOutdated) },
        message = { "Update the VDTelemetry mod. Older format than this terminal reads: ${it.joinToString()}" },
      ),
      KeyedAlertRule(
        id = TERMINAL_OUTDATED_ID,
        severity = AlertSeverity.Warning,
        title = "TERMINAL OUTDATED",
        activeEntities = { channels(it, ModFormat.Mismatch.TerminalOutdated) },
        message = { "Update VDTerminal. Newer format than this terminal reads: ${it.joinToString()}" },
      ),
    )

  private fun channels(inputs: AlertInputs, mismatch: ModFormat.Mismatch): Map<String, String>? =
    inputs.formatVersions?.filter { (channel, version) -> ModFormat.check(channel, version) == mismatch }
      ?.mapValues { (channel, _) -> channel }
}
