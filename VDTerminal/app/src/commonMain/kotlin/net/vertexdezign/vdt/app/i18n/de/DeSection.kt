package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.SectionStrings

internal object DeSection : SectionStrings {
  override val lowerRate = "Ausbringmenge senken"
  override val raiseRate = "Ausbringmenge erhöhen"
  override fun saved(percent: Int): String = "$percent% gespart"
  override fun rate(percent: Int): String = "$percent% Menge"
  override val to = "bis"
  override val tool = "Gerät"
  override fun working(type: String): String = "$type · arbeitet"
  override fun ready(type: String): String = "$type · bereit"
  override fun off(type: String): String = "$type · aus"
}
