package net.vertexdezign.vdt.app.i18n

/** The Finance app's books: the headline figures, the loan, the month table and the money log. */
interface FinanceStrings {
  val waiting: String
  val noFarm: String
  val books: String
  val invoices: String
  val hideEmpty: String

  val balance: String
  val loans: String
  val perMonth: String
  val loan: String
  val interestPerDay: String
  fun loanAmount(amount: String): String

  val activity: String
  val noTransactions: String
  val transaction: String

  /** The loan editor. Amounts arrive formatted. */
  val loanCaps: String
  fun loanOfMax(target: String, max: String): String
  fun borrowAmount(amount: String): String
  fun repayAmount(amount: String): String
  val borrow: String
  val repay: String
  val reset: String
  val cannotManageLoan: String
  fun cannotAfford(amount: String, balance: String): String
  fun repayTitle(amount: String): String
  fun repayMessage(left: String, step: String): String

  /** The month table. */
  fun showMonths(count: Int): String
  fun showAllMonths(count: Int): String
  val nothingMoved: String
  val category: String
  val current: String
  val total: String
}
