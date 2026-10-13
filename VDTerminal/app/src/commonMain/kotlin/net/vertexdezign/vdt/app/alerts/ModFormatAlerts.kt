package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.ModFormat

/**
 * The shell's own alert rules, which belong to no app: the mod and the terminal disagreeing about a
 * channel's format (see [ModFormat] for why that is a normal state once the mod ships on ModHub).
 *
 * Keyed per channel, so the alert names every file that is off -- kept current as channels are fixed --
 * and a second channel going stale later raises again. A Warning rather than Critical: everything that still matches keeps working, and
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
        title = { it.alerts.modOutdated },
        activeEntities = { _, inputs -> channels(inputs, ModFormat.Mismatch.ModOutdated) },
        message = { strings, channels -> strings.alerts.modOutdatedMessage(channels.joinToString()) },
        restatesWholeSet = true,
      ),
      KeyedAlertRule(
        id = TERMINAL_OUTDATED_ID,
        severity = AlertSeverity.Warning,
        title = { it.alerts.terminalOutdated },
        activeEntities = { _, inputs -> channels(inputs, ModFormat.Mismatch.TerminalOutdated) },
        message = { strings, channels -> strings.alerts.terminalOutdatedMessage(channels.joinToString()) },
        restatesWholeSet = true,
      ),
    )

  private fun channels(inputs: AlertInputs, mismatch: ModFormat.Mismatch): Map<String, String>? =
    inputs.formatVersions?.filter { (channel, version) -> ModFormat.check(channel, version) == mismatch }
      ?.mapValues { (channel, _) -> channel }
}
