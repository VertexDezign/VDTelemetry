package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.AutoDriveStrings

internal object EnAutoDrive : AutoDriveStrings {
  override val closeList = "close the list"
  override val notInstalled = "AutoDrive is not installed"
  override val notInVehicle = "Not in a vehicle"
  override val notDriven = "AutoDrive does not drive this vehicle"
  override val searchDestinations = "Search destinations…"
  override val searchFillTypes = "Search fill types…"
  override val mode = "MODE"
  override val pickUpAt = "PICK UP AT"
  override val destination = "DESTINATION"
  override val deliverTo = "DELIVER TO"
  override val load = "LOAD"
  override val rounds = "ROUNDS"
  override val driving = "DRIVING"
  override val stopped = "STOPPED"
  override fun heading(marker: String): String = "to $marker"
  override val choose = "Choose…"
  override val fewerRounds = "fewer rounds"
  override val moreRounds = "more rounds"
  override val untilStopped = "Until stopped"
  override fun roundsLeft(left: Int, total: Int): String = "$left of $total left"
  override val stop = "STOP"
  override val start = "START"

  override val modeDriveTo = "Drive to"
  override val modePickupAndDeliver = "Pick up & deliver"
  override val modeDeliverTo = "Deliver to"
  override val modeLoad = "Load"
  override val modeUnload = "Unload combine"
  override val modeOther = "Other"
}
