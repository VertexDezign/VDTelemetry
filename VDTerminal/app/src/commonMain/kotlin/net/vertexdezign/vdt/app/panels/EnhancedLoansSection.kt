package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.EnhancedLoan
import net.vertexdezign.vdt.model.EnhancedLoans
import kotlin.math.min
import kotlin.math.roundToLong

/** The step the amount pickers move in. ELS takes any integer, so this is purely for tapping speed. */
private const val AMOUNT_STEP = 5000L

/**
 * The widest amount either loan command can carry — both are `Int` on the wire. Every ceiling here is
 * capped to it: the model's money is `Long` and a farm rich enough to be offered more than this is
 * rare but representable, and `toInt()` on the way into the command would wrap it to a negative.
 */
private val MAX_COMMAND_AMOUNT = Int.MAX_VALUE.toLong()

/**
 * The FS25_EnhancedLoanSystem section: the bank's terms, this farm's annuity loans, and the two things
 * you can do about them — take a new one, or pay extra off an existing one.
 *
 * Rendered *instead of* the base-game loan controls, because ELS replaces that system rather than
 * extending it. The panel decides purely on this block's presence.
 */
@Composable
fun EnhancedLoansSection(
  els: EnhancedLoans,
  balance: Long,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  var taking by remember { mutableStateOf(false) }
  var selectedId by remember { mutableStateOf<Int?>(null) }

  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    BankTerms(els)

    val running = els.running
    if (running.isEmpty()) {
      Text("No loans running", color = VdtColors.DarkGray, fontSize = 11.sp)
    } else {
      running.forEach { loan ->
        LoanRow(
          loan = loan,
          expanded = loan.id == selectedId,
          onClick = { selectedId = if (selectedId == loan.id) null else loan.id },
        )
        if (loan.id == selectedId) {
          RepayControls(loan, els, balance, onCommand = onCommand)
        }
      }
    }

    // Cleared loans are kept by the mod and are worth a line, but not the space a row costs.
    val cleared = els.loans.count { it.paidOff }
    if (cleared > 0) {
      Text("$cleared loan${if (cleared == 1) "" else "s"} paid off", color = VdtColors.DarkGray, fontSize = 10.sp)
    }

    if (!els.canManage) {
      Text("You do not have the right to manage this farm's loans", color = VdtColors.DarkGray, fontSize = 10.sp)
    } else if (taking) {
      TakeLoanControls(els, onDismiss = { taking = false }, onCommand = onCommand)
    } else {
      FinanceButton(
        label = "Take a loan",
        color = VdtColors.Green,
        onClick = { taking = true },
        enabled = (els.maxAmount ?: 0) > 0,
      )
    }
  }
}

@Composable
private fun BankTerms(els: EnhancedLoans) {
  Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
    Text("BANK", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    els.interest?.let {
      // The rate drifts on its own when the server runs it dynamically, so say which it is: the number
      // a loan is signed at is fixed for that loan's life, but this one is not.
      Text(
        "${formatRate(it)}%${if (els.dynamicInterest) " (variable)" else ""}",
        color = VdtColors.TextDark,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    els.maxDurationYears?.let {
      Text("up to $it yr", color = VdtColors.DarkGray, fontSize = 11.sp)
    }
    els.maxAmount?.let {
      Text("max ${formatMoney(it)}", color = VdtColors.DarkGray, fontSize = 11.sp)
    }
  }
}

@Composable
private fun LoanRow(loan: EnhancedLoan, expanded: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(if (expanded) VdtColors.TrackGray else VdtColors.White.copy(alpha = 0.5f))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        formatMoney(loan.restAmount),
        color = VdtColors.TextDark,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
      Text(
        "of ${formatMoney(loan.amount)} · ${formatRate(loan.interest)}%",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        "${loan.restMonths} mo left",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
      )
    }
    ProgressBar(
      fraction = loan.progress,
      leftLabel = "Repaid",
      // The split is what makes an annuity loan legible: early on almost all of it is interest.
      rightLabel = "${formatMoney(loan.monthlyRate ?: 0)}/mo · ${formatMoney(loan.monthlyInterest ?: 0)} interest",
    )
  }
}

/**
 * Extra payment against one loan. The ceiling is the mod's own clamp order made visible — the farm's
 * money, the per-loan fraction (only while single redemptions are enforced), and what is left owing —
 * so the stepper cannot ask for something the mod would silently reduce.
 */
