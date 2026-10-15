package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.StorageStrings

internal object EnStorage : StorageStrings {
  override val waiting = "Waiting for storage data…"
  override val empty = "No owned storages"
  override val listTitle = "Storage"
  override val storageEmpty = "This storage is empty"
  override val objects = "Objects"
  override fun unloadDescription(title: String): String = "unload $title"
  override fun unloadTitle(title: String): String = "Unload $title"
  override fun amountOf(amount: Int, cap: Int): String = "$amount of $cap"
  override val amount = "Amount"
  override val unload = "Unload"
  override fun fillTypes(count: Int): String = if (count == 1) "1 fill type" else "$count fill types"
}
