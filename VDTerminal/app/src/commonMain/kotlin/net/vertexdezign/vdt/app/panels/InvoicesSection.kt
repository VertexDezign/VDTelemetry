package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.ConfirmDialog
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Invoice
import net.vertexdezign.vdt.model.InvoiceLine
import net.vertexdezign.vdt.model.InvoicesData
import net.vertexdezign.vdt.model.PenaltyTerms

/** Which invoices the list is showing. */
private enum class InvoiceFilter(val label: String) {
  ALL("All"),
  INCOMING("Owed"),
  OUTGOING("Owing"),
}

/**
 * The Invoices view of the Finance app: what this farm owes, what it is owed, the proposals waiting on
 * it, and the four things it can do about any of them — plus raising a new invoice.
 *
 * A null [data] means the channel is absent, which for this one mod means **FS25_Invoices is not
 * installed** rather than "no data yet": the file only ever exists when it is. An installed mod with
 * nothing to show sends an empty list, and that is what singleplayer will always look like, because an
 * invoice needs two farms.
 *
 * [balance] comes from the finance channel rather than this one. Whether an invoice is affordable
 * changes far faster than this channel writes, so the mod deliberately says nothing about it and the
 * Pay button is greyed here instead.
 */
@Composable
fun InvoicesSection(
  data: InvoicesData?,
  balance: Long?,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  if (data == null) {
    Centered("FS25_Invoices is not installed", modifier)
    return
  }

  var filter by remember { mutableStateOf(InvoiceFilter.ALL) }
  var expandedId by remember { mutableStateOf<Int?>(null) }
  var building by remember { mutableStateOf(false) }
  // The pending confirmation, and which invoice it belongs to. Held here rather than per row so the
  // dialog covers the section, and so it survives the row scrolling out from under it.
  var confirming by remember { mutableStateOf<Pair<Invoice, ClientMessage>?>(null) }

  val rows = remember(data.invoices, filter) {
    when (filter) {
      InvoiceFilter.ALL -> data.invoices
      InvoiceFilter.INCOMING -> data.invoices.filter { it.isIncoming }
      InvoiceFilter.OUTGOING -> data.invoices.filter { it.isOutgoing }
    }
      // Newest first, and anything settled sinks below anything still live: a paid invoice is a
      // receipt, and the list is a to-do list.
      .sortedWith(compareBy({ it.isPaid }, { -it.id }))
  }

  Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      InvoicesHeadline(data)

      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        InvoiceFilter.entries.forEach { candidate ->
          FilterChip(
            label = candidate.label,
            active = filter == candidate,
            onClick = { filter = candidate },
          )
        }
        Box(Modifier.weight(1f))
        if (data.canIssue) {
          FinanceButton("New invoice", VdtColors.Green, { building = true })
        }
      }

      if (rows.isEmpty()) {
        Centered(
          when {
            data.invoices.isNotEmpty() -> "Nothing in this direction"
            data.farms.isEmpty() -> "No other farms to invoice — this needs a multiplayer session"
            else -> "No invoices yet"
          },
        )
      } else {
        Column(
          Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          rows.forEach { invoice ->
            InvoiceRow(
              invoice = invoice,
              expanded = expandedId == invoice.id,
              balance = balance,
              terms = data.penaltyTerms,
              onToggle = { expandedId = if (expandedId == invoice.id) null else invoice.id },
              onConfirm = { confirming = invoice to it },
              onCommand = onCommand,
            )
          }
        }
      }
    }

    confirming?.let { (invoice, message) ->
      val (title, body, label) = confirmCopy(message, invoice, balance)
      ConfirmDialog(
        title = title,
        message = body,
        confirmLabel = label,
        onConfirm = {
          onCommand(message)
          confirming = null
        },
        onDismiss = { confirming = null },
      )
    }

    if (building) {
      InvoiceBuilder(
        data = data,
        onDismiss = { building = false },
        onCommand = {
          onCommand(it)
          building = false
        },
      )
    }
  }
}

