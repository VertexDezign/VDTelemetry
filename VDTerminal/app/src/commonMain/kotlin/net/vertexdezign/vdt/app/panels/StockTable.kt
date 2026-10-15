package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.FilterOption
import net.vertexdezign.vdt.app.components.FilterSelect
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.StorageData
import kotlin.math.roundToInt

/**
 * The Market app's **Stock** tab: everything the farm holds, priced against the board.
 *
 * One sortable table over [buildStock]'s rows — a commodity in one form, wherever on the farm it is —
 * with a total line under it. Tapping a row opens where that stock actually sits, which is the
 * question a stock list raises and usually leaves the reader to answer by driving around.
 *
 * The price board is optional here in a way the holdings are not: without it the table still lists
 * what the farm owns and simply has no price to put beside it, which is worth more than a waiting
 * spinner. The other way round is not true — with no holdings channel there is nothing to list.
 *
 * **Stock nothing buys is left unpriced on purpose.** Diesel, fertilizer, lime and the rest are sold
 * *to* the farm and bought back by nobody, so their rows carry a dash and stay out of the total. The
 * buy price is on the board and would fill the column, but it answers a different question — what a
 * shop charges to replace it, not what anyone would pay for it — and a total mixing the two would be
 * neither the yard's worth nor its replacement cost.
 */
@Composable
internal fun StockTable(
  prices: PricesData?,
  storage: StorageData?,
  production: ProductionData?,
  husbandry: HusbandriesData?,
  periods: List<String>,
) {
  val rows = remember(storage, production, husbandry, prices) {
    buildStock(storage, production, husbandry, prices)
  }
  val entries = remember(rows, prices) { valuateAll(rows, prices) }
  val crops = remember(prices) { prices?.fillTypes.orEmpty().filter { it.isCrop }.map { it.type }.toSet() }
  val s = LocalStrings.current.market
  when {
    storage == null && production == null && husbandry == null -> Centered(s.waitingStock)
    entries.isEmpty() -> Centered(s.holdsNothing)
    else -> StockBody(entries, crops, prices, periods)
  }
}

@Composable
private fun StockBody(entries: List<StockEntry>, crops: Set<String>, prices: PricesData?, periods: List<String>) {
  val strings = LocalStrings.current
  var filters by remember { mutableStateOf(emptyList<FilterOption>()) }
  // Name, ascending: the table is read to look something up as often as to see what the yard is worth,
  // and an alphabetical list is the one a reader can find a row in without reading it.
  var sort by remember { mutableStateOf(StockSort.NAME) }
  var ascending by remember { mutableStateOf(true) }
  // Which row is opened, by key rather than by index: the rows re-sort on every write, and an index
  // would open a different commodity each time the silo levels moved.
  var openKey by remember { mutableStateOf<String?>(null) }

  val options = remember(entries, crops, strings) { stockOptions(entries, crops, strings) }
  val shown = remember(entries, crops, filters, sort, ascending, strings) {
    stockSorted(stockFiltered(entries, filters, crops, strings), sort, ascending)
  }
  val totals = remember(shown) { stockTotals(shown) }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val columns = columnsFor(maxWidth)
    Column(Modifier.fillMaxSize()) {
      StockControls(options, filters, prices, { filters = it })
      Spacer(Modifier.height(8.dp))
      StockHeader(columns, sort, ascending) { column ->
        if (sort == column.sort) {
          ascending = !ascending
        } else {
          sort = column.sort
          ascending = column.startAscending
        }
      }
      if (shown.isEmpty()) {
        Centered(strings.common.nothingMatches)
      } else {
        // Lazy, like the fleet's: a played-in farm holds dozens of commodities across five sources,
        // and every row can open a sub-list of its own.
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          items(shown, key = { it.row.key }) { entry ->
            StockRowView(
              entry = entry,
              columns = columns,
              periods = periods,
              open = entry.row.key == openKey,
              onClick = { openKey = if (openKey == entry.row.key) null else entry.row.key },
            )
          }
        }
        StockTotalRow(totals, columns)
      }
    }
  }
}

// ---- Columns -------------------------------------------------------------------------------------

/**
 * The table's columns, in order. [weight] is the share of the width each takes, so the table fits
 * whatever it is given rather than scrolling sideways on a tablet.
 *
 * [startAscending] is which way the column first sorts when it is picked: a name reads from A, and
 * every figure on this table is one where the reader means "the biggest" when they tap it once.
 */
