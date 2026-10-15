package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.VehicleStrings

internal object DeVehicle : VehicleStrings {
  override val engineTitle = "Motor und Getriebe"
  override val engineRunning = "Motor läuft"
  override val speedSync = "Geschwindigkeit koppeln"
  override val noEngine = "Keine Motordaten"
  override val rpm = "U/MIN"
  override val load = "LAST"
  override val fuelPerHour = "DIESEL/H"
  override fun speedLimit(limit: String): String = "Arbeitsgeschwindigkeit max. $limit"
  override val water = "WASSER"
  override val trans = "GETR."
  override val defPerHour = "ADBLUE/H"
  override val steering = "LENKUNG"
  override val cruise = "TEMPOMAT"
  override val cruiseFaster = "Tempomat schneller"
  override val cruiseSlower = "Tempomat langsamer"

  override val navigation = "Navigation"
  override fun compass(index: Int): String = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")[index]
  override val steeringAssist = "Lenkassistent"
  override val aiHelper = "Helfer"
  override val guideLines = "Spurlinien"
  override val lampOn = "an"
  override val lampOff = "aus"
  override val lampUnavailable = "nicht verfügbar"
  override val right = "R"
  override val left = "L"
  override fun linesDone(done: Int): String = " · $done erledigt"

  override val lighting = "Beleuchtung"
  override val beacon = "Rundumleuchte"
  override val workFront = "Arbeitsscheinwerfer vorn"
  override val workRear = "Arbeitsscheinwerfer hinten"
  override val highBeam = "Fernlicht"
  override val lowBeam = "Abblendlicht"
  override val turnLeft = "Blinker links"
  override val hazard = "Warnblinker"
  override val turnRight = "Blinker rechts"
  override val schematic = "Traktorskizze"

  override val slotVehicle = "Fahrzeug"
  override val slotFront = "Vorne"
  override val slotRear = "Hinten"
  override val mergeByFillType = "nach Füllgut zusammenfassen"
  override val noVehicle = "Kein Fahrzeug"
  override val noImplement = "Kein Gerät"
  override val needsReset = "Zurücksetzen nötig"
  override val unknown = "Unbekannt"

  override val lowFuel = "WENIG DIESEL"
  override fun fuelAt(percent: Int): String = "Diesel bei $percent%"
}
