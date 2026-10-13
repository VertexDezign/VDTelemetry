package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.CommonStrings

internal object EnCommon : CommonStrings {
  override val nothingMatches = "Nothing matches"
  override val ascending = "Ascending"
  override val descending = "Descending"
  override val showOnMap = "Show on map"

  override val notSorted = "not sorted"
  override val sortedAscending = "sorted ascending"
  override val sortedDescending = "sorted descending"

  override fun monthsShort(months: Int) = "$months mo"
  override val cancel = "Cancel"
  override val selectEntry = "Select an entry"
  override fun litres(level: String, capacity: String): String = "$level / $capacity L"
  override fun litre(amount: String): String = "$amount L"
}
