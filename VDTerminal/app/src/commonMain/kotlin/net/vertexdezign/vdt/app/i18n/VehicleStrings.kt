package net.vertexdezign.vdt.app.i18n

/** The Vehicle app's panels: engine, navigation, lighting and the rig slots. */
interface VehicleStrings {
  /** Engine and Transmission. */
  val engineTitle: String
  val engineRunning: String
  val speedSync: String
  val noEngine: String
  val rpm: String
  val load: String
  val fuelPerHour: String
  fun speedLimit(limit: String): String
  val water: String
  val trans: String
  val defPerHour: String
  val steering: String
  val cruise: String
  val cruiseFaster: String
  val cruiseSlower: String

  /** Navigation. */
  val navigation: String
  fun compass(index: Int): String
  val steeringAssist: String
  val aiHelper: String
  val guideLines: String
  val lampOn: String
  val lampOff: String
  val lampUnavailable: String

  /** The lightbar's side of the line: right, left. */
  val right: String
  val left: String
  fun linesDone(done: Int): String

  /** Lighting. */
  val lighting: String
  val beacon: String
  val workFront: String
  val workRear: String
  val highBeam: String
  val lowBeam: String
  val turnLeft: String
  val hazard: String
  val turnRight: String
  val schematic: String

  /** The rig slot. */
  val slotVehicle: String
  val slotFront: String
  val slotRear: String
  val mergeByFillType: String
  val noVehicle: String
  val noImplement: String
  val needsReset: String
  val unknown: String

  /** The low-fuel alert. */
  val lowFuel: String
  fun fuelAt(percent: Int): String
}
