package net.vertexdezign.vdt.app.i18n

/** The Production app: production points, their lines, and the plants they belong to. */
interface ProductionStrings {
  val waiting: String
  val empty: String
  val listTitle: String
  val noLines: String
  fun lines(count: Int): String
  val factory: String
  val inputs: String
  val outputs: String

  /** A line's monthly figures, both already formatted. */
  fun perMonth(cycles: String, costs: String): String
  val on: String
  val off: String
  val changeMode: String

  val statusRunning: String
  val statusMissingInput: String
  val statusNoSpace: String
  val statusInactive: String

  val modeDirectSell: String
  val modeDistribute: String
  val modeWithinPlant: String
  val modeKeep: String

  /** A plant's part roles, plural: one row stands for every machine of that role. */
  val roleBunkers: String
  val roleFermenters: String
  val roleCogeneration: String
  val roleDigestate: String
  val roleTorch: String

  val plant: String
  val utilization: String
  val noReading: String

  /** A utilization reading's band, in a word; the torch has its own two. */
  val torchBurning: String
  val torchIdle: String
  val atLimit: String
  val optimal: String
  val ok: String
  val poor: String
  val stopped: String
}
