package net.vertexdezign.vdt.app.i18n

/** Enhanced Loan System's section of the Finance app. */
interface LoansStrings {
  val noneRunning: String
  fun paidOff(count: Int): String
  val cannotManage: String
  val takeLoan: String

  /** The bank's terms. Amounts and rates arrive formatted. */
  val bank: String
  fun rate(rate: String, variable: Boolean): String
  fun upToYears(years: Int): String
  fun maxAmount(amount: String): String

  /** A running loan. */
  fun ofAmount(amount: String, rate: String): String
  fun monthsLeft(months: Int): String
  val repaid: String
  fun instalment(rate: String, interest: String): String
  val alreadyPaidExtra: String
  val nothingToPay: String
  val max: String
  val payOff: String
  fun extraPaymentNote(cap: String): String

  /** Taking a new one. */
  val amount: String
  val term: String
  fun years(years: Int): String
  fun monthly(amount: String, months: Int): String
  fun total(total: String, interest: String): String
  val borrow: String
}