/** What this farm owes, what it is owed, and anything waiting on an answer. */
@Composable
private fun InvoicesHeadline(data: InvoicesData) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(20.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FinanceFigure(
      label = if (data.hasOverdue) "You owe · overdue" else "You owe",
      value = formatMoney(data.totalOwed),
      color = when {
        data.hasOverdue -> VdtColors.Red
        data.totalOwed > 0 -> VdtColors.Amber
        else -> VdtColors.DarkGray
      },
    )
    FinanceFigure(
      label = "You're owed",
      value = formatMoney(data.totalOwing),
      color = if (data.totalOwing > 0) VdtColors.AccentText else VdtColors.DarkGray,
    )
    val pending = data.pendingProposals.size
    if (pending > 0) {
      FinanceFigure(
        label = if (pending == 1) "Proposal to answer" else "Proposals to answer",
        value = pending.toString(),
        color = VdtColors.Amber,
      )
    }
    if (!data.canManage) {
      Text(
        "View only — you do not have the right to manage this farm's invoices",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )
    }
  }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
  Text(
    label.uppercase(),
    color = if (active) VdtColors.White else VdtColors.DarkGray,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (active) VdtColors.Green else VdtColors.TrackGray)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 5.dp),
  )
}

/** The status word the mod's own list prints, and the ink for it. */
private fun statusOf(invoice: Invoice): Pair<String, Color> = when {
  invoice.isPaid -> "Paid" to VdtColors.DarkGray

  invoice.isProposal -> "Awaiting approval" to VdtColors.Amber

  invoice.overdue -> "Overdue" to VdtColors.Red

  invoice.isPayable -> "Pending" to VdtColors.TextDark

  // A state this build does not know: print the raw token rather than inventing a label for it.
  else -> invoice.state to VdtColors.DarkGray
}

@Composable
private fun InvoiceRow(
  invoice: Invoice,
  expanded: Boolean,
  balance: Long?,
  terms: PenaltyTerms?,
  onToggle: () -> Unit,
  onConfirm: (ClientMessage) -> Unit,
  onCommand: (ClientMessage) -> Unit,
) {
  val (status, statusColor) = statusOf(invoice)

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = if (expanded) 0.9f else 0.6f)),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onToggle)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Which way the money goes, in one glyph — the word "incoming" is ambiguous about whether it is
      // the invoice or the money that is arriving, and here they point opposite ways.
      Text(
        if (invoice.isIncoming) "▼" else "▲",
        color = if (invoice.isIncoming) VdtColors.Red else VdtColors.AccentText,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
          invoice.counterpartyLabel,
          color = VdtColors.TextDark,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            listOfNotNull("#${invoice.id}", invoice.date, status).joinToString(" · "),
            color = statusColor,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          // The mod's list carries a discount column of its own, so a rebate is visible without
          // opening anything. Its own colour, not the status one -- it is a saving, not a state.
          val discount = invoice.discountTotal
          if (discount > 0) {
            Text(
              "−${formatMoney(discount)}",
              color = VdtColors.AccentText,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
            )
          }
        }
      }
      Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
          // The tax-inclusive total plus any penalty, in BOTH directions -- what the mod's own list
          // row prints. What the issuer actually banks (credit) is smaller and lives in the detail,
          // where there is room to explain the VAT that goes nowhere.
          formatMoney(invoice.totalDue),
          color = if (invoice.isIncoming) VdtColors.Red else VdtColors.AccentText,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        // The countdown only means something while it is still running; once a penalty exists the
        // amount above already carries it.
        invoice.daysUntilPenalty?.takeIf { invoice.isIncoming && invoice.isPayable }?.let { days ->
          Text(
            if (days == 0) "penalty due" else "$days ${if (days == 1) "day" else "days"} to penalty",
            color = if (days <= 1) VdtColors.Amber else VdtColors.DarkGray,
            fontSize = 9.sp,
            maxLines = 1,
          )
        }
      }
    }

    if (expanded) {
      InvoiceDetail(invoice, balance, terms, onConfirm, onCommand)
    }
  }
}

@Composable
private fun InvoiceDetail(
  invoice: Invoice,
  balance: Long?,
  terms: PenaltyTerms?,
  onConfirm: (ClientMessage) -> Unit,
  onCommand: (ClientMessage) -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
      Text("LINES", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
    invoice.lines.forEach { LineRow(it) }

    Totals(invoice)

    invoice.penalty?.let { penalty ->
      Text(
        buildString {
          append("Includes ${formatMoney(penalty)} of late penalty")
          terms?.takeIf { it.enabled }?.let {
            append(" — ${trimPercent(it.ratePercent)}% a month after ${it.gracePeriods} month")
            if (it.gracePeriods != 1) append("s")
            append(", capped at ${trimPercent(it.capPercent)}%")
          }
        },
        color = VdtColors.Red,
        fontSize = 10.sp,
      )
    }

    InvoiceActions(invoice, balance, onConfirm, onCommand)
  }
}

