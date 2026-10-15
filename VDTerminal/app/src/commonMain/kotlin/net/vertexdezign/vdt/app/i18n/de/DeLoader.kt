package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.LoaderStrings

internal object DeLoader : LoaderStrings {
  override val loader = "Lader"
  override val noLoader = "Kein Lader am Gespann"
  override val level = "WAAGRECHT"
  override val height = "Höhe"
  override val nothingBelow = "nichts darunter in Reichweite"
  override val aboveYourZero = "über deinem Nullpunkt"
  override val aboveGround = "über dem Untergrund"
  override val load = "Ladung"
  override val angle = "WINKEL"
  override val onYourLevel = "auf deinem Nullpunkt"
  override val onDefaultLevel = "auf dem Standard-Nullpunkt"
  override val noseUp = "Spitze hoch"
  override val noseDown = "Spitze runter"
  override fun offDefault(off: String): String = "$off, Standard-Nullpunkt"
  override val setLevel = "Nullpunkt setzen"
  override val setLevelHere = "Nullpunkt hier setzen"
  override val clearLevel = "Nullpunkt löschen"

  override val lift = "Heben"
  override val reach = "Ausschub"
  override val telescope = "Teleskop"
  override val sideShift = "Seitenschub"
  override val tilt = "Kippen"
  override val highTip = "Hochkippen"
  override val clamp = "Klammer"
  override val width = "Breite"
  override fun tool(number: String): String = "Werkzeug $number"
}
