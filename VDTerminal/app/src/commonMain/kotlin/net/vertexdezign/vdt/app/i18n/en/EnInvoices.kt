package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.InvoicesStrings

internal object EnInvoices : InvoicesStrings {
  override val notInstalled = "FS25_Invoices is not installed"
  override val filterAll = "All"
  override val filterOwed = "Owed"
  override val filterOwing = "Owing"
  override val newInvoice = "New invoice"
  override val nothingThisWay = "Nothing in this direction"
  override val noOtherFarms = "No other farms to invoice — this needs a multiplayer session"
  override val none = "No invoices yet"

  override val youOwe = "You owe"
  override val youOweOverdue = "You owe · overdue"
  override val youAreOwed = "You're owed"
  override fun proposalsToAnswer(count: Int): String = if (count == 1) "Proposal to answer" else "Proposals to answer"
  override val viewOnly = "View only — you do not have the right to manage this farm's invoices"

  override val statusPaid = "Paid"
  override val statusAwaiting = "Awaiting approval"
  override val statusOverdue = "Overdue"
  override val statusPending = "Pending"
  override val penaltyDue = "penalty due"
  override fun daysToPenalty(days: Int): String = if (days == 1) "1 day to penalty" else "$days days to penalty"

  override val lines = "LINES"
  override fun includesPenalty(amount: String): String = "Includes $amount of late penalty"
  override fun penaltyTerms(rate: String, grace: Int, cap: String): String =
    " — $rate% a month after $grace month${if (grace == 1) "" else "s"}, capped at $cap%"
  override fun workType(id: Int): String = "Work type $id"
  override val perThousandLitres = " / 1000 l"
  override fun vat(rate: String): String = "VAT $rate%"
  override fun field(id: Int): String = "field $id"

  override val net = "Net"
  override val discount = "Discount"
  override val vatLabel = "VAT"
  override val penalty = "Penalty"
  override val payerPays = "Payer pays"
  override val issuerReceives = "Issuer receives"

  override fun pay(amount: String): String = "Pay $amount"
  override val accept = "Accept"
  override val refuse = "Refuse"
  override val withdraw = "Withdraw"
  override fun cannotAfford(amount: String, balance: String): String =
    "Paying $amount needs more than the $balance on hand"

  override fun payTitle(amount: String): String = "Pay $amount?"
  override fun payTo(counterparty: String): String = "To $counterparty."
  override fun payLeaves(left: String): String = " That leaves $left on hand."
  override fun payTax(credit: String): String = " They receive $credit — the rest is tax."
  override val payConfirm = "Pay"
  override val refuseTitle = "Refuse this proposal?"
  override fun refuseMessage(counterparty: String): String =
    "It will be deleted. $counterparty would have to raise it again."
  override fun withdrawTitle(id: Int): String = "Withdraw invoice #$id?"
  override val withdrawMessage =
    "It will be deleted rather than marked cancelled — the mod keeps no record of it afterwards."

  override fun proposalsWaiting(count: Int): String =
    if (count == 1) "1 proposal waiting on you" else "$count proposals waiting on you"

  override val unitHectare = "ha"
  override val unitHour = "h"
  override val unitLitre = "l"
  override val unitPiece = "pc"

  override val askToBeBilled = "Ask to be billed"
  override val iDidTheWork = "I did the work"
  override val theyDidTheWork = "They did the work"
  override val proposalNote = "They will be asked to approve it before it becomes payable."
  override val invoiceNote = "Billed immediately — they can pay it as soon as it arrives."
  override val whoDidTheWork = "Who did the work"
  override val whoToBill = "Who to bill"
  override val linesLabel = "Lines"
  override val noLinesYet = "Nothing on it yet — add a work type below."
  override val addLine = "Add line"
  override fun maxLines(max: Int): String = "An invoice can carry at most $max lines."
  override val discountCaps = "DISCOUNT"
  override val totalCaps = "TOTAL"
  override val vatNote = "Includes VAT at the server's rates; the issuer receives the net amount."
  override val sendProposal = "Send proposal"
  override val sendInvoice = "Send invoice"
  override fun remove(name: String): String = "Remove $name"
  override val percentOff = "% off"
  override val noteHint = "note (optional)"
  override val addALine = "Add a line"
  override val close = "Close"
  override val inGameOnly = "built in-game only — it transfers goods"
}
