package net.vertexdezign.vdt.app.i18n

/** Words more than one app uses, said the same way everywhere. */
interface CommonStrings {
  val nothingMatches: String
  val ascending: String
  val descending: String
  val showOnMap: String

  /** A sortable column header's state, read out for a screen reader. */
  val notSorted: String
  val sortedAscending: String
  val sortedDescending: String

  /** A count of the game's months, short: an animal's age, a machine's. */
  fun monthsShort(months: Int): String
  val cancel: String
  val selectEntry: String

  /** A fill level against its capacity, both already formatted. */
  fun litres(level: String, capacity: String): String

  /** An amount in litres, already formatted. */
  fun litre(amount: String): String

  /** A filter box: its placeholder, its free-text token, and its overflow line. */
  val filter: String
  val filterText: String
  fun moreMatches(count: Int): String
  fun remove(label: String): String

  /** Dialog and row actions. */
  val save: String
  val delete: String
  val create: String
  val edit: String
  val name: String

  /** The calendar months, short, January first. */
  val monthsShort: List<String>
}
