package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.LoansStrings

internal object EnLoans : LoansStrings {
  override val noneRunning = "No loans running"
  override fun paidOff(count: Int): String = if (count == 1) "1 loan paid off" else "$count loans paid off"
  override val cannotManage = "You do not have the right to manage this farm's loans"
  override val takeLoan = "Take a loan"

  override val bank = "BANK"
  override fun rate(rate: String, variable: Boolean): String = if (variable) "$rate% (variable)" else "$rate%"
  override fun upToYears(years: Int): String = "up to $years yr"
  override fun maxAmount(amount: String): String = "max $amount"

  override fun ofAmount(amount: String, rate: String): String = "of $amount · $rate%"
  override fun monthsLeft(months: Int): String = "$months mo left"
  override val repaid = "Repaid"
  override fun instalment(rate: String, interest: String): String = "$rate/mo · $interest interest"
  override val alreadyPaidExtra = "This loan has already had its extra payment this year"
  override val nothingToPay = "Nothing can be paid off right now"
  override val max = "Max"
  override val payOff = "Pay off"
  override fun extraPaymentNote(cap: String): String = "One extra payment a year, up to $cap of this loan"

  override val amount = "AMOUNT"
  override val term = "TERM"
  override fun years(years: Int): String = "$years yr"
  override fun monthly(amount: String, months: Int): String = "~$amount a month for $months months"
  override fun total(total: String, interest: String): String = "Total ~$total · $interest interest"
  override val borrow = "Borrow"
}
