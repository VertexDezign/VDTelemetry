package net.vertexdezign.vdt.app.i18n

/** The loader screen: the tool's angle and height, and its cylinders. */
interface LoaderStrings {
  val loader: String
  val noLoader: String
  val level: String
  val height: String
  val nothingBelow: String
  val aboveYourZero: String
  val aboveGround: String
  val load: String
  val angle: String
  val onYourLevel: String
  val onDefaultLevel: String
  val noseUp: String
  val noseDown: String
  fun offDefault(off: String): String
  val setLevel: String
  val setLevelHere: String
  val clearLevel: String

  /** A cylinder by what it does. */
  val lift: String
  val reach: String
  val telescope: String
  val sideShift: String
  val tilt: String
  val highTip: String
  val clamp: String
  val width: String
  fun tool(number: String): String
}
