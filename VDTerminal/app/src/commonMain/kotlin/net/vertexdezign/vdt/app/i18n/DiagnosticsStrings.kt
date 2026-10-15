package net.vertexdezign.vdt.app.i18n

/** The Diagnostics app: how often each channel file is written. */
interface DiagnosticsStrings {
  val title: String
  val waiting: String
  val channel: String
  val cadence: String
  val minMax: String
  val lastSeen: String
  val writes: String
  val never: String
  val justNow: String
  fun secondsAgo(seconds: Int): String
  fun minutesAgo(minutes: Int): String
  fun mapLayer(plane: String): String

  /** The channel files by name, null for one this terminal does not know. */
  fun channelName(fileName: String): String?
}
