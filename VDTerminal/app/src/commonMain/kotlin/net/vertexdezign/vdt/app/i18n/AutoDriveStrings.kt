package net.vertexdezign.vdt.app.i18n

/** The AutoDrive app. */
interface AutoDriveStrings {
  val closeList: String
  val notInstalled: String
  val notInVehicle: String
  val notDriven: String
  val searchDestinations: String
  val searchFillTypes: String
  val mode: String
  val pickUpAt: String
  val destination: String
  val deliverTo: String
  val load: String
  val rounds: String
  val driving: String
  val stopped: String
  fun heading(marker: String): String
  val choose: String
  val fewerRounds: String
  val moreRounds: String
  val untilStopped: String
  fun roundsLeft(left: Int, total: Int): String
  val stop: String
  val start: String

  /** AutoDrive's modes, as its mode button cycles them. */
  val modeDriveTo: String
  val modePickupAndDeliver: String
  val modeDeliverTo: String
  val modeLoad: String
  val modeUnload: String
  val modeOther: String
}
