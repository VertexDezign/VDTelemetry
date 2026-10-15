package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.CombineStrings

internal object DeCombine : CombineStrings {
  override val idle = "Steht"
  override val crop = "Frucht"
  override fun from(fruit: String): String = "aus $fruit"
  override val worked = "Bearbeitet"
  override fun thisSession(area: String): String = "+$area in dieser Sitzung"
  override val filling = "Füllt"
  override val noFlow = "Kein Durchsatz"
  override val rainStopped = "Regen — gestoppt"
  override val rainComing = "Regen kommt"
  override val damp = "Feuchtes Erntegut"
  override fun noTankOut(crop: String): String = "Kein Tank — $crop direkt aus dem Rohr"
  override val noTank = "Kein Tank — direkt aus dem Rohr"
  override fun ofCapacity(value: String, capacity: String, unit: String): String = "$value von $capacity $unit"
  override val throughput = "Durchsatz"
  override val yield = "Ertrag"
  override fun limit(speed: String): String = "Limit $speed km/h"
  override val drum = "TROMMEL"
  override val header = "SCHNEIDWERK"
  override val straw = "STROH"
  override val swath = "Schwad"
  override val chop = "Häckseln"
  override val noStraw = "Diese Frucht hinterlässt kein Stroh"
  override val pickingUp = "Nimmt auf"
  override val cutting = "Schneidet"
  override val windrowPickup = "Schwadaufnahme"
  override val notCutting = "Schneidet nicht"
  override val cutsRaised = "Schneidet angehoben"
  override fun strawShare(percent: Int): String = "Stroh $percent%"
}
