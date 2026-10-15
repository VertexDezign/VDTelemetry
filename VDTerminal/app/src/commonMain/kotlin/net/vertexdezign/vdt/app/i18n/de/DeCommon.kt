package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.CommonStrings

internal object DeCommon : CommonStrings {
  override val nothingMatches = "Keine Treffer"
  override val ascending = "Aufsteigend"
  override val descending = "Absteigend"
  override val showOnMap = "Auf Karte zeigen"

  override val notSorted = "nicht sortiert"
  override val sortedAscending = "aufsteigend sortiert"
  override val sortedDescending = "absteigend sortiert"

  override fun monthsShort(months: Int) = "$months Mon."
  override val cancel = "Abbrechen"
  override val selectEntry = "Eintrag wählen"
  override fun litres(level: String, capacity: String): String = "$level / $capacity l"
  override fun litre(amount: String): String = "$amount l"
  override val filter = "Filtern…"
  override val filterText = "Text"
  override fun moreMatches(count: Int): String = "$count weitere — weiter tippen"
  override fun remove(label: String): String = "$label entfernen"
  override val save = "Speichern"
  override val delete = "Löschen"
  override val create = "Erstellen"
  override val edit = "Bearbeiten"
  override val name = "Name"
  override val monthsShort = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
}
