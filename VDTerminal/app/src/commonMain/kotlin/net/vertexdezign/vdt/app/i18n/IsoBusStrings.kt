package net.vertexdezign.vdt.app.i18n

/** The ISOBUS screen: the machine's status strip, the mixer wagon, and the words its sections share. */
interface IsoBusStrings {
  val noVehicle: String
  val nothingOnRig: String
  fun nothingAttached(slot: String): String
  val selectToControl: String
  val reachNote: String

  /** The status strip's chips. */
  val needsReset: String
  val on: String
  val off: String
  val lowered: String
  val raised: String
  val folded: String
  val unfolded: String
  val pipeMoving: String
  val pipeIn: String
  fun pipeAt(current: Int, count: Int): String
  val pipeOut: String
  val coverClosed: String
  fun coverOf(index: Int, count: Int): String
  val coverOpen: String
  val unloading: String
  val unload: String
  fun workMode(current: Int, count: Int): String
  fun tipTo(side: String): String
  val closed: String
  fun openingTo(side: String): String
  val opening: String
  fun openTo(side: String): String
  val open: String
  fun closingFrom(side: String): String
  val closing: String
  fun hitch(percent: Int): String
  val strapped: String
  val unstrapped: String
  fun straps(fastened: Int, count: Int): String

  /** Why the engine refuses to unload, on a machine unloading into a trough. */
  val refusedHere: String
  val refusedFull: String
  val refusedFillType: String
  val refusedTool: String
  val refusedAccess: String
  val refusedLand: String

  /** The mixer wagon. */
  fun ofCapacity(capacity: String): String
  fun totalMass(mass: String): String
  val load: String
  val weight: String
  val noPower: String
  fun mixing(time: String): String
  val running: String
  val idle: String
  val empty: String
  val offRatio: String
  val loaded: String
  val noRecipe: String
  val outsideWindow: String
  val inWindow: String
  fun toReach(litres: String, percent: Int): String
}