private enum class StockColumn(
  val weight: Float,
  val sort: StockSort,
  val numeric: Boolean,
  val startAscending: Boolean,
) {
  NAME(2.6f, StockSort.NAME, false, true),
  FORM(1.0f, StockSort.FORM, false, true),
  AMOUNT(1.2f, StockSort.AMOUNT, true, false),
  PRICE(1.0f, StockSort.PRICE, true, false),
  VALUE(1.2f, StockSort.VALUE, true, false),
  STATION(1.7f, StockSort.STATION, false, true),
  BEST_PRICE(1.1f, StockSort.BEST_PRICE, true, false),
  BEST_VALUE(1.2f, StockSort.BEST_VALUE, true, false),
  MONTH(0.9f, StockSort.MONTH, false, true),
  ;

  fun label(strings: Strings): String = when (this) {
    NAME -> strings.market.columnName
    FORM -> strings.market.columnType
    AMOUNT -> strings.market.columnAmount
    PRICE -> strings.market.columnPrice
    VALUE -> strings.market.columnValue
    STATION -> strings.market.columnSellsTo
    BEST_PRICE -> strings.market.columnBestPrice
    BEST_VALUE -> strings.market.columnBestValue
    MONTH -> strings.market.columnMonth
  }
}

/**
 * Which columns survive at this width. A dropped column is never a lost figure — the opened row
 * carries all nine in full — so the table gives up its widest columns first and keeps the three the
 * whole screen is for: what it is, how much, what it is worth.
 */
private fun columnsFor(width: Dp): List<StockColumn> = when {
  width >= 900.dp -> StockColumn.entries

  width >= 740.dp -> StockColumn.entries - StockColumn.STATION

  width >= 560.dp -> listOf(
    StockColumn.NAME,
    StockColumn.FORM,
    StockColumn.AMOUNT,
    StockColumn.PRICE,
    StockColumn.VALUE,
    StockColumn.BEST_VALUE,
  )

  else -> listOf(StockColumn.NAME, StockColumn.AMOUNT, StockColumn.VALUE)
}

// ---- Controls ------------------------------------------------------------------------------------

@Composable
private fun StockControls(
  options: List<FilterOption>,
  filters: List<FilterOption>,
  prices: PricesData?,
  onFilters: (List<FilterOption>) -> Unit,
) {
  val strings = LocalStrings.current
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    FilterSelect(
      options = options,
      selected = filters,
      onSelectedChange = onFilters,
      modifier = Modifier.weight(1f),
      placeholder = strings.market.stockFilterHint,
    )
    Text(
      priceNote(strings, prices),
      color = VdtColors.DarkGray,
      fontSize = 9.sp,
      textAlign = TextAlign.End,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      // Capped so the filter keeps its room on a narrow screen: the note is context, the box is the
      // control.
      modifier = Modifier.padding(top = 6.dp).widthIn(max = 150.dp),
    )
  }
}

/**
 * The unit and the economy the money columns are in. Both belong on screen: the board's prices are per
 * 1000 l (the unit the game's own menu prints), and they already have the difficulty multiplier folded
 * in — so a save on easy and a save on hard genuinely have different boards, and the reader is told
 * which one they are looking at rather than left to wonder.
 */
private fun priceNote(strings: Strings, prices: PricesData?): String = when {
  prices == null -> strings.market.noBoard
  else -> strings.market.pricesPer1000(strings.numbers.formatMultiplier(prices.priceMultiplier))
}

// ---- The table -----------------------------------------------------------------------------------

@Composable
private fun StockHeader(
  columns: List<StockColumn>,
  sort: StockSort,
  ascending: Boolean,
  onSort: (StockColumn) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 8.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    columns.forEach { column ->
      val active = column.sort == sort
      Row(
        Modifier
          .weight(column.weight)
          .clickable(role = Role.Button) { onSort(column) }
          .padding(horizontal = 2.dp),
        horizontalArrangement = if (column.numeric) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // The sorted column is named by the arrow beside it, not by a colour: the header row is one
        // flat grey and the mark is the only thing that moves.
        if (active && column.numeric) SortMark(ascending)
        Text(
          column.label(LocalStrings.current).uppercase(),
          color = VdtColors.DarkGray,
          fontSize = 9.sp,
          fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (active && !column.numeric) SortMark(ascending)
      }
    }
  }
}

@Composable
private fun SortMark(ascending: Boolean) {
  val common = LocalStrings.current.common
  // An Icon, never an arrow character — the wasm build has no font fallback for those glyphs.
  Icon(
    if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
    contentDescription = if (ascending) common.ascending else common.descending,
    tint = VdtColors.DarkGray,
    modifier = Modifier.size(11.dp),
  )
}

@Composable
private fun StockRowView(
  entry: StockEntry,
  columns: List<StockColumn>,
  periods: List<String>,
  open: Boolean,
  onClick: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(if (open) VdtColors.Light else VdtColors.Panel)
      .clickable(role = Role.Button, onClick = onClick),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      columns.forEach { column -> StockCell(column, entry, periods) }
    }
    if (open) StockDetail(entry, periods)
  }
}

