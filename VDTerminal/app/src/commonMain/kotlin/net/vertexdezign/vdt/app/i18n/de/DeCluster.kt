package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.ClusterStrings

internal object DeCluster : ClusterStrings {
  override val rpm = "U/MIN"
  override val speed = "TEMPO"
  override val cruise = "TEMPOMAT"
  override val gear = "GANG"
  override val auto = "AUTO"
  override val sync = "SYNC"
  override val temp = "TEMP"
  override val trans = "GETR."
  override val fuel = "DIESEL"
  override val def = "ADBLUE"
  override val air = "LUFT"
  override val noService = "KEINE SERVICEDATEN"
  override val volts = "VOLT"
  override val overdueBy = "ÜBERFÄLLIG"
  override val serviceIn = "SERVICE IN"
  override val hours = "H"

  override val forward = "Getriebe vorwärts"
  override val reverse = "Getriebe rückwärts"
  override val neutral = "Getriebe neutral"
  override fun steeringMode(index: Int, count: Int): String = "Lenkmodus $index von $count"
  override val seatReversed = "Fahrerplatz gewendet"
  override val seatForward = "Fahrerplatz vorwärts"
  override val assistEngaged = "Lenkassistent aktiv"
  override val assistArmed = "Lenkassistent bereit"
  override val syncWaiting = "Kopplung wartet auf die andere Maschine"
  override val synced = "Geschwindigkeit gekoppelt"
  override fun speedLimit(figure: Int, unit: String): String = "Arbeitsgeschwindigkeit max. $figure $unit".trim()
  override fun engineLoad(percent: Int, overloaded: Boolean): String =
    "Motorlast $percent%" + if (overloaded) ", überlastet" else ""

  override val turnLeft = "Blinker links"
  override val turnRight = "Blinker rechts"
  override val highBeam = "Fernlicht"
  override val lowBeam = "Abblendlicht"
  override val workFront = "Arbeitsscheinwerfer vorn"
  override val workRear = "Arbeitsscheinwerfer hinten"
  override val beacon = "Rundumleuchte"
  override val parkingBrake = "Feststellbremse"
  override val diffLock = "Differenzialsperre"
  override val awd = "Allradantrieb"
  override val engineWarning = "Motorwarnung"
  override val coolant = "Kühlmitteltemperatur"
  override val charging = "Ladesystem"
  override val brakes = "Bremssystem"
  override val service = "Wartung fällig"
  override val attention = "Achtung"
}
