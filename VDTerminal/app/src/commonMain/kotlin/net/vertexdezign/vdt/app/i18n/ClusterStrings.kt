package net.vertexdezign.vdt.app.i18n

/** The instrument cluster's tiles: readout, telltale band, level strip and service. */
interface ClusterStrings {
  /** Line labels, in the caption face: short, upper case. */
  val rpm: String
  val speed: String
  val cruise: String
  val gear: String
  val auto: String
  val sync: String
  val temp: String
  val trans: String
  val fuel: String
  val def: String
  val air: String
  val noService: String
  val volts: String
  val overdueBy: String
  val serviceIn: String
  val hours: String

  /** Marks, read out for a screen reader. */
  val forward: String
  val reverse: String
  val neutral: String
  fun steeringMode(index: Int, count: Int): String
  val seatReversed: String
  val seatForward: String
  val assistEngaged: String
  val assistArmed: String
  val syncWaiting: String
  val synced: String
  fun speedLimit(figure: Int, unit: String): String
  fun engineLoad(percent: Int, overloaded: Boolean): String

  /** The telltales, by what they say. */
  val turnLeft: String
  val turnRight: String
  val highBeam: String
  val lowBeam: String
  val workFront: String
  val workRear: String
  val beacon: String
  val parkingBrake: String
  val diffLock: String
  val awd: String
  val engineWarning: String
  val coolant: String
  val charging: String
  val brakes: String
  val service: String
  val attention: String
}
