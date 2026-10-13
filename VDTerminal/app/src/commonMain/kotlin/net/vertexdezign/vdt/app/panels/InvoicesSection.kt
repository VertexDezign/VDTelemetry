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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Invoice
import net.vertexdezign.vdt.model.InvoiceLine
import net.vertexdezign.vdt.model.InvoicesData
import net.vertexdezign.vdt.model.PenaltyTerms

/** Which invoices the list is showing. */
private enum class InvoiceFilter {
  ALL,
  INCOMING,
  OUTGOING,
  ;

  fun label(strings: Strings): String = when (this) {
    ALL -> strings.invoices.filterAll
    INCOMING -> strings.invoices.filterOwed
    OUTGOING -> strings.invoices.filterOwing
  }
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
  val strings = LocalStrings.current
  val s = strings.invoices
  if (data == null) {
    Centered(s.notInstalled, modifier)
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
            label = candidate.label(strings),
            active = filter == candidate,
            onClick = { filter = candidate },
          )
        }
        Box(Modifier.weight(1f))
        if (data.canIssue) {
          FinanceButton(s.newInvoice, VdtColors.Green, { building = true })
        }
      }

      if (rows.isEmpty()) {
        Centered(
          when {
            data.invoices.isNotEmpty() -> s.nothingThisWay
            data.farms.isEmpty() -> s.noOtherFarms
            else -> s.none
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
      val (title, body, label) = confirmCopy(strings, message, invoice, balance)
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
  val numbers = LocalStrings.current.numbers
  val s = LocalStrings.current.invoices
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface.copy(alpha = 0.6f))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(20.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FinanceFigure(
      label = if (data.hasOverdue) s.youOweOverdue else s.youOwe,
      value = numbers.formatMoney(data.totalOwed),
      color = when {
        data.hasOverdue -> VdtColors.Red
        data.totalOwed > 0 -> VdtColors.Amber
        else -> VdtColors.DarkGray
      },
    )
    FinanceFigure(
      label = s.youAreOwed,
      value = numbers.formatMoney(data.totalOwing),
      color = if (data.totalOwing > 0) VdtColors.AccentText else VdtColors.DarkGray,
    )
    val pending = data.pendingProposals.size
    if (pending > 0) {
      FinanceFigure(
        label = s.proposalsToAnswer(pending),
        value = pending.toString(),
        color = VdtColors.Amber,
      )
    }
    if (!data.canManage) {
      Text(
        s.viewOnly,
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )
    }
  }
}

/** The status word the mod's own list prints, and the ink for it. */
@Composable
@ReadOnlyComposable
private fun statusOf(invoice: Invoice): Pair<String, Color> = when {
  invoice.isPaid -> LocalStrings.current.invoices.statusPaid to VdtColors.DarkGray

  invoice.isProposal -> LocalStrings.current.invoices.statusAwaiting to VdtColors.Amber

  invoice.overdue -> LocalStrings.current.invoices.statusOverdue to VdtColors.Red

  invoice.isPayable -> LocalStrings.current.invoices.statusPending to VdtColors.TextDark

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
  val numbers = LocalStrings.current.numbers
  val s = LocalStrings.current.invoices
  val (status, statusColor) = statusOf(invoice)

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface.copy(alpha = if (expanded) 0.9f else 0.6f)),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onToggle)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Which way the money goes, in one mark — the word "incoming" is ambiguous about whether it is
      // the invoice or the money that is arriving, and here they point opposite ways. An Icon, not a
      // text arrow: the wasm build ships no font fallback, so a glyph outside the bundled font's
      // coverage renders as tofu (see AnimalsPanel's SortArrow, which is why that one is an Icon).
      Icon(
        if (invoice.isIncoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
        contentDescription = if (invoice.isIncoming) s.youOwe else s.youAreOwed,
        tint = if (invoice.isIncoming) VdtColors.Red else VdtColors.AccentText,
        modifier = Modifier.size(13.dp),
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
              "-${numbers.formatMoney(discount)}",
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
          numbers.formatMoney(invoice.totalDue),
          color = if (invoice.isIncoming) VdtColors.Red else VdtColors.AccentText,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        // The countdown only means something while it is still running; once a penalty exists the
        // amount above already carries it.
        invoice.daysUntilPenalty?.takeIf { invoice.isIncoming && invoice.isPayable }?.let { days ->
          Text(
            if (days == 0) s.penaltyDue else s.daysToPenalty(days),
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
  val numbers = LocalStrings.current.numbers
  val s = LocalStrings.current.invoices
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
      Text(s.lines, color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
    invoice.lines.forEach { LineRow(it) }

    Totals(invoice)

    invoice.penalty?.let { penalty ->
      Text(
        buildString {
          append(s.includesPenalty(numbers.formatMoney(penalty)))
          terms?.takeIf { it.enabled }?.let {
            append(
              s.penaltyTerms(numbers.trimPercent(it.ratePercent), it.gracePeriods, numbers.trimPercent(it.capPercent)),
            )
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
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val s = strings.invoices
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        line.name?.takeIf { it.isNotBlank() } ?: s.workType(line.workTypeId),
        color = VdtColors.TextDark,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        buildString {
          append(numbers.formatQuantity(line.quantity))
          append(" ")
          append(unitLabel(strings, line.unit))
          append(" × ")
          append(numbers.formatQuantity(line.price))
          // A litre line is priced per thousand, so the multiplication above does not read as
          // arithmetic unless it says so.
          if (line.unit == "liter") append(s.perThousandLitres)
          line.discountRate?.let { append(" · -${numbers.trimPercent(it * 100)}%") }
          line.vatRate?.let { append(" · ${s.vat(numbers.trimPercent(it * 100))}") }
          line.fieldId?.let { append(" · ${s.field(it)}") }
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
      numbers.formatMoney(line.amount),
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
  val numbers = LocalStrings.current.numbers
  val s = LocalStrings.current.invoices
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    TotalLine(s.net, numbers.formatMoney(invoice.totalNet), VdtColors.DarkGray)
    // Between net and VAT, where the mod's own detail dialog puts it. Informational: the discount is
    // already inside every figure here, so this says what was given away, not what is still owed.
    invoice.discountTotal.takeIf { it > 0 }?.let {
      TotalLine(s.discount, "-${numbers.formatMoney(it)}", VdtColors.AccentText)
    }
    invoice.vat?.let { TotalLine(s.vatLabel, numbers.formatMoney(it), VdtColors.DarkGray) }
    invoice.penalty?.let { TotalLine(s.penalty, numbers.formatMoney(it), VdtColors.Red) }
    TotalLine(s.payerPays, numbers.formatMoney(invoice.totalDue), VdtColors.TextDark, bold = true)
    TotalLine(s.issuerReceives, numbers.formatMoney(invoice.credit), VdtColors.TextDark, bold = true)
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
  val numbers = LocalStrings.current.numbers
  val s = LocalStrings.current.invoices
  if (invoice.actions.isEmpty()) return

  val affordable = balance == null || balance >= invoice.totalDue

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (invoice.canPay) {
        FinanceButton(
          label = s.pay(numbers.formatMoney(invoice.totalDue)),
          color = VdtColors.Green,
          onClick = { onConfirm(ClientMessage.PayInvoice(invoice.id)) },
          enabled = affordable,
        )
      }
      if (invoice.canValidate) {
        // Not destructive and not a payment: accepting a proposal only turns it into a real invoice
        // for the other farm to settle, so it goes straight through.
        FinanceButton(s.accept, VdtColors.Green, { onCommand(ClientMessage.ValidateProposal(invoice.id)) })
      }
      if (invoice.canRefuse) {
        FinanceButton(s.refuse, VdtColors.Red, { onConfirm(ClientMessage.RefuseProposal(invoice.id)) })
      }
      if (invoice.canCancel) {
        FinanceButton(s.withdraw, VdtColors.Red, { onConfirm(ClientMessage.CancelInvoice(invoice.id)) })
      }
    }

    if (invoice.canPay && !affordable) {
      Text(
        s.cannotAfford(numbers.formatMoney(invoice.totalDue), numbers.formatMoney(balance)),
        color = VdtColors.Red,
        fontSize = 10.sp,
      )
    }
  }
}

/** Wording for the three actions that move money or destroy a record. */
private fun confirmCopy(
  strings: Strings,
  message: ClientMessage,
  invoice: Invoice,
  balance: Long?,
): Triple<String, String, String> {
  val numbers = strings.numbers
  val s = strings.invoices
  return when (message) {
    is ClientMessage.PayInvoice ->
      Triple(
        s.payTitle(numbers.formatMoney(invoice.totalDue)),
        buildString {
          append(s.payTo(invoice.counterpartyLabel))
          balance?.let { append(s.payLeaves(numbers.formatMoney(it - invoice.totalDue))) }
          // The gap is the mod's destroyed VAT, and it is surprising enough to name up front rather
          // than leave someone to spot afterwards.
          if (invoice.credit != invoice.totalDue) {
            append(s.payTax(numbers.formatMoney(invoice.credit)))
          }
        },
        s.payConfirm,
      )

    is ClientMessage.RefuseProposal ->
      Triple(s.refuseTitle, s.refuseMessage(invoice.counterpartyLabel), s.refuse)

    else ->
      Triple(s.withdrawTitle(invoice.id), s.withdrawMessage, s.withdraw)
  }
}

// ---- Widget --------------------------------------------------------------------------------------

/**
 * The glanceable half: what this farm owes, what it is owed, and whether anything needs answering.
 * The full view is a menu; this is what somebody checks between fields.
 */
@Composable
fun InvoicesSummary(data: InvoicesData?, modifier: Modifier = Modifier) {
  val numbers = LocalStrings.current.numbers
  val s = LocalStrings.current.invoices
  Panel(
    title = LocalStrings.current.finance.invoices,
    icon = Icons.AutoMirrored.Filled.ReceiptLong,
    modifier = modifier,
  ) {
    if (data == null) {
      Centered(s.notInstalled)
      return@Panel
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      FinanceFigure(
        label = if (data.hasOverdue) s.youOweOverdue else s.youOwe,
        value = numbers.formatMoney(data.totalOwed),
        color = when {
          data.hasOverdue -> VdtColors.Red
          data.totalOwed > 0 -> VdtColors.Amber
          else -> VdtColors.DarkGray
        },
      )
      FinanceFigure(
        label = s.youAreOwed,
        value = numbers.formatMoney(data.totalOwing),
        color = if (data.totalOwing > 0) VdtColors.AccentText else VdtColors.DarkGray,
      )
      val pending = data.pendingProposals.size
      if (pending > 0) {
        Text(
          s.proposalsWaiting(pending),
          color = VdtColors.Amber,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

// ---- Formatting ----------------------------------------------------------------------------------

/** The mod's unit tokens as the short labels a line reads with, e.g. the `ha` of `3.5 ha`. */
internal fun unitLabel(strings: Strings, unit: String): String = when (unit) {
  "hectare" -> strings.invoices.unitHectare
  "hour" -> strings.invoices.unitHour
  "liter" -> strings.invoices.unitLitre
  else -> strings.invoices.unitPiece
}

/** How a work type's price should be read: a `liter` row prices a thousand litres, everything else one unit. */
internal fun priceUnitLabel(strings: Strings, unit: String): String =
  if (unit == "liter") "1000 ${strings.invoices.unitLitre}" else unitLabel(strings, unit)

/**
 * A quantity or unit price with at most two decimals and no trailing zeros — `3.5`, `2`, `0.55`. These
 * are the one part of this channel that is deliberately not whole currency units, so they cannot go
 * through [formatMoney].
 */
internal fun Numbers.formatQuantity(value: Double): String = trimmed(value, 2, grouped = true)

/** A percentage with no pointless `.0`. */
internal fun Numbers.trimPercent(value: Double): String = formatQuantity(value)
