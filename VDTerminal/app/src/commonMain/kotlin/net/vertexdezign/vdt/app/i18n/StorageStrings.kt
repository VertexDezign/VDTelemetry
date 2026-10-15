package net.vertexdezign.vdt.app.i18n

/** The Storage app: owned silos, sheds and object storages. */
interface StorageStrings {
  val waiting: String
  val empty: String
  val listTitle: String
  val storageEmpty: String
  val objects: String
  fun unloadDescription(title: String): String
  fun unloadTitle(title: String): String
  fun amountOf(amount: Int, cap: Int): String
  val amount: String
  val unload: String
  fun fillTypes(count: Int): String
}
