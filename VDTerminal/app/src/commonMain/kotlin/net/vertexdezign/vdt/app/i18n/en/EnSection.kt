package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.SectionStrings

internal object EnSection : SectionStrings {
  override val lowerRate = "lower the application rate"
  override val raiseRate = "raise the application rate"
  override fun saved(percent: Int): String = "$percent% saved"
  override fun rate(percent: Int): String = "$percent% rate"
  override val to = "to"
  override val tool = "Tool"
  override fun working(type: String): String = "$type · working"
  override fun ready(type: String): String = "$type · ready"
  override fun off(type: String): String = "$type · off"
}
