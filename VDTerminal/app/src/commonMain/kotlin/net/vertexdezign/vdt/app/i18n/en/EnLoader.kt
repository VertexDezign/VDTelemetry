package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.LoaderStrings

internal object EnLoader : LoaderStrings {
  override val loader = "Loader"
  override val noLoader = "No loader on this rig"
  override val level = "LEVEL"
  override val height = "Height"
  override val nothingBelow = "nothing below in reach"
  override val aboveYourZero = "above your zero"
  override val aboveGround = "above what is below"
  override val load = "Load"
  override val angle = "ANGLE"
  override val onYourLevel = "on your level"
  override val onDefaultLevel = "on the default level"
  override val noseUp = "nose up"
  override val noseDown = "nose down"
  override fun offDefault(off: String): String = "$off, default level"
  override val setLevel = "Set level"
  override val setLevelHere = "Set level here"
  override val clearLevel = "Clear level"

  override val lift = "Lift"
  override val reach = "Reach"
  override val telescope = "Telescope"
  override val sideShift = "Side shift"
  override val tilt = "Tilt"
  override val highTip = "High tip"
  override val clamp = "Clamp"
  override val width = "Width"
  override fun tool(number: String): String = "Tool $number"
}
