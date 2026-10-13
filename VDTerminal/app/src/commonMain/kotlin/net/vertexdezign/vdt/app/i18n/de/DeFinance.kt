package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.FinanceStrings

internal object DeFinance : FinanceStrings {
  override val waiting = "Warte auf Finanzdaten…"
  override val noFarm = "Kein Hof"
  override val books = "Bücher"
  override val invoices = "Rechnungen"
  override val hideEmpty = "Zeilen ohne Bewegung ausblenden"

  override val balance = "Kontostand"
  override val loans = "Kredite"
  override val perMonth = "Pro Monat"
  override val loan = "Kredit"
  override val interestPerDay = "Zinsen / Tag"
  override fun loanAmount(amount: String): String = "Kredit $amount"

  override val activity = "AKTIVITÄT"
  override val noTransactions = "Noch keine Buchungen in dieser Sitzung"
  override val transaction = "Buchung"

  override val loanCaps = "KREDIT"
  override fun loanOfMax(target: String, max: String): String = "$target von max. $max"
  override fun borrowAmount(amount: String): String = "$amount aufnehmen"
  override fun repayAmount(amount: String): String = "$amount tilgen"
  override val borrow = "Aufnehmen"
  override val repay = "Tilgen"
  override val reset = "Zurücksetzen"
  override val cannotManageLoan = "Du darfst den Kredit dieses Hofs nicht verwalten"
  override fun cannotAfford(amount: String, balance: String): String =
    "$amount zu tilgen braucht mehr als die vorhandenen $balance"
  override fun repayTitle(amount: String): String = "$amount tilgen?"
  override fun repayMessage(left: String, step: String): String =
    "Dann bleiben $left — weniger als ein Schritt von $step."

  override fun showMonths(count: Int): String = "$count Monate zeigen"
  override fun showAllMonths(count: Int): String = "Alle $count Monate zeigen"
  override val nothingMoved = "In diesen Monaten hat sich nichts bewegt"
  override val category = "Kategorie"
  override val current = "aktuell"
  override val total = "SUMME"
}
