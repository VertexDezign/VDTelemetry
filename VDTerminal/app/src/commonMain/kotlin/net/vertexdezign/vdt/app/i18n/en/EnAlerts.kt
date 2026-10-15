package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.AlertsStrings

internal object EnAlerts : AlertsStrings {
  override val modOutdated = "MOD OUTDATED"
  override val terminalOutdated = "TERMINAL OUTDATED"
  override fun modOutdatedMessage(channels: String): String =
    "Update the VDTelemetry mod. Older format than this terminal reads: $channels"
  override fun terminalOutdatedMessage(channels: String): String =
    "Update VDTerminal. Newer format than this terminal reads: $channels"
  override val notifications = "NOTIFICATIONS"
  override val clear = "CLEAR"
  override val nothingYet = "Nothing has come up"
  override val active = "ACTIVE"
}
