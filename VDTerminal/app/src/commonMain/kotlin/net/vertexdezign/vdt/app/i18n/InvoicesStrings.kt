package net.vertexdezign.vdt.app.i18n

/** FS25_Invoices' view of the Finance app, its builder and its tile. */
interface InvoicesStrings {
  val notInstalled: String
  val filterAll: String
  val filterOwed: String
  val filterOwing: String
  val newInvoice: String
  val nothingThisWay: String
  val noOtherFarms: String
  val none: String

  val youOwe: String
  val youOweOverdue: String
  val youAreOwed: String
  fun proposalsToAnswer(count: Int): String
  val viewOnly: String

  val statusPaid: String
  val statusAwaiting: String
  val statusOverdue: String
  val statusPending: String
  val penaltyDue: String
  fun daysToPenalty(days: Int): String

  val lines: String

  /** The penalty note; amounts and rates arrive formatted. */
  fun includesPenalty(amount: String): String
  fun penaltyTerms(rate: String, grace: Int, cap: String): String
  fun workType(id: Int): String
  val perThousandLitres: String
  fun vat(rate: String): String
  fun field(id: Int): String

  val net: String
  val discount: String
  val vatLabel: String
  val penalty: String
  val payerPays: String
  val issuerReceives: String

  fun pay(amount: String): String
  val accept: String
  val refuse: String
  val withdraw: String
  fun cannotAfford(amount: String, balance: String): String

  fun payTitle(amount: String): String
  fun payTo(counterparty: String): String
  fun payLeaves(left: String): String
  fun payTax(credit: String): String
  val payConfirm: String
  val refuseTitle: String
  fun refuseMessage(counterparty: String): String
  fun withdrawTitle(id: Int): String
  val withdrawMessage: String

  fun proposalsWaiting(count: Int): String

  /** The unit tokens as the short labels a line reads with. */
  val unitHectare: String
  val unitHour: String
  val unitLitre: String
  val unitPiece: String

  /** The builder. */
  val askToBeBilled: String
  val iDidTheWork: String
  val theyDidTheWork: String
  val proposalNote: String
  val invoiceNote: String
  val whoDidTheWork: String
  val whoToBill: String
  val linesLabel: String
  val noLinesYet: String
  val addLine: String
  fun maxLines(max: Int): String
  val discountCaps: String
  val totalCaps: String
  val vatNote: String
  val sendProposal: String
  val sendInvoice: String
  fun remove(name: String): String
  val percentOff: String
  val noteHint: String
  val addALine: String
  val close: String
  val inGameOnly: String
}
