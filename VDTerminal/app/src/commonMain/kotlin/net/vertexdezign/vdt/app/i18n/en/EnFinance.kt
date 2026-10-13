package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.FinanceStrings

internal object EnFinance : FinanceStrings {
  override val waiting = "Waiting for finance data…"
  override val noFarm = "No farm"
  override val books = "Books"
  override val invoices = "Invoices"
  override val hideEmpty = "hide rows with no movement"

  override val balance = "Balance"
  override val loans = "Loans"
  override val perMonth = "Per month"
  override val loan = "Loan"
  override val interestPerDay = "Interest / day"
  override fun loanAmount(amount: String): String = "Loan $amount"

  override val activity = "ACTIVITY"
  override val noTransactions = "No transactions yet this session"
  override val transaction = "Transaction"

  override val loanCaps = "LOAN"
  override fun loanOfMax(target: String, max: String): String = "$target of $max max"
  override fun borrowAmount(amount: String): String = "borrow $amount"
  override fun repayAmount(amount: String): String = "repay $amount"
  override val borrow = "Borrow"
  override val repay = "Repay"
  override val reset = "Reset"
  override val cannotManageLoan = "You do not have the right to manage this farm's loan"
  override fun cannotAfford(amount: String, balance: String): String =
    "Repaying $amount needs more than the $balance on hand"
  override fun repayTitle(amount: String): String = "Repay $amount?"
  override fun repayMessage(left: String, step: String): String =
    "That leaves $left on hand — less than one $step step."

  override fun showMonths(count: Int): String = "Show $count months"
  override fun showAllMonths(count: Int): String = "Show all $count months"
  override val nothingMoved = "Nothing moved in these months"
  override val category = "Category"
  override val current = "current"
  override val total = "TOTAL"
}
