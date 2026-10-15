package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.AutoDriveStrings

internal object DeAutoDrive : AutoDriveStrings {
  override val closeList = "Liste schließen"
  override val notInstalled = "AutoDrive ist nicht installiert"
  override val notInVehicle = "Nicht in einem Fahrzeug"
  override val notDriven = "AutoDrive fährt dieses Fahrzeug nicht"
  override val searchDestinations = "Ziele suchen…"
  override val searchFillTypes = "Füllgüter suchen…"
  override val mode = "MODUS"
  override val pickUpAt = "LADEN BEI"
  override val destination = "ZIEL"
  override val deliverTo = "LIEFERN AN"
  override val load = "LADUNG"
  override val rounds = "RUNDEN"
  override val driving = "FÄHRT"
  override val stopped = "GESTOPPT"
  override fun heading(marker: String): String = "nach $marker"
  override val choose = "Wählen…"
  override val fewerRounds = "weniger Runden"
  override val moreRounds = "mehr Runden"
  override val untilStopped = "Bis zum Stopp"
  override fun roundsLeft(left: Int, total: Int): String = "noch $left von $total"
  override val stop = "STOPP"
  override val start = "START"

  override val modeDriveTo = "Fahren zu"
  override val modePickupAndDeliver = "Laden & liefern"
  override val modeDeliverTo = "Liefern an"
  override val modeLoad = "Laden"
  override val modeUnload = "Drescher entladen"
  override val modeOther = "Sonstiges"
}
