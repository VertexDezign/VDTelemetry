package net.vertexdezign.vdt.app.i18n

/** The combine and forage-harvester section of the ISOBUS screen. */
interface CombineStrings {
  val idle: String
  val crop: String
  fun from(fruit: String): String
  val worked: String
  fun thisSession(area: String): String
  val filling: String
  val noFlow: String
  val rainStopped: String
  val rainComing: String
  val damp: String
  fun noTankOut(crop: String): String
  val noTank: String
  fun ofCapacity(value: String, capacity: String, unit: String): String
  val throughput: String
  val yield: String
  fun limit(speed: String): String
  val drum: String
  val header: String
  val straw: String
  val swath: String
  val chop: String
  val noStraw: String
  val pickingUp: String
  val cutting: String
  val windrowPickup: String
  val notCutting: String
  val cutsRaised: String
  fun strawShare(percent: Int): String
}
