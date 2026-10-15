package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.RotationStrings

internal object EnRotation : RotationStrings {
  override val notInstalled = "CropRotation mod not installed"
  override val noPlans = "No rotation plans — create one in the game menu"
  override val plans = "PLANS"
  override val newRotation = "New rotation"
  override val noPlansYet = "No rotation plans yet"
  override val deleteTitle = "Delete rotation?"
  override val unnamed = "Unnamed rotation"
  override val deleteRotation = "delete rotation"
  override val noCrops = "No crops in this rotation"
  override val addSlot = "add slot"
  override val removeSlot = "remove last slot"
  override val fallow = "Fallow"
  override fun crop(state: Int): String = "Crop $state"
  override val noCatchCrop = "No catch crop"
  override val change = "change"
}
