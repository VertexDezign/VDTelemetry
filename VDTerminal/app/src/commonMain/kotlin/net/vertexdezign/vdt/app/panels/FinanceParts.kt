package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FinanceData
import net.vertexdezign.vdt.model.MoneyEvent
import kotlin.math.abs

// ---- Money formatting ----------------------------------------------------------------------------

/**
 * Group a signed amount with thousands separators and a leading sign, e.g. `-1,250`.
 *
 * Deliberately no currency symbol: the game's own unit is a per-install setting the mod does not
 * export, so printing one would be a guess. The panel labels its columns instead.
 */
fun formatMoney(value: Long, withSign: Boolean = false): String {
  val digits = abs(value).toString()
  val grouped = StringBuilder()
  val firstGroup = digits.length % 3
  if (firstGroup > 0) grouped.append(digits, 0, firstGroup)
  var i = firstGroup
  while (i < digits.length) {
    if (grouped.isNotEmpty()) grouped.append(',')
    grouped.append(digits, i, i + 3)
    i += 3
  }
  val sign = when {
    value < 0 -> "-"
    withSign && value > 0 -> "+"
    else -> ""
  }
  return sign + grouped
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
    if (data.loansAvailable) {
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
          if (data.loansAvailable && (data.loan ?: 0) > 0) {
            Text(
              "Loan ${formatMoney(data.loan ?: 0)}",
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
