package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.StorageStrings

internal object DeStorage : StorageStrings {
  override val waiting = "Warte auf Lagerdaten…"
  override val empty = "Keine eigenen Lager"
  override val listTitle = "Lager"
  override val storageEmpty = "Dieses Lager ist leer"
  override val objects = "Objekte"
  override fun unloadDescription(title: String): String = "$title auslagern"
  override fun unloadTitle(title: String): String = "$title auslagern"
  override fun amountOf(amount: Int, cap: Int): String = "$amount von $cap"
  override val amount = "Menge"
  override val unload = "Auslagern"
  override fun fillTypes(count: Int): String = if (count == 1) "1 Sorte" else "$count Sorten"
}
