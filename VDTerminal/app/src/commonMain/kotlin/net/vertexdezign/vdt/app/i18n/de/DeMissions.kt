package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.MissionsStrings

internal object DeMissions : MissionsStrings {
  override val waiting = "Warte auf Auftragsdaten…"
  override val empty = "Keine Aufträge im Angebot"
  override fun active(active: Int, max: Int): String = "$active / $max AKTIV"
  override val yours = "Deine"
  override val onOffer = "Angebote"

  override val confirmTitle = "Auftrag aufgeben?"
  override fun confirmMessage(title: String, location: String): String =
    "„$title“ $location wird abgebrochen und bringt nichts ein."
  override val giveUp = "Aufgeben"
  override val accept = "Annehmen"
  override val withEquipment = "Mit Ausrüstung"
  override val collect = "Abschließen"
  override val cannotManage = "Du darfst die Aufträge dieses Hofs nicht verwalten"
  override val limitReached = "Dieser Hof hat bereits die maximale Anzahl Aufträge"

  override val progress = "Fortschritt"
  override val payout = "Auszahlung"
  override val reward = "Belohnung"
  override val timeLeft = "Verbleibende Zeit"

  override fun offeredCount(count: Int): String = "$count im Angebot"
  override val none = "Keine Aufträge"
  override fun readyToCollect(count: Int): String = "$count BEREIT ZUM ABSCHLUSS"
  override fun onOfferCaps(count: Int): String = "$count IM ANGEBOT"

  override val done = "Erledigt"
  override val failed = "Fehlgeschlagen"
  override val timedOut = "Abgelaufen"
  override val cancelled = "Abgebrochen"
  override val preparing = "Vorbereitung"

  override val expired = "abgelaufen"
  override fun daysHours(days: Int, hours: Int): String = "$days T $hours h"
  override fun hours(hours: Int): String = "$hours h"
  override fun minutes(minutes: Int): String = "$minutes min"
}
