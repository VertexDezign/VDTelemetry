package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.TirePressureStrings

internal object DeTirePressure : TirePressureStrings {
  override val title = "Reifendruck"
  override val notInVehicle = "Nicht in einem Fahrzeug"
  override val noSystem = "Keine Reifendruckregelanlage am Gespann"
  override val wholeRig = "GANZES GESPANN"
  override val implements = "GERÄTE"
  override val road = "Straße"
  override val field = "Feld"
  override val mixed = "Gemischt"
  override val front = "Vorne"
  override val rear = "Hinten"
  override fun bar(value: String): String = "$value bar"
  override fun inflatingTo(value: String): String = "pumpt auf $value bar"
  override fun deflatingTo(value: String): String = "lässt ab auf $value bar"
  override val switchRigToRoad = "Gespann auf Straßendruck"
  override val switchRigToField = "Gespann auf Felddruck"
}
