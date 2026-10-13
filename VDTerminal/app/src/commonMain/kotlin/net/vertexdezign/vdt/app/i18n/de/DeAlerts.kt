package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.AlertsStrings

internal object DeAlerts : AlertsStrings {
  override val modOutdated = "MOD VERALTET"
  override val terminalOutdated = "TERMINAL VERALTET"
  override fun modOutdatedMessage(channels: String): String =
    "VDTelemetry-Mod aktualisieren. Älteres Format, als dieses Terminal liest: $channels"
  override fun terminalOutdatedMessage(channels: String): String =
    "VDTerminal aktualisieren. Neueres Format, als dieses Terminal liest: $channels"
  override val notifications = "BENACHRICHTIGUNGEN"
  override val clear = "LEEREN"
  override val nothingYet = "Bisher nichts"
  override val active = "AKTIV"
}
