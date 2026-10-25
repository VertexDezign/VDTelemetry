package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.TirePressureStrings

internal object EnTirePressure : TirePressureStrings {
  override val title = "Tyre pressure"
  override val notInVehicle = "Not in a vehicle"
  override val noSystem = "No tyre inflation system on this rig"
  override val wholeRig = "WHOLE RIG"
  override val implements = "IMPLEMENTS"
  override val road = "Road"
  override val field = "Field"
  override val mixed = "Mixed"
  override val front = "Front"
  override val rear = "Rear"
  override fun bar(value: String): String = "$value bar"
  override fun inflatingTo(value: String): String = "inflating to $value bar"
  override fun deflatingTo(value: String): String = "deflating to $value bar"
  override val switchRigToRoad = "switch the rig to road pressure"
  override val switchRigToField = "switch the rig to field pressure"
}
