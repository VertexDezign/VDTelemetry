package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.MissionsStrings

internal object EnMissions : MissionsStrings {
  override val waiting = "Waiting for contract data…"
  override val empty = "No contracts on offer"
  override fun active(active: Int, max: Int): String = "$active / $max ACTIVE"
  override val yours = "Yours"
  override val onOffer = "On offer"

  override val confirmTitle = "Give up contract?"
  override fun confirmMessage(title: String, location: String): String =
    "\"$title\" $location will be cancelled and pays nothing."
  override val giveUp = "Give up"
  override val accept = "Accept"
  override val withEquipment = "With equipment"
  override val collect = "Collect"
  override val cannotManage = "You may not manage this farm's contracts"
  override val limitReached = "This farm is already running its maximum contracts"

  override val progress = "Progress"
  override val payout = "Payout"
  override val reward = "Reward"
  override val timeLeft = "Time left"

  override fun offeredCount(count: Int): String = "$count on offer"
  override val none = "No contracts"
  override fun readyToCollect(count: Int): String = "$count READY TO COLLECT"
  override fun onOfferCaps(count: Int): String = "$count ON OFFER"

  override val done = "Done"
  override val failed = "Failed"
  override val timedOut = "Timed out"
  override val cancelled = "Cancelled"
  override val preparing = "Preparing"

  override val expired = "expired"
  override fun daysHours(days: Int, hours: Int): String = "${days}d ${hours}h"
  override fun hours(hours: Int): String = "${hours}h"
  override fun minutes(minutes: Int): String = "${minutes}m"
}
