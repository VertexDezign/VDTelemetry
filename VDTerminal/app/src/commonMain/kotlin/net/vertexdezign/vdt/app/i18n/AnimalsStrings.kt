package net.vertexdezign.vdt.app.i18n

/** The Animals app: the pen list and a pen's detail. */
interface AnimalsStrings {
  val waiting: String
  val empty: String
  val listTitle: String
  val selectPrompt: String
  fun penSubtitle(count: Int, max: Int): String

  val productivity: String
  val animals: String
  val food: String
  val conditions: String

  /** The animal table's column headers. */
  val columnAnimal: String
  val columnCount: String
  val columnAge: String
  val columnHealth: String
  val columnRepro: String
}
