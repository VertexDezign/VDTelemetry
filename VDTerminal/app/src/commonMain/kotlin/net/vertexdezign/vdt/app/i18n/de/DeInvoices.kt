package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.InvoicesStrings

internal object DeInvoices : InvoicesStrings {
  override val notInstalled = "FS25_Invoices ist nicht installiert"
  override val filterAll = "Alle"
  override val filterOwed = "Zu zahlen"
  override val filterOwing = "Ausstehend"
  override val newInvoice = "Neue Rechnung"
  override val nothingThisWay = "In dieser Richtung nichts"
  override val noOtherFarms = "Keine anderen Höfe zum Abrechnen — das braucht eine Mehrspielersitzung"
  override val none = "Noch keine Rechnungen"

  override val youOwe = "Du schuldest"
  override val youOweOverdue = "Du schuldest · überfällig"
  override val youAreOwed = "Dir wird geschuldet"
  override fun proposalsToAnswer(count: Int): String = if (count == 1) "Vorschlag offen" else "Vorschläge offen"
  override val viewOnly = "Nur Ansicht — du darfst die Rechnungen dieses Hofs nicht verwalten"

  override val statusPaid = "Bezahlt"
  override val statusAwaiting = "Wartet auf Zustimmung"
  override val statusOverdue = "Überfällig"
  override val statusPending = "Offen"
  override val penaltyDue = "Strafe fällig"
  override fun daysToPenalty(days: Int): String = if (days == 1) "1 Tag bis zur Strafe" else "$days Tage bis zur Strafe"

  override val lines = "POSTEN"
  override fun includesPenalty(amount: String): String = "Enthält $amount Verzugsstrafe"
  override fun penaltyTerms(rate: String, grace: Int, cap: String): String =
    " — $rate% pro Monat nach $grace Monat${if (grace == 1) "" else "en"}, höchstens $cap%"
  override fun workType(id: Int): String = "Arbeit $id"
  override val perThousandLitres = " / 1000 l"
  override fun vat(rate: String): String = "MwSt. $rate%"
  override fun field(id: Int): String = "Feld $id"

  override val net = "Netto"
  override val discount = "Rabatt"
  override val vatLabel = "MwSt."
  override val penalty = "Strafe"
  override val payerPays = "Zahler zahlt"
  override val issuerReceives = "Aussteller erhält"

  override fun pay(amount: String): String = "$amount zahlen"
  override val accept = "Annehmen"
  override val refuse = "Ablehnen"
  override val withdraw = "Zurückziehen"
  override fun cannotAfford(amount: String, balance: String): String =
    "$amount zu zahlen braucht mehr als die vorhandenen $balance"

  override fun payTitle(amount: String): String = "$amount zahlen?"
  override fun payTo(counterparty: String): String = "An $counterparty."
  override fun payLeaves(left: String): String = " Dann bleiben $left."
  override fun payTax(credit: String): String = " Sie erhalten $credit — der Rest ist Steuer."
  override val payConfirm = "Zahlen"
  override val refuseTitle = "Diesen Vorschlag ablehnen?"
  override fun refuseMessage(counterparty: String): String = "Er wird gelöscht. $counterparty müsste ihn neu stellen."
  override fun withdrawTitle(id: Int): String = "Rechnung #$id zurückziehen?"
  override val withdrawMessage = "Sie wird gelöscht statt storniert — der Mod behält danach keinen Eintrag."

  override fun proposalsWaiting(count: Int): String =
    if (count == 1) "1 Vorschlag wartet auf dich" else "$count Vorschläge warten auf dich"

  override val unitHectare = "ha"
  override val unitHour = "h"
  override val unitLitre = "l"
  override val unitPiece = "Stk."

  override val askToBeBilled = "Abrechnung anfragen"
  override val iDidTheWork = "Ich habe gearbeitet"
  override val theyDidTheWork = "Sie haben gearbeitet"
  override val proposalNote = "Sie werden um Zustimmung gebeten, bevor sie zahlbar wird."
  override val invoiceNote = "Sofort abgerechnet — sie kann bezahlt werden, sobald sie ankommt."
  override val whoDidTheWork = "Wer hat gearbeitet"
  override val whoToBill = "Wem in Rechnung stellen"
  override val linesLabel = "Posten"
  override val noLinesYet = "Noch leer — füge unten eine Arbeit hinzu."
  override val addLine = "Posten hinzufügen"
  override fun maxLines(max: Int): String = "Eine Rechnung hat höchstens $max Posten."
  override val discountCaps = "RABATT"
  override val totalCaps = "SUMME"
  override val vatNote = "Enthält MwSt. zu den Sätzen des Servers; der Aussteller erhält den Nettobetrag."
  override val sendProposal = "Vorschlag senden"
  override val sendInvoice = "Rechnung senden"
  override fun remove(name: String): String = "$name entfernen"
  override val percentOff = "% Rabatt"
  override val noteHint = "Notiz (optional)"
  override val addALine = "Posten hinzufügen"
  override val close = "Schließen"
  override val inGameOnly = "nur im Spiel — überträgt Waren"
}