@Composable
private fun LineRow(line: InvoiceLine) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        line.name?.takeIf { it.isNotBlank() } ?: "Work type ${line.workTypeId}",
        color = VdtColors.TextDark,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        buildString {
          append(formatQuantity(line.quantity))
          append(" ")
          append(unitLabel(line.unit))
          append(" × ")
          append(formatQuantity(line.price))
          // A litre line is priced per thousand, so the multiplication above does not read as
          // arithmetic unless it says so.
          if (line.unit == "liter") append(" / 1000 l")
          line.discountRate?.let { append(" · −${trimPercent(it * 100)}%") }
          line.vatRate?.let { append(" · VAT ${trimPercent(it * 100)}%") }
          line.fieldId?.let { append(" · field $it") }
        },
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      line.note?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = VdtColors.DarkGray, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
    Text(
      formatMoney(line.amount),
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.End,
      maxLines = 1,
    )
  }
}

/**
 * The two figures a payment actually moves. They differ by the VAT, which the mod destroys rather than
 * paying to anyone — so showing one number would be wrong for one of the two parties, whichever we
 * picked.
 */
@Composable
private fun Totals(invoice: Invoice) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    TotalLine("Net", formatMoney(invoice.totalNet), VdtColors.DarkGray)
    // Between net and VAT, where the mod's own detail dialog puts it. Informational: the discount is
    // already inside every figure here, so this says what was given away, not what is still owed.
    invoice.discountTotal.takeIf { it > 0 }?.let {
      TotalLine("Discount", "−${formatMoney(it)}", VdtColors.AccentText)
    }
    invoice.vat?.let { TotalLine("VAT", formatMoney(it), VdtColors.DarkGray) }
    invoice.penalty?.let { TotalLine("Penalty", formatMoney(it), VdtColors.Red) }
    TotalLine("Payer pays", formatMoney(invoice.totalDue), VdtColors.TextDark, bold = true)
    TotalLine("Issuer receives", formatMoney(invoice.credit), VdtColors.TextDark, bold = true)
  }
}

@Composable
private fun TotalLine(label: String, value: String, color: Color, bold: Boolean = false) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
      label,
      color = color,
      fontSize = if (bold) 11.sp else 10.sp,
      fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
    Text(
      value,
      color = color,
      fontSize = if (bold) 11.sp else 10.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

/**
 * The buttons, driven purely by what the mod said this player may do — no state machine restated here.
 * The single exception is affordability, which the channel deliberately does not carry.
 *
 * Anything that moves money or destroys a record goes through [onConfirm] rather than straight to
 * [onCommand]; the dialog itself is raised by the section, so it covers the whole view rather than the
 * one row it was triggered from.
 */
@Composable
private fun InvoiceActions(
  invoice: Invoice,
  balance: Long?,
  onConfirm: (ClientMessage) -> Unit,
  onCommand: (ClientMessage) -> Unit,
) {
  if (invoice.actions.isEmpty()) return

  val affordable = balance == null || balance >= invoice.totalDue

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (invoice.canPay) {
        FinanceButton(
          label = "Pay ${formatMoney(invoice.totalDue)}",
          color = VdtColors.Green,
          onClick = { onConfirm(ClientMessage.PayInvoice(invoice.id)) },
          enabled = affordable,
        )
      }
      if (invoice.canValidate) {
        // Not destructive and not a payment: accepting a proposal only turns it into a real invoice
        // for the other farm to settle, so it goes straight through.
        FinanceButton("Accept", VdtColors.Green, { onCommand(ClientMessage.ValidateProposal(invoice.id)) })
      }
      if (invoice.canRefuse) {
        FinanceButton("Refuse", VdtColors.Red, { onConfirm(ClientMessage.RefuseProposal(invoice.id)) })
      }
      if (invoice.canCancel) {
        FinanceButton("Withdraw", VdtColors.Red, { onConfirm(ClientMessage.CancelInvoice(invoice.id)) })
      }
    }

    if (invoice.canPay && !affordable) {
      Text(
        "Paying ${formatMoney(invoice.totalDue)} needs more than the ${formatMoney(balance)} on hand",
        color = VdtColors.Red,
        fontSize = 10.sp,
      )
    }
  }
}