@Composable
private fun RowScope.StockCell(column: StockColumn, entry: StockEntry, periods: List<String>) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val row = entry.row
  val valuation = entry.value
  val modifier = Modifier.weight(column.weight).padding(horizontal = 2.dp)
  when (column) {
    StockColumn.NAME -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
      Text(
        row.title,
        color = VdtColors.TextDark,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      // A fermenting bale still reports the fill type it went in as, so the row is priced as grass
      // while it is on its way to being silage. The badge is a word, not a tint.
      if (row.fermenting > 0) {
        Spacer(Modifier.width(4.dp))
        WordBadge(strings.market.fermentingBadge(row.fermenting))
      }
    }

    StockColumn.FORM -> CellText(formSummary(strings, row), modifier = modifier)

    StockColumn.AMOUNT -> CellText(
      if (row.liters > 0) strings.common.litre(numbers.integer(row.liters)) else "—",
      end = true,
      modifier = modifier,
      muted = row.liters <= 0,
    )

    StockColumn.PRICE -> CellText(
      valuation.sale?.let { numbers.formatPrice(it.price) } ?: "—",
      end = true,
      modifier = modifier,
      muted = valuation.sale == null,
    )

    StockColumn.VALUE -> CellText(
      valuation.value?.let { numbers.formatCurrency(it) } ?: "—",
      end = true,
      modifier = modifier,
      bold = true,
      muted = valuation.value == null,
    )

    StockColumn.STATION -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
      val sale = valuation.sale
      if (sale == null) {
        CellText(strings.market.noBuyer, end = false, muted = true)
      } else {
        TrendMark(sale.trend)
        Spacer(Modifier.width(3.dp))
        Text(
          sale.station,
          color = VdtColors.TextDark,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (sale.greatDemand) {
          Spacer(Modifier.width(4.dp))
          WordBadge(strings.market.demandBadge)
        }
      }
    }

    StockColumn.BEST_PRICE -> CellText(
      valuation.bestPrice?.let { numbers.formatPrice(it) } ?: "—",
      end = true,
      modifier = modifier,
      muted = valuation.bestPrice == null,
    )

    StockColumn.BEST_VALUE -> CellText(
      valuation.bestValue?.let { numbers.formatCurrency(it) } ?: "—",
      end = true,
      modifier = modifier,
      muted = valuation.bestValue == null,
    )

    StockColumn.MONTH -> CellText(
      if (valuation.bestMonth > 0) periodLabel(periods, valuation.bestMonth) else "—",
      end = true,
      modifier = modifier,
      muted = valuation.bestMonth <= 0,
    )
  }
}