@Composable
private fun RepayControls(loan: EnhancedLoan, els: EnhancedLoans, balance: Long, onCommand: (ClientMessage) -> Unit) {
  val spent = loan.specialRedemptionDone && !els.multipleRedemptions
  val fractionCap =
    if (els.multipleRedemptions) {
      Long.MAX_VALUE
    } else {
      ((els.redemptionFraction ?: 0f) * loan.amount).roundToLong()
    }
  val ceiling = minOf(balance.coerceAtLeast(0), fractionCap, loan.restAmount, MAX_COMMAND_AMOUNT)

  // The field's digits are the state; the amount is what they parse to. Keeping the number derived
  // rather than mirrored is what lets the buttons and the keyboard edit the same value without one
  // of them having to overwrite the other mid-edit.
  //
  // Deliberately **not** keyed on the ceiling: it moves with the farm's balance, which moves every
  // time a drop of fuel is burnt, and re-keying would blank a half-typed amount several times a
  // second. A ceiling that drops below what is in the field trims it instead.
  var amountText by remember(loan.id) { mutableStateOf(min(AMOUNT_STEP, ceiling).toString()) }
  LaunchedEffect(ceiling) {
    val typed = amountText.toLongOrNull()
    if (typed != null && typed > ceiling) amountText = ceiling.toString()
  }
  val amount = amountText.toLongOrNull()?.coerceIn(0L, ceiling) ?: 0L

  Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    when {
      spent -> Text(
        "This loan has already had its extra payment this year",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )

      ceiling <= 0 -> Text(
        "Nothing can be paid off right now",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )

      else -> {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          FinanceButton(
            label = "−",
            color = VdtColors.ProgressBlue,
            onClick = { amountText = (amount - AMOUNT_STEP).coerceIn(0L, ceiling).toString() },
            enabled = els.canManage && amount > 0,
          )
          AmountField(
            text = amountText,
            ceiling = ceiling,
            onTextChange = { amountText = it },
            enabled = els.canManage,
          )
          FinanceButton(
            label = "+",
            color = VdtColors.ProgressBlue,
            onClick = { amountText = (amount + AMOUNT_STEP).coerceIn(0L, ceiling).toString() },
            enabled = els.canManage && amount < ceiling,
          )
          FinanceButton(
            label = "Max",
            color = VdtColors.ProgressBlue,
            onClick = { amountText = ceiling.toString() },
            enabled = els.canManage && amount < ceiling,
          )
          FinanceButton(
            label = "Pay off",
            color = VdtColors.Green,
            onClick = { onCommand(ClientMessage.RepayLoan(loan.id, amount.toInt())) },
            enabled = els.canManage && amount > 0,
          )
        }
        if (!els.multipleRedemptions && fractionCap < loan.restAmount) {
          Text(
            "One extra payment a year, up to ${formatMoney(fractionCap)} of this loan",
            color = VdtColors.DarkGray,
            fontSize = 9.sp,
          )
        }
      }
    }
  }
}