/** Wording for the three actions that move money or destroy a record. */
private fun confirmCopy(message: ClientMessage, invoice: Invoice, balance: Long?): Triple<String, String, String> =
  when (message) {
    is ClientMessage.PayInvoice ->
      Triple(
        "Pay ${formatMoney(invoice.totalDue)}?",
        buildString {
          append("To ${invoice.counterpartyLabel}.")
          balance?.let { append(" That leaves ${formatMoney(it - invoice.totalDue)} on hand.") }
          // The gap is the mod's destroyed VAT, and it is surprising enough to name up front rather
          // than leave someone to spot afterwards.
          if (invoice.credit != invoice.totalDue) {
            append(" They receive ${formatMoney(invoice.credit)} — the rest is tax.")
          }
        },
        "Pay",
      )

    is ClientMessage.RefuseProposal ->
      Triple(
        "Refuse this proposal?",
        "It will be deleted. ${invoice.counterpartyLabel} would have to raise it again.",
        "Refuse",
      )

    else ->
      Triple(
        "Withdraw invoice #${invoice.id}?",
        "It will be deleted rather than marked cancelled — the mod keeps no record of it afterwards.",
        "Withdraw",
      )
  }

// ---- Widget --------------------------------------------------------------------------------------

/**
 * The glanceable half: what this farm owes, what it is owed, and whether anything needs answering.
 * The full view is a menu; this is what somebody checks between fields.
 */
@Composable
fun InvoicesSummary(data: InvoicesData?, modifier: Modifier = Modifier) {
  Panel(title = "Invoices", icon = Icons.AutoMirrored.Filled.ReceiptLong, modifier = modifier) {
    if (data == null) {
      Centered("FS25_Invoices is not installed")
      return@Panel
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      FinanceFigure(
        label = if (data.hasOverdue) "You owe · overdue" else "You owe",
        value = formatMoney(data.totalOwed),
        color = when {
          data.hasOverdue -> VdtColors.Red
          data.totalOwed > 0 -> VdtColors.Amber
          else -> VdtColors.DarkGray
        },
      )
      FinanceFigure(
        label = "You're owed",
        value = formatMoney(data.totalOwing),
        color = if (data.totalOwing > 0) VdtColors.AccentText else VdtColors.DarkGray,
      )
      val pending = data.pendingProposals.size
      if (pending > 0) {
        Text(
          "$pending proposal${if (pending == 1) "" else "s"} waiting on you",
          color = VdtColors.Amber,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

// ---- Formatting ----------------------------------------------------------------------------------

/** The mod's unit tokens as the short labels a line reads with. */
internal fun unitLabel(unit: String): String = when (unit) {
  "hectare" -> "ha"
  "hour" -> "h"
  "liter" -> "l"
  else -> "pc"
}

/**
 * A quantity or unit price with at most two decimals and no trailing zeros — `3.5`, `2`, `0.55`. These
 * are the one part of this channel that is deliberately not whole currency units, so they cannot go
 * through [formatMoney].
 */
internal fun formatQuantity(value: Double): String {
  if (value.isNaN() || value.isInfinite()) return "—"
  // Built from the rounded hundredths rather than by formatting the double: wasm has no
  // java.util.Formatter, and Double.toString() would print 1.7000000000000002 for a price the mod
  // stores as 1.7. Sign is handled separately so -0.55 does not come out as "0.55".
  val negative = value < 0
  val hundredths = kotlin.math.round(kotlin.math.abs(value) * 100).toLong()
  val whole = hundredths / 100
  val rest = hundredths % 100
  val digits = when {
    rest == 0L -> formatMoney(whole)
    rest % 10 == 0L -> "${formatMoney(whole)}.${rest / 10}"
    else -> "${formatMoney(whole)}.${rest.toString().padStart(2, '0')}"
  }
  return if (negative && hundredths != 0L) "-$digits" else digits
}

/** A percentage with no pointless `.0`. */
internal fun trimPercent(value: Double): String = formatQuantity(value)
