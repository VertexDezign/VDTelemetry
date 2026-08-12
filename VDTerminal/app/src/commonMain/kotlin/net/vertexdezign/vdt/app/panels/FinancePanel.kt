package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import net.vertexdezign.vdt.model.FinanceData
import net.vertexdezign.vdt.model.FinancePeriod
import net.vertexdezign.vdt.model.FinanceStatRow
import net.vertexdezign.vdt.model.InvoicesData

/** Columns the in-game finances screen shows: the period being played plus four archived ones. */
private const val DEFAULT_COLUMNS = 5

/** Below this the log moves under the table instead of beside it. */
private const val SIDE_BY_SIDE_ABOVE = 760

/** Width of one period column — wide enough for a grouped seven-digit figure. */
private val COLUMN_WIDTH = 92.dp

/** Width of the stat-name column. Fixed rather than weighted so the figures line up under scroll. */
private val NAME_WIDTH = 168.dp

/**
 * The Finance app full page: the local farm's books, the same three things the in-game finances screen
 * shows — the balance and loan, the month-by-month table, and the money notifications as a log.
 *
 * A null [data] means the channel is absent (export off / no data yet) — distinct from a spectator
 * with no farm, which the mod reports as a present-but-empty document ([FinanceData.hasFarm]).
 */
@Composable
fun FinancePanel(
  data: FinanceData?,
  modifier: Modifier = Modifier,
  invoices: InvoicesData? = null,
  onCommand: (ClientMessage) -> Unit = {},
) {
  // Most of the table is zeroes in any given month, so the useful default is to hide them -- the
  // opposite of the in-game screen, which has a full page to spend and always shows every bucket.
  // (The row count is the game's 33 plus whatever a mod adds: one capture already carries 34.)
  var hideEmpty by remember { mutableStateOf(true) }
  // Which half of the app is on screen. A driving-time view mode, so it lives on the panel header
  // rather than in a config dialog -- and it only exists at all when FS25_Invoices is installed, so a
  // player without it sees exactly the page they had before.
  var showInvoices by remember { mutableStateOf(false) }
  val hasInvoices = invoices != null

  Panel(
    title = "Finance",
    icon = Icons.Filled.AccountBalance,
    modifier = modifier,
    headerActions = {
      if (hasInvoices) {
        ViewTab("Books", !showInvoices) { showInvoices = false }
        ViewTab("Invoices", showInvoices) { showInvoices = true }
      }
      if (!showInvoices && data?.stats?.isNotEmpty() == true) {
        Icon(
          if (hideEmpty) Icons.Filled.FilterAlt else Icons.Filled.FilterAltOff,
          contentDescription = if (hideEmpty) "show rows with no movement" else "hide rows with no movement",
          tint = VdtColors.DarkGray,
          modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { hideEmpty = !hideEmpty }
            .padding(2.dp),
        )
      }
    },
  ) {
    when {
      // The invoices view stands on its own: it has its own farm scope and its own empty states, and
      // it is worth reading even while the books are still waiting for their first write.
      showInvoices && hasInvoices -> InvoicesSection(invoices, data?.balance, onCommand = onCommand)

      data == null -> Centered("Waiting for finance data…")

      !data.hasFarm -> Centered("No farm")

      else -> FinanceContent(data, hideEmpty, onCommand)
    }
  }
}

/**
 * One of the panel header's view tabs. `selectable` rather than `clickable`: which of the two is
 * showing is carried by fill and text colour, which a screen reader cannot see, so the selected state
 * has to be in the semantics as well.
 */