/** Take a new loan: an amount and a term, both bounded by what the bank currently offers. */
@Composable
private fun TakeLoanControls(els: EnhancedLoans, onDismiss: () -> Unit, onCommand: (ClientMessage) -> Unit) {
  val ceiling = (els.maxAmount ?: 0).coerceIn(0, MAX_COMMAND_AMOUNT)
  val maxYears = (els.maxDurationYears ?: 1).coerceAtLeast(1)

  // Typed digits, as in RepayControls — including why the ceiling does not key them: the bank's offer
  // is derived from the farm's cash, so it drifts under the cursor while an amount is being typed.
  var amountText by remember { mutableStateOf(min(50_000L, ceiling).toString()) }
  var years by remember(maxYears) { mutableStateOf(min(10, maxYears)) }
  LaunchedEffect(ceiling) {
    val typed = amountText.toLongOrNull()
    if (typed != null && typed > ceiling) amountText = ceiling.toString()
  }
  val amount = amountText.toLongOrNull()?.coerceIn(0L, ceiling) ?: 0L

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      Text("AMOUNT", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
      FinanceButton(
        label = "−",
        color = VdtColors.ProgressBlue,
        onClick = { amountText = (amount - AMOUNT_STEP * 2).coerceIn(0L, ceiling).toString() },
        enabled = amount > 0,
      )
      AmountField(text = amountText, ceiling = ceiling, onTextChange = { amountText = it })
      FinanceButton(
        label = "+",
        color = VdtColors.ProgressBlue,
        onClick = { amountText = (amount + AMOUNT_STEP * 2).coerceIn(0L, ceiling).toString() },
        enabled = amount < ceiling,
      )
      FinanceButton(
        label = "Max",
        color = VdtColors.ProgressBlue,
        onClick = { amountText = ceiling.toString() },
        enabled = amount < ceiling,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("TERM", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
      FinanceButton(
        label = "−",
        color = VdtColors.ProgressBlue,
        onClick = { years = (years - 1).coerceIn(1, maxYears) },
        enabled = years > 1,
      )
      Text("$years yr", color = VdtColors.TextDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      FinanceButton(
        label = "+",
        color = VdtColors.ProgressBlue,
        onClick = { years = (years + 1).coerceIn(1, maxYears) },
        enabled = years < maxYears,
      )
    }

    // What it will actually cost, on the same annuity formula the mod uses — so the decision is made
    // before the command goes, not after the first instalment lands.
    val rate = els.interest ?: 0f
    val monthly = annuity(amount, rate, years)
    Text(
      "≈ ${formatMoney(monthly)} a month for ${years * 12} months",
      color = VdtColors.DarkGray,
      fontSize = 10.sp,
    )
    // The figure the monthly instalment hides: what the loan costs in total. Given its own weight
    // because it is the one number that makes two terms comparable — a longer term always shows the
    // easier instalment and the larger bill.
    val total = totalRepayment(amount, rate, years)
    Text(
      "Total ≈ ${formatMoney(total)} · ${formatMoney(total - amount)} interest",
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FinanceButton(
        label = "Borrow",
        color = VdtColors.Green,
        onClick = {
          onCommand(ClientMessage.TakeLoan(amount.toInt(), years))
          onDismiss()
        },
        enabled = amount > 0,
      )
      FinanceButton(label = "Cancel", color = VdtColors.DarkGray, onClick = onDismiss)
    }
  }
}

/**
 * The monthly instalment for an annuity loan, matching `ELS_loan:calculateAnnuity` — the annuity
 * factor applied to the principal, divided into twelve. A zero rate would divide by zero in that
 * formula, so it degrades to plain equal instalments.
 */
internal fun annuity(amount: Long, ratePercent: Float, years: Int): Long =
  annuityExact(amount, ratePercent, years).roundToLong()

/** The same instalment before it is rounded for display — what an amortization has to be run with. */
private fun annuityExact(amount: Long, ratePercent: Float, years: Int): Double {
  if (amount <= 0 || years <= 0) return 0.0
  val r = ratePercent / 100.0
  if (r <= 0.0) return amount.toDouble() / (years * 12)
  var compounded = 1.0
  repeat(years) { compounded *= (1 + r) }
  val factor = (compounded * r) / (compounded - 1)
  return amount * factor / 12
}

/**
 * Everything a loan will have cost by the time it clears — principal plus all the interest —
 * mirroring `ELS_loan:calculateTotalAmount`, which does not close a formula but *runs the loan*:
 * each month charges interest on what is still owed at a twelfth of the annual rate, the annuity
 * pays that plus whatever is left over off the principal, and the last instalment is only as large
 * as the remainder. That is why this comes out **below** `monthlyRate × months`: the instalment is
 * derived from annual compounding but charged against monthly interest, so the debt clears a month
 * or two early. Pricing it any other way would over-state the bill by an instalment.
 *
 * The loop is bounded by the agreed term, which the amortization always finishes inside; a payment
 * too small to cover its own interest (only reachable at a degenerate rate) leaves the remaining
 * balance in the total rather than spinning.
 */
internal fun totalRepayment(amount: Long, ratePercent: Float, years: Int): Long {
  if (amount <= 0 || years <= 0) return 0
  val payment = annuityExact(amount, ratePercent, years)
  val monthlyRate = ratePercent / 100.0 / 12
  var rest = amount.toDouble()
  var total = 0.0
  repeat(years * 12) {
    if (rest <= 0) return total.roundToLong()
    val interest = rest * monthlyRate
    val principal = payment - interest
    if (principal >= rest) {
      total += rest + interest
      rest = 0.0
    } else {
      total += payment
      rest -= principal
    }
  }
  return (total + rest.coerceAtLeast(0.0)).roundToLong()
}

/** `3.5` → `"3.5"`, `3.0` → `"3"` — the mod stores a float but usually means one decimal. */
private fun formatRate(value: Float): String {
  val tenths = (value * 10).roundToLong()
  return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}
