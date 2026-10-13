package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.RotationStrings

internal object DeRotation : RotationStrings {
  override val notInstalled = "CropRotation-Mod nicht installiert"
  override val noPlans = "Keine Fruchtfolgen — lege eine im Spielmenü an"
  override val plans = "PLÄNE"
  override val newRotation = "Neue Fruchtfolge"
  override val noPlansYet = "Noch keine Fruchtfolgen"
  override val deleteTitle = "Fruchtfolge löschen?"
  override val unnamed = "Unbenannte Fruchtfolge"
  override val deleteRotation = "Fruchtfolge löschen"
  override val noCrops = "Keine Früchte in dieser Fruchtfolge"
  override val addSlot = "Position hinzufügen"
  override val removeSlot = "letzte Position entfernen"
  override val fallow = "Brache"
  override fun crop(state: Int): String = "Frucht $state"
  override val noCatchCrop = "Keine Zwischenfrucht"
  override val change = "ändern"
}
