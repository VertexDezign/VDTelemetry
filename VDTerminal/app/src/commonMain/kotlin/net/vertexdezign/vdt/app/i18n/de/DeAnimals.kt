package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.AnimalsStrings

internal object DeAnimals : AnimalsStrings {
  override val waiting = "Warte auf Tierdaten…"
  override val empty = "Keine eigenen Tiere"
  override val listTitle = "Ställe"
  override val selectPrompt = "Stall wählen"
  override fun penSubtitle(count: Int, max: Int): String = "$count / $max Tiere"

  override val productivity = "Produktivität"
  override val animals = "Tiere"
  override val food = "Futter"
  override val conditions = "Versorgung"

  override val columnAnimal = "Tier"
  override val columnCount = "Anzahl"
  override val columnAge = "Alter"
  override val columnHealth = "Gesundheit"
  override val columnRepro = "Fortpfl."
}