@Composable
private fun ViewTab(label: String, active: Boolean, onClick: () -> Unit) {
  Text(
    label.uppercase(),
    color = if (active) VdtColors.White else VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (active) VdtColors.Green else VdtColors.TrackGray)
      .selectable(selected = active, role = Role.Tab, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

@Composable
private fun FinanceContent(data: FinanceData, hideEmpty: Boolean, onCommand: (ClientMessage) -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val sideBySide = maxWidth.value >= SIDE_BY_SIDE_ABOVE
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      FinanceHeadline(data)
      // Which loan section appears is decided purely by whether the mod sent a replacement block —
      // no discriminator, the same "dispatch on presence" rule the ISOBUS sections follow. The two are
      // mutually exclusive by construction: the mod omits the base-game fields whenever it sends this.
      val els = data.enhancedLoans
      when {
        els != null -> EnhancedLoansSection(els, data.balance ?: 0, onCommand = onCommand)
        data.loansAvailable -> LoanControls(data, onCommand)
      }

      if (sideBySide) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.weight(1f).fillMaxHeight()) { MonthTable(data, hideEmpty) }
          Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
          MoneyLog(data.history, Modifier.width(230.dp).fillMaxHeight())
        }
      } else {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.fillMaxWidth().weight(1f)) { MonthTable(data, hideEmpty) }
          MoneyLog(data.history, Modifier.fillMaxWidth().heightIn(max = 160.dp))
        }
      }
    }
  }
}

// ---- Loan ----------------------------------------------------------------------------------------

/**
 * Borrow and repay, as one target the buttons move and Apply commits. The command carries the
 * absolute target rather than a delta (see [ClientMessage.SetLoan]), so this is the natural shape: the
 * stepper edits a number, and one tap sends it.
 *
 * The pending target is keyed on the live loan, so it re-baselines the moment a change lands —
 * including one another player made.
 */
