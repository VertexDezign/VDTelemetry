package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.CombineStrings

internal object EnCombine : CombineStrings {
  override val idle = "Idle"
  override val crop = "Crop"
  override fun from(fruit: String): String = "from $fruit"
  override val worked = "Worked"
  override fun thisSession(area: String): String = "+$area this session"
  override val filling = "Filling"
  override val noFlow = "No flow"
  override val rainStopped = "Rain — stopped"
  override val rainComing = "Rain coming"
  override val damp = "Damp crop"
  override fun noTankOut(crop: String): String = "No tank — $crop straight out the spout"
  override val noTank = "No tank — straight out the spout"
  override fun ofCapacity(value: String, capacity: String, unit: String): String = "$value of $capacity $unit"
  override val throughput = "Throughput"
  override val yield = "Yield"
  override fun limit(speed: String): String = "Limit $speed km/h"
  override val drum = "DRUM"
  override val header = "HEADER"
  override val straw = "STRAW"
  override val swath = "Swath"
  override val chop = "Chop"
  override val noStraw = "This crop leaves no straw"
  override val pickingUp = "Picking up"
  override val cutting = "Cutting"
  override val windrowPickup = "Windrow pickup"
  override val notCutting = "Not cutting"
  override val cutsRaised = "Cuts raised"
  override fun strawShare(percent: Int): String = "Straw $percent%"
}
