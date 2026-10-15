package net.vertexdezign.vdt.app.i18n

/** The section strip: work width, nozzles and Precision Farming's rate. */
interface SectionStrings {
  val lowerRate: String
  val raiseRate: String
  fun saved(percent: Int): String
  fun rate(percent: Int): String
  val to: String
  val tool: String
  fun working(type: String): String
  fun ready(type: String): String
  fun off(type: String): String
}
