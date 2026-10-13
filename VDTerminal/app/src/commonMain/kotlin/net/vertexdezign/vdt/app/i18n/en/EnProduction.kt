package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.ProductionStrings

internal object EnProduction : ProductionStrings {
  override val waiting = "Waiting for production data…"
  override val empty = "No owned productions"
  override val listTitle = "Productions"
  override val noLines = "This production has no production lines"
  override fun lines(count: Int): String = if (count == 1) "1 line" else "$count lines"
  override val factory = "Factory"
  override val inputs = "Inputs"
  override val outputs = "Outputs"
  override fun perMonth(cycles: String, costs: String): String = "$cycles cycles/mo · $costs/mo"
  override val on = "ON"
  override val off = "OFF"
  override val changeMode = "change mode"

  override val statusRunning = "Running"
  override val statusMissingInput = "Missing input"
  override val statusNoSpace = "Out of space"
  override val statusInactive = "Inactive"

  override val modeDirectSell = "Direct sell"
  override val modeDistribute = "Distribute"
  override val modeWithinPlant = "Within plant"
  override val modeKeep = "Keep"

  override val roleBunkers = "Bunkers"
  override val roleFermenters = "Fermenters"
  override val roleCogeneration = "Cogeneration"
  override val roleDigestate = "Digestate tanks"
  override val roleTorch = "Gas torch"

  override val plant = "PLANT"
  override val utilization = "Utilization"
  override val noReading = "No reading"

  override val torchBurning = "burning"
  override val torchIdle = "idle"
  override val atLimit = "at limit"
  override val optimal = "optimal"
  override val ok = "ok"
  override val poor = "poor"
  override val stopped = "stopped"
}
