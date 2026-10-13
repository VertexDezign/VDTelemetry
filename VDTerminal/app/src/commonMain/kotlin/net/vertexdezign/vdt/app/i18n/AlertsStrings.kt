package net.vertexdezign.vdt.app.i18n

/** The shell's alerts: the notification centre and the mod-format rules. */
interface AlertsStrings {
  val modOutdated: String
  val terminalOutdated: String
  fun modOutdatedMessage(channels: String): String
  fun terminalOutdatedMessage(channels: String): String
  val notifications: String
  val clear: String
  val nothingYet: String
  val active: String
}