@Composable
private fun LoanControls(data: FinanceData, onCommand: (ClientMessage) -> Unit) {
  val loan = data.loan ?: 0
  val balance = data.balance ?: 0
  val step = data.loanStep.coerceAtLeast(1).toLong()
  // The game's own ceiling: an existing loan above a since-lowered max is held, not force-called.
  val ceiling = maxOf(data.loanMax ?: 0, loan)

  var target by remember(loan, ceiling) { mutableStateOf(loan) }
  var confirming by remember { mutableStateOf(false) }

  val delta = target - loan
  val canManage = data.canManageLoan
  // The mod refuses a repayment the balance can't cover, so the button must not offer one.
  val affordable = delta >= 0 || -delta <= balance

  val stepDown = canManage && target > 0
  val stepUp = canManage && target < ceiling

  fun move(by: Long) {
    target = (target + by).coerceIn(0L, ceiling)
  }

  fun commit() {
    // The mod clamps and re-checks; this cast is safe because the ceiling is Farm.MAX_LOAN at most.
    onCommand(ClientMessage.SetLoan(target.toInt()))
    confirming = false
  }

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("LOAN", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
      Text(
        "${formatMoney(target)} of ${formatMoney(ceiling)} max",
        color = VdtColors.TextDark,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
      if (delta != 0L) {
        Text(
          if (delta > 0) "borrow ${formatMoney(delta)}" else "repay ${formatMoney(-delta)}",
          color = moneyColor(-delta),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      FinanceButton("−${compactStep(step * 10)}", VdtColors.ProgressBlue, { move(-step * 10) }, enabled = stepDown)
      FinanceButton("−${compactStep(step)}", VdtColors.ProgressBlue, { move(-step) }, enabled = stepDown)
      FinanceButton("+${compactStep(step)}", VdtColors.ProgressBlue, { move(step) }, enabled = stepUp)
      FinanceButton("+${compactStep(step * 10)}", VdtColors.ProgressBlue, { move(step * 10) }, enabled = stepUp)

      if (delta != 0L) {
        FinanceButton(
          label = if (delta > 0) "Borrow" else "Repay",
          color = VdtColors.Green,
          // Confirm only when a repayment would very nearly empty the account -- the case where the
          // next fuel stop bounces. The game itself never confirms, so anything broader would nag.
          onClick = { if (delta < 0 && balance + delta < step) confirming = true else commit() },
          enabled = canManage && affordable,
        )
        FinanceButton("Reset", VdtColors.DarkGray, { target = loan })
      }
    }

    if (!canManage) {
      Text(
        "You do not have the right to manage this farm's loan",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )
    } else if (delta < 0 && !affordable) {
      Text(
        "Repaying ${formatMoney(-delta)} needs more than the ${formatMoney(balance)} on hand",
        color = VdtColors.Red,
        fontSize = 10.sp,
      )
    }
  }

  if (confirming) {
    ConfirmDialog(
      title = "Repay ${formatMoney(-delta)}?",
      message = "That leaves ${formatMoney(balance + delta)} on hand — less than one ${formatMoney(step)} step.",
      confirmLabel = "Repay",
      onConfirm = ::commit,
      onDismiss = { confirming = false },
    )
  }
}

/** `5000` -> `5k`, `50000` -> `50k`; anything under a thousand stays as digits. */
private fun compactStep(value: Long): String = if (value >= 1000 && value % 1000 == 0L) "${value / 1000}k" else "$value"

// ---- The month table -----------------------------------------------------------------------------

/** The active sort. Null is the game's own `statNames` order, which groups related buckets together. */
private data class StatSort(val periodIndex: Int?, val descending: Boolean)

/**
 * Clicking a period header sorts the rows by that month's figure; clicking the name header sorts
 * alphabetically. Clicking the active column flips the direction. Money columns start biggest-first
 * (the movers you want to see), names A→Z — the same rule the animal table follows.
 */
private fun nextSort(current: StatSort?, periodIndex: Int?): StatSort =
  // `current?.periodIndex == periodIndex` would also be true for "no sort yet, name column clicked",
  // which must start a sort rather than flip one — hence the explicit null check.
  if (current != null && current.periodIndex == periodIndex) {
    current.copy(descending = !current.descending)
  } else {
    StatSort(periodIndex, descending = periodIndex != null)
  }

private fun comparatorFor(sort: StatSort): Comparator<FinanceStatRow> {
  val ascending: Comparator<FinanceStatRow> =
    if (sort.periodIndex == null) {
      compareBy { it.title.lowercase() }
    } else {
      compareBy { it.values.getOrElse(sort.periodIndex) { 0L } }
    }
  return if (sort.descending) ascending.reversed() else ascending
}

/**
 * The finances table: one row per `FinanceStats` bucket, one column per exported period, newest on the
 * left. Column 0 is the month being played, so it moves while you watch; the rest are settled.
 *
 * The mod exports up to a year of columns but a multiplayer client only ever gets five, so the extra
 * ones are opt-in rather than assumed — see [DEFAULT_COLUMNS].
 */
@Composable
private fun MonthTable(data: FinanceData, hideEmpty: Boolean) {
  var sort by remember { mutableStateOf<StatSort?>(null) }
  var allColumns by remember { mutableStateOf(false) }

  val columns = if (allColumns) data.periods else data.periods.take(DEFAULT_COLUMNS)
  val rows =
    remember(data.stats, hideEmpty, sort, columns.size) {
      val visible =
        if (hideEmpty) {
          // Emptiness is judged over the columns ON SCREEN: a row that only moved in a hidden month
          // would otherwise sit there as a line of zeroes.
          data.stats.filter { row -> columns.indices.any { row.values.getOrElse(it) { 0L } != 0L } }
        } else {
          data.stats
        }
      sort?.let { visible.sortedWith(comparatorFor(it)) } ?: visible
    }

  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (data.periods.size > DEFAULT_COLUMNS) {
      Text(
        if (allColumns) "Show ${DEFAULT_COLUMNS} months" else "Show all ${data.periods.size} months",
        color = VdtColors.AccentText,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .clickable(role = Role.Button) { allColumns = !allColumns }
          .padding(horizontal = 4.dp, vertical = 2.dp),
      )
    }

    Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
      TableHeader(columns, sort) { sort = nextSort(sort, it) }
      Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
        if (rows.isEmpty()) {
          Text(
            "Nothing moved in these months",
            color = VdtColors.DarkGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 8.dp),
          )
        }
        rows.forEachIndexed { index, row ->
          StatRow(row, columns, striped = index % 2 == 0)
        }
      }
      TotalsRow(columns)
    }
  }
}

