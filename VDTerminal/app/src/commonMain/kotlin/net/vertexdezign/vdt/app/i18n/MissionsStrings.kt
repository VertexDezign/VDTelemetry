package net.vertexdezign.vdt.app.i18n

/** The Contracts app and its tile. */
interface MissionsStrings {
  val waiting: String
  val empty: String
  fun active(active: Int, max: Int): String
  val yours: String
  val onOffer: String

  val confirmTitle: String
  fun confirmMessage(title: String, location: String): String
  val giveUp: String
  val accept: String
  val withEquipment: String
  val collect: String
  val cannotManage: String
  val limitReached: String

  val progress: String
  val payout: String
  val reward: String
  val timeLeft: String

  /** The tile. */
  fun offeredCount(count: Int): String
  val none: String
  fun readyToCollect(count: Int): String
  fun onOfferCaps(count: Int): String

  val done: String
  val failed: String
  val timedOut: String
  val cancelled: String
  val preparing: String

  /** In-game time left, as the contract list prints it. */
  val expired: String
  fun daysHours(days: Int, hours: Int): String
  fun hours(hours: Int): String
  fun minutes(minutes: Int): String
}
