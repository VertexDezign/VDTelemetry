package net.vertexdezign.vdt.app.i18n

/** The baler and bale-wrapper sections of the ISOBUS screen. */
interface BalerStrings {
  val bale: String
  fun ofCapacity(value: String, capacity: String, unit: String): String
  val size: String
  fun afterThisBale(size: String): String
  val diameter: String
  fun squareShape(width: String, height: String): String
  val buffer: String
  val bufferEmptying: String
  val wrap: String
  val collector: String
  val inChannel: String
  val bales: String
  fun total(count: String): String
  val reset: String
  val net: String
  val twine: String
  val film: String
  val rolls: String
  val autoDrop: String
  val autoDropWrapped: String
  val dropWrapped: String
  val cannotWrap: String
  val dropBale: String
  val dropUnfinished: String
  val closeTailgate: String
  val tipPlatform: String
  val tailgateOpen: String
  val tailgateClosing: String
  val baleReady: String
  val baling: String
  val idle: String
  val waitingForBale: String
  val loading: String
  val baleOnTable: String
  val wrapping: String
  val wrapped: String
  val dropping: String
  val tableReturning: String
}