@Composable
private fun TableHeader(columns: List<FinancePeriod>, sort: StatSort?, onSort: (Int?) -> Unit) {
  Row(Modifier.background(VdtColors.TrackGray), verticalAlignment = Alignment.Bottom) {
    HeaderCell(
      label = "Category",
      width = NAME_WIDTH,
      numeric = false,
      active = sort?.periodIndex == null && sort != null,
      descending = sort?.descending == true,
      onClick = { onSort(null) },
    )
    columns.forEach { period ->
      HeaderCell(
        label = period.label,
        // A year of columns repeats month names, so the settled ones carry their year; the current
        // month is unambiguous and gets the more useful label instead.
        sublabel = if (period.current) "current" else period.year.toString(),
        width = COLUMN_WIDTH,
        numeric = true,
        active = sort?.periodIndex == period.index,
        descending = sort?.periodIndex == period.index && sort.descending,
        onClick = { onSort(period.index) },
      )
    }
  }
}

@Composable
private fun HeaderCell(
  label: String,
  width: androidx.compose.ui.unit.Dp,
  numeric: Boolean,
  active: Boolean,
  descending: Boolean,
  onClick: () -> Unit,
  sublabel: String? = null,
) {
  Column(
    Modifier
      .width(width)
      // The header is a button that reports where the sort sits; screen readers get the state here
      // rather than from a separate arrow node (`clickable` merges descendants into this one).
      .clickable(role = Role.Button, onClick = onClick)
      .semantics {
        stateDescription = when {
          !active -> "not sorted"
          descending -> "sorted descending"
          else -> "sorted ascending"
        }
      }
      .padding(horizontal = 6.dp, vertical = 6.dp),
    horizontalAlignment = if (numeric) Alignment.End else Alignment.Start,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        label.uppercase(),
        color = if (active) VdtColors.TextDark else VdtColors.DarkGray,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // An Icon, not a "▲" — the wasm build ships no font fallback, so a Geometric Shapes glyph
      // renders as tofu. Decorative: the header cell above carries the sort state in its semantics.
      if (active) {
        Icon(
          if (descending) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowDropUp,
          contentDescription = null,
          tint = VdtColors.TextDark,
          modifier = Modifier.size(12.dp),
        )
      }
    }
    if (sublabel != null) {
      Text(sublabel, color = VdtColors.DarkGray, fontSize = 8.sp, maxLines = 1)
    }
  }
}

@Composable
private fun StatRow(row: FinanceStatRow, columns: List<FinancePeriod>, striped: Boolean) {
  Row(
    Modifier.background(if (striped) VdtColors.White.copy(alpha = 0.6f) else Color.Transparent),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      row.title,
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.width(NAME_WIDTH).padding(horizontal = 6.dp, vertical = 5.dp),
    )
    columns.forEach { period ->
      val value = row.values.getOrElse(period.index) { 0L }
      Text(
        if (value == 0L) "—" else formatMoney(value),
        color = moneyColor(value),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(COLUMN_WIDTH).padding(horizontal = 6.dp, vertical = 5.dp),
      )
    }
  }
}

/** The footer the in-game screen prints: each column summed, income minus expenses. */
@Composable
private fun TotalsRow(columns: List<FinancePeriod>) {
  Row(Modifier.background(VdtColors.TrackGray), verticalAlignment = Alignment.CenterVertically) {
    Text(
      "TOTAL",
      color = VdtColors.TextDark,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.width(NAME_WIDTH).padding(horizontal = 6.dp, vertical = 7.dp),
    )
    columns.forEach { period ->
      Text(
        formatMoney(period.total, withSign = true),
        color = moneyColor(period.total),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(COLUMN_WIDTH).padding(horizontal = 6.dp, vertical = 7.dp),
      )
    }
  }
}
