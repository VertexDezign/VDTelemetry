package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.LoansStrings

internal object DeLoans : LoansStrings {
  override val noneRunning = "Keine laufenden Kredite"
  override fun paidOff(count: Int): String = if (count == 1) "1 Kredit abbezahlt" else "$count Kredite abbezahlt"
  override val cannotManage = "Du darfst die Kredite dieses Hofs nicht verwalten"
  override val takeLoan = "Kredit aufnehmen"

  override val bank = "BANK"
  override fun rate(rate: String, variable: Boolean): String = if (variable) "$rate% (variabel)" else "$rate%"
  override fun upToYears(years: Int): String = "bis $years J."
  override fun maxAmount(amount: String): String = "max. $amount"

  override fun ofAmount(amount: String, rate: String): String = "von $amount · $rate%"
  override fun monthsLeft(months: Int): String = "noch $months Mon."
  override val repaid = "Getilgt"
  override fun instalment(rate: String, interest: String): String = "$rate/Monat · $interest Zinsen"
  override val alreadyPaidExtra = "Dieser Kredit hatte dieses Jahr schon seine Sondertilgung"
  override val nothingToPay = "Gerade kann nichts getilgt werden"
  override val max = "Max"
  override val payOff = "Tilgen"
  override fun extraPaymentNote(cap: String): String = "Eine Sondertilgung pro Jahr, bis zu $cap dieses Kredits"

  override val amount = "BETRAG"
  override val term = "LAUFZEIT"
  override fun years(years: Int): String = "$years J."
  override fun monthly(amount: String, months: Int): String = "~$amount pro Monat über $months Monate"
  override fun total(total: String, interest: String): String = "Gesamt ~$total · $interest Zinsen"
  override val borrow = "Aufnehmen"
}
