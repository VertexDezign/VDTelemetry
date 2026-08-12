package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FinanceData
import net.vertexdezign.vdt.model.MoneyEvent

// ---- Money formatting ----------------------------------------------------------------------------

/**
 * Group a signed amount with thousands separators and a leading sign, e.g. `-1,250`.
 *
 * Deliberately no currency symbol: the game's own unit is a per-install setting the mod does not
 * export, so printing one would be a guess. The panel labels its columns instead.
 */
fun formatMoney(value: Long, withSign: Boolean = false): String {
  // The magnitude comes off the string, not off abs(): abs(Long.MIN_VALUE) is still negative, and
  // grouping a "-" as a digit would print a doubled sign and a ragged first group.
  val sign = when {
    value < 0 -> "-"
    withSign && value > 0 -> "+"
    else -> ""
  }
  return sign + groupDigits(value.toString().removePrefix("-"))
}

/**
 * Thousands-group an unsigned digit string, from the right. Split out from [formatMoney] because the
 * amount field groups what the user is typing, which is a string and not yet a number.
 */
internal fun groupDigits(digits: String): String {
  val grouped = StringBuilder()
  val firstGroup = digits.length % 3
  if (firstGroup > 0) grouped.append(digits, 0, firstGroup)
  var i = firstGroup
  while (i < digits.length) {
    if (grouped.isNotEmpty()) grouped.append(',')
    grouped.append(digits, i, i + 3)
    i += 3
  }
  return grouped.toString()
}

/**
 * Ink for a signed amount. A plain zero stays muted rather than green — most of the table is zeroes
 * in any given month, and colouring them all would drown the rows that actually moved.
 */
fun moneyColor(value: Long): Color = when {
  value > 0 -> VdtColors.AccentText
  value < 0 -> VdtColors.Red
  else -> VdtColors.DarkGray
}

// ---- Headline ------------------------------------------------------------------------------------

/** One labelled figure in the headline strip. */
@Composable
fun FinanceFigure(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

/**
 * Balance, loan and what the loan costs per day. The balance follows the in-game screen's own rule
 * and turns red once it is negative.
 */
@Composable
fun FinanceHeadline(data: FinanceData, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(20.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val balance = data.balance ?: 0
    FinanceFigure(
      label = "Balance",
      value = formatMoney(balance),
      // The game's threshold, not zero: InGameMenuStatisticsFrame goes red at <= -1.
      color = if (balance <= -1) VdtColors.Red else VdtColors.TextDark,
    )
    val els = data.enhancedLoans
    when {
      // A replacement loan system owns the headline figures too: its debt is the farm's debt, and its
      // instalment is what actually leaves the account.
      els != null -> {
        val owed = els.totalOutstanding
        FinanceFigure(
          label = "Loans",
          value = formatMoney(owed),
          color = if (owed > 0) VdtColors.Amber else VdtColors.DarkGray,
        )
        if (owed > 0) {
          FinanceFigure(label = "Per month", value = formatMoney(-els.totalMonthlyRate), color = VdtColors.Red)
        }
      }

      data.loansAvailable -> {
        FinanceFigure(
          label = "Loan",
          value = formatMoney(data.loan ?: 0),
          color = if ((data.loan ?: 0) > 0) VdtColors.Amber else VdtColors.DarkGray,
        )
        val interest = data.loanInterestPerDay ?: 0
        if (interest > 0) {
          FinanceFigure(label = "Interest / day", value = formatMoney(-interest), color = VdtColors.Red)
        }
      }
    }
  }
}

// ---- Money log -----------------------------------------------------------------------------------

/**
 * The money notifications this mod session saw, newest first — the running half of the finances view,
 * where the table is the settled half. Session-scoped mod-side, so it starts empty every game launch.
 */
@Composable
fun MoneyLog(events: List<MoneyEvent>, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("ACTIVITY", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    if (events.isEmpty()) {
      Text(
        "No transactions yet this session",
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
      )
      return@Column
    }
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      events.forEach { MoneyLogRow(it) }
    }
  }
}

@Composable
private fun MoneyLogRow(event: MoneyEvent) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        // An unlabelled change is a real case — the game allows an empty notification label.
        event.title ?: "Transaction",
        color = VdtColors.TextDark,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val stamp = listOfNotNull(event.date, event.time).joinToString(" ")
      if (stamp.isNotEmpty()) {
        Text(stamp, color = VdtColors.DarkGray, fontSize = 9.sp, maxLines = 1)
      }
    }
    Text(
      formatMoney(event.amount, withSign = true),
      color = moneyColor(event.amount),
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.End,
      maxLines = 1,
    )
  }
}

