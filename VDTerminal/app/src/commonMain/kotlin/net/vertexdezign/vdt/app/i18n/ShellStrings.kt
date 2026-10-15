package net.vertexdezign.vdt.app.i18n

/** The shell around every screen: the header and footer bars, and the connection states. */
interface ShellStrings {
  val loading: String
  val connecting: String
  val connectionLost: String
  val openLauncher: String
  val openNotifications: String
  val edit: String
  val done: String
  val editLayoutDescription: String

  /** The header's wake-lock control: short, it shares a slot with an icon. */
  val wakeOn: String
  val wakeOff: String
  val wakeUnsupported: String
  val wakeLockDescription: String
}