@Composable
private fun CellText(
  text: String,
  modifier: Modifier = Modifier,
  /** Right-align the cell — every figure column does, so the digits line up down the table. */
  end: Boolean = false,
  bold: Boolean = false,
  muted: Boolean = false,
) {
  Text(
    text,
    color = if (muted) VdtColors.DarkGray else VdtColors.TextDark,
    fontSize = if (bold) 12.sp else 11.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = if (end) TextAlign.End else TextAlign.Start,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

@Composable
private fun WordBadge(label: String) {
  Text(
    label,
    fontSize = 8.sp,
    fontWeight = FontWeight.Bold,
    color = VdtColors.OnFill,
    modifier = Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.DarkGray)
      .padding(horizontal = 4.dp, vertical = 2.dp),
  )
}

/**
 * The opened row: where the stock is, and the two claims the money columns make spelled out in full.
 *
 * This is also what makes the narrow layouts honest — every column the width dropped is written out
 * here as a sentence, so a phone shows the same table with one more tap.
 */
@Composable
private fun StockDetail(entry: StockEntry, periods: List<String>) {
  val strings = LocalStrings.current
  val valuation = entry.value
  Column(
    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    // The row's own line, restated: on the narrow layouts the form and the count are columns the
    // width dropped, and this is where the claim that nothing is lost by dropping them is kept.
    DetailLine(formSentence(strings, entry.row))
    entry.row.sources.forEach { source -> SourceLine(source) }
    val sale = valuation.sale
    if (sale == null) {
      DetailLine(strings.market.noStationBuys(entry.row.title))
    } else {
      DetailLine(saleSentence(strings, sale, valuation))
      if (valuation.bestMonth > 0 && valuation.bestPrice != null && valuation.bestValue != null) {
        DetailLine(projectionSentence(strings, sale, valuation, periods))
      } else {
        DetailLine(strings.market.flatYear)
      }
    }
  }
}

/**
 * What the stock is in — the "Type" column. A row is one commodity however many containers it is
 * spread over, so this is a list and not a word: seed on a pallet and seed in a big bag reads
 * "Pallets + Big bags (9)", and the sources underneath say which is where.
 */
internal fun formSummary(strings: Strings, row: StockRow): String {
  val forms = row.forms.joinToString(" + ") { it.label(strings) }
  return if (row.count > 0) "$forms (${row.count})" else forms
}

private fun formSentence(strings: Strings, row: StockRow): String = buildString {
  append(formSummary(strings, row))
  if (row.liters > 0) append(" · ${strings.common.litre(strings.numbers.integer(row.liters))}")
  if (row.sources.size > 1) append(" · ${strings.market.places(row.sources.size)}")
}

// The sentences below spell their arithmetic out in words. An arrow would be shorter and would render
// as a box: the wasm build has no font fallback, and a mark inside a sentence cannot be an Icon
// without an InlineTextContent (see VDTerminal/README.md -> "Design rules").
private fun saleSentence(strings: Strings, sale: BestSale, valuation: StockValuation): String = buildString {
  val numbers = strings.numbers
  val s = strings.market
  append(s.bestOffer(numbers.formatPrice(sale.price), sale.station, trendLabel(strings, sale.trend)))
  if (sale.greatDemand) {
    // The premium is already in the price the game reports; this says why it is high, it does not
    // multiply it a second time.
    append(s.greatDemand(sale.demandMultiplier?.let { numbers.trimmed(it, 2) } ?: "", sale.demandHoursLeft))
  }
  valuation.value?.let { append(s.worth(numbers.formatCurrency(it))) }
}

private fun projectionSentence(
  strings: Strings,
  sale: BestSale,
  valuation: StockValuation,
  periods: List<String>,
): String {
  val best = valuation.bestPrice ?: return ""
  val bestValue = valuation.bestValue ?: return ""
  val now = valuation.value ?: return ""
  val gain = if (now > 0f) ((bestValue - now) / now * 100).roundToInt() else 0
  val month = periodLabel(periods, valuation.bestMonth)
  val numbers = strings.numbers
  return strings.market.projection(
    month,
    if (gain >= 0) "+$gain" else "$gain",
    sale.station,
    numbers.formatPrice(best),
    numbers.formatCurrency(bestValue),
  )
}

@Composable
private fun SourceLine(source: StockSource) {
  val strings = LocalStrings.current
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      if (source.name.isBlank()) source.place.label(strings) else "${source.place.label(strings)} · ${source.name}",
      color = VdtColors.TextDark,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    source.note?.let { note ->
      Text(note.label(strings), color = VdtColors.DarkGray, fontSize = 9.sp)
      Spacer(Modifier.width(6.dp))
    }
    Text(
      sourceAmount(strings, source),
      color = VdtColors.DarkGray,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

/**
 * How much of the row is in this one store — the whole point of opening a row, so it says it in full:
 * what form it is in (the only place that says which of the nine pallets are big bags), how many
 * objects, how many liters, and out of what where the store states a capacity.
 */
internal fun sourceAmount(strings: Strings, source: StockSource): String = buildString {
  val numbers = strings.numbers
  if (source.form != StockForm.BULK) append(source.form.label(strings))
  if (source.count > 0) append(" ×${source.count}")
  if (isNotEmpty() && source.liters > 0) append(" · ")
  if (source.liters > 0) {
    val liters = numbers.integer(source.liters)
    append(
      if (source.capacity >
        0
      ) {
        strings.common.litres(liters, numbers.integer(source.capacity))
      } else {
        strings.common.litre(liters)
      },
    )
  }
}.trim()

@Composable
private fun DetailLine(text: String) {
  Text(text, color = VdtColors.DarkGray, fontSize = 10.sp)
}

/**
 * The bottom line, over exactly the rows on screen — filtering to the crops and reading the total is
 * how "what is my grain worth" gets answered, so a total that ignored the filter would answer a
 * question nobody asked.
 */
@Composable
private fun StockTotalRow(totals: StockTotals, columns: List<StockColumn>) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  Row(
    Modifier
      .fillMaxWidth()
      .padding(top = 4.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    columns.forEach { column ->
      val modifier = Modifier.weight(column.weight).padding(horizontal = 2.dp)
      when (column) {
        StockColumn.NAME -> Text(
          strings.market.total(totals.rows, totals.unpriced),
          color = VdtColors.TextDark,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = modifier,
        )

        StockColumn.VALUE -> CellText(
          numbers.formatCurrency(totals.value),
          end = true,
          modifier = modifier,
          bold = true,
        )

        StockColumn.BEST_VALUE -> CellText(
          numbers.formatCurrency(totals.bestValue),
          end = true,
          modifier = modifier,
          bold = true,
        )

        else -> Box(modifier)
      }
    }
  }
}
