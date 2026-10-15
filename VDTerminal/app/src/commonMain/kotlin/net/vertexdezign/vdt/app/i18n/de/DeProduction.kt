package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.ProductionStrings

internal object DeProduction : ProductionStrings {
  override val waiting = "Warte auf Produktionsdaten…"
  override val empty = "Keine eigenen Produktionen"
  override val listTitle = "Produktionen"
  override val noLines = "Diese Produktion hat keine Produktionslinien"
  override fun lines(count: Int): String = if (count == 1) "1 Linie" else "$count Linien"
  override val factory = "Fabrik"
  override val inputs = "Rohstoffe"
  override val outputs = "Produkte"
  override fun perMonth(cycles: String, costs: String): String = "$cycles Zyklen/Monat · $costs/Monat"
  override val on = "AN"
  override val off = "AUS"
  override val changeMode = "Modus ändern"

  override val statusRunning = "Läuft"
  override val statusMissingInput = "Rohstoff fehlt"
  override val statusNoSpace = "Lager voll"
  override val statusInactive = "Inaktiv"

  override val modeDirectSell = "Direktverkauf"
  override val modeDistribute = "Verteilen"
  override val modeWithinPlant = "In der Anlage"
  override val modeKeep = "Einlagern"

  override val roleBunkers = "Bunker"
  override val roleFermenters = "Fermenter"
  override val roleCogeneration = "BHKW"
  override val roleDigestate = "Gärrestlager"
  override val roleTorch = "Gasfackel"

  override val plant = "ANLAGE"
  override val utilization = "Auslastung"
  override val noReading = "Kein Messwert"

  override val torchBurning = "brennt"
  override val torchIdle = "aus"
  override val atLimit = "am Limit"
  override val optimal = "optimal"
  override val ok = "ok"
  override val poor = "schwach"
  override val stopped = "steht"
}
