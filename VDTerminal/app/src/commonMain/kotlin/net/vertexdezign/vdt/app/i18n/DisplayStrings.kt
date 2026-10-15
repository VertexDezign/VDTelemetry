package net.vertexdezign.vdt.app.i18n

/** Display mode's own shell: the strip a pinned device shows instead of the header and footer. */
interface DisplayStrings {
  val title: String
  val wakeOn: String
  val wakeOff: String
  val wakeUnsupported: String

  /** The wake-lock state read out for a screen reader, [state] being one of the three above. */
  fun wakeLockDescription(state: String): String

  val editLayout: String
  val exit: String
  val editing: String
  val move: String
  val done: String
  val noSuchScreen: String

  /** Why a pinned display shows nothing: [target] is the page it was pinned to. */
  fun noSuchScreenDetail(target: String): String
}