// ---- Dashboard tile ------------------------------------------------------------------------------

/**
 * The compact form for a dashboard page: the balance, this month's running total, and the loan when
 * there is one. Deliberately not the table — a tile has room for the headline, and the table is what
 * the full page is for.
 */
@Composable
fun FinanceSummary(data: FinanceData?, modifier: Modifier = Modifier) {
  Panel(title = "Finance", icon = Icons.Filled.AccountBalance, modifier = modifier) {
    when {
      data == null -> Centered("Waiting for finance data…")

      !data.hasFarm -> Centered("No farm")

      else -> {
        val balance = data.balance ?: 0
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          FinanceFigure(
            label = "Balance",
            value = formatMoney(balance),
            color = if (balance <= -1) VdtColors.Red else VdtColors.TextDark,
          )
          data.periods.firstOrNull()?.let { current ->
            FinanceFigure(
              label = current.label,
              value = formatMoney(current.total, withSign = true),
              color = moneyColor(current.total),
            )
          }
          // Whichever loan system is in play, if either owes anything.
          val owed = data.enhancedLoans?.totalOutstanding ?: (data.loan ?: 0).takeIf { data.loansAvailable } ?: 0
          if (owed > 0) {
            Text(
              "Loan ${formatMoney(owed)}",
              color = VdtColors.Amber,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }
    }
  }
}

// ---- Buttons ---------------------------------------------------------------------------------

/** A flat action button, matching the contracts panel's. Shared by the panel and the loan sections. */
@Composable
internal fun FinanceButton(
  label: String,
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val bg = if (enabled) color else VdtColors.TrackGray
  val fg = if (enabled) VdtColors.White else VdtColors.TextDisabled
  Box(
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp),
  ) {
    Text(label.uppercase(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
  }
}

// ---- Amount entry --------------------------------------------------------------------------------

/** Digits a money field accepts, wide enough for any balance the game holds and short of Long overflow. */
private const val MAX_AMOUNT_DIGITS = 12

/**
 * A typed money amount, grouped as it is entered. The loan screens pair it with ± buttons, but the
 * field is what makes six figures reachable — the game's own loan dialogs take a typed number, and
 * stepping to one 5,000 at a time is not a substitute.
 *
 * The **caller owns the raw digits**: what they mean is `text.toLongOrNull() ?: 0`, and the buttons
 * beside the field write digits back through [onTextChange], so there is one source of truth and no
 * state to re-sync. Entry above [ceiling] is clamped as it is typed rather than on commit — the field
 * must never show a number the button under it would not send.
 */
@Composable
internal fun AmountField(
  text: String,
  ceiling: Long,
  onTextChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  BasicTextField(
    value = text,
    onValueChange = { new ->
      // Digits only, capped in width before parsing: a paste of 30 digits overflows Long, and
      // toLongOrNull() would then read as "empty" rather than as "far too much".
      val digits = new.filter { it.isDigit() }.take(MAX_AMOUNT_DIGITS).trimStart('0')
      val value = digits.toLongOrNull()
      onTextChange(if (value != null && value > ceiling) ceiling.toString() else digits)
    },
    enabled = enabled,
    singleLine = true,
    textStyle = TextStyle(
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      color = if (enabled) VdtColors.TextDark else VdtColors.TextDisabled,
      textAlign = TextAlign.End,
    ),
    cursorBrush = SolidColor(VdtColors.TextDark),
    visualTransformation = ThousandsGrouping,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    modifier = modifier
      .width(110.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(if (enabled) VdtColors.White else VdtColors.TrackGray)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 7.dp),
  )
}

/**
 * Groups the digits of an amount field for display only — `250000` reads as `250,000` while what the
 * field stores stays parseable. The offset mapping is the part that matters: Compose places the cursor
 * and the selection through it, and an index that disagrees with the rendered string by even one
 * throws rather than merely looking wrong.
 */
internal object ThousandsGrouping : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val digits = text.text
    val grouped = groupDigits(digits)
    val mapping = object : OffsetMapping {
      /** Commas sit *between* groups, so the count before a digit is the total minus those after it. */
      override fun originalToTransformed(offset: Int): Int {
        val n = digits.length
        val at = offset.coerceIn(0, n)
        val commasBefore = (n - 1) / 3 - (n - at - 1).coerceAtLeast(0) / 3
        return at + commasBefore
      }

      /** Counting the commas passed is exact by construction, which a second formula would not be. */
      override fun transformedToOriginal(offset: Int): Int {
        val at = offset.coerceIn(0, grouped.length)
        return at - grouped.take(at).count { it == ',' }
      }
    }
    return TransformedText(AnnotatedString(grouped), mapping)
  }
}
