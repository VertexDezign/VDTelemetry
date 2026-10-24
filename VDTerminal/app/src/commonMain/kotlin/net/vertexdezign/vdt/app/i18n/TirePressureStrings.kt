package net.vertexdezign.vdt.app.i18n

/** The tyre-pressure widget (Variable Tire Pressure). */
interface TirePressureStrings {
  val title: String
  val notInVehicle: String
  val noSystem: String

  /** The three scopes: the mod's whole-combination, tractor and implements keys. */
  val wholeRig: String
  val implements: String

  val road: String
  val field: String
  val mixed: String
  val front: String
  val rear: String

  /** A pressure, already formatted, with its unit. */
  fun bar(value: String): String

  /** Said for the arrow beside a pressure that is moving: where to, already formatted. */
  fun inflatingTo(value: String): String
  fun deflatingTo(value: String): String

  /** What tapping the readout on the lighting schematic does: the whole rig to one pressure. */
  val switchRigToRoad: String
  val switchRigToField: String
}
