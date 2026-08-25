package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.FilterOption
import net.vertexdezign.vdt.app.components.FilterSelect
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.PricesFillType
import net.vertexdezign.vdt.model.PricesStation

/**
 * The Market app's **Prices** tab: the map's price board, one commodity at a time.
 *
 * Master/detail on the commodity rather than on the station, because that is the question a price
 * board is opened with — *where do I sell wheat*, not *what does this shop take*. It is the axis the
 * game's own Prices table uses too, and it is the one that makes the twelve-month curve belong
 * somewhere: a curve is a property of the commodity, not of a shop.
 *
 * Everything is per 1000 litres with the economy multiplier already in it (the unit the game prints),
 * except a pallet price, which is for the whole pallet — the one exception the board carries, and it
 * is labelled where it appears.
 */
@Composable
internal fun PriceBoard(data: PricesData, periods: List<String>) {
  if (data.fillTypes.isEmpty()) {
    Centered("The price board is empty")
    return
  }
  var filters by remember { mutableStateOf(emptyList<FilterOption>()) }
  var selectedType by remember { mutableStateOf<String?>(null) }

  val sales = remember(data) { bestSales(data) }
  val facts = remember(data) { boardFacts(data) }
  val options = remember(data, facts) { priceOptions(data, facts) }
  val shown = remember(data, facts, filters) {
    priceFiltered(data.fillTypes, facts, filters).sortedBy { it.title.lowercase() }
  }
  val currentType = selectedType.takeIf { type -> shown.any { it.type == type } } ?: shown.firstOrNull()?.type
  val selected = shown.firstOrNull { it.type == currentType }

  Column(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      FilterSelect(
        options = options,
        selected = filters,
        onSelectedChange = { filters = it },
        modifier = Modifier.weight(1f),
        placeholder = "Filter by commodity or station…",
      )
      Text(
        "per 1000 l · economy ×${formatMultiplier(data.priceMultiplier)}",
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        textAlign = TextAlign.End,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).widthIn(max = 150.dp),
      )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxSize()) {
      Box(Modifier.width(230.dp).fillMaxHeight().padding(end = 10.dp)) {
        if (shown.isEmpty()) {
          Centered("Nothing matches")
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(shown, key = { it.type }) { fillType ->
              CommodityRow(
                fillType = fillType,
                sale = sales[fillType.type],
                selected = fillType.type == currentType,
                onClick = { selectedType = fillType.type },
              )
            }
          }
        }
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
      Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
        if (selected == null) {
          Centered("Select a commodity")
        } else {
          CommodityDetail(selected, data, sales[selected.type], periods)
        }
      }
    }
  }
}

// ---- The commodity list --------------------------------------------------------------------------

@Composable
private fun CommodityRow(fillType: PricesFillType, sale: BestSale?, selected: Boolean, onClick: () -> Unit) {
  val fg = if (selected) VdtColors.White else VdtColors.TextDark
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(if (selected) VdtColors.Green else VdtColors.TrackGray)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      fillType.title,
      color = fg,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (sale?.greatDemand == true) {
      Text(
        "DEMAND",
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = if (selected) VdtColors.Green else VdtColors.White,
        modifier = Modifier
          .clip(RoundedCornerShape(3.dp))
          .background(if (selected) VdtColors.White else VdtColors.DarkGray)
          .padding(horizontal = 3.dp, vertical = 1.dp),
      )
      Spacer(Modifier.width(4.dp))
    }
    if (sale != null) {
      TrendMark(sale.trend, Modifier.padding(end = 2.dp))
      Text(formatPrice(sale.price), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    } else {
      Text("buy only", color = if (selected) VdtColors.White else VdtColors.DarkGray, fontSize = 10.sp)
    }
  }
}

// ---- The commodity detail ------------------------------------------------------------------------

@Composable
private fun CommodityDetail(fillType: PricesFillType, data: PricesData, sale: BestSale?, periods: List<String>) {
  val sellers = remember(data, fillType) {
    data.stations
      .mapNotNull { station -> station.sell.firstOrNull { it.type == fillType.type }?.let { station to it } }
      .sortedByDescending { it.second.price }
  }
  val buyers = remember(data, fillType) {
    data.stations
      .mapNotNull { station -> station.buy.firstOrNull { it.type == fillType.type }?.let { station to it } }
      .sortedBy { it.second.price }
  }
  val pallets = remember(data, fillType) {
    data.stations
      .mapNotNull { station -> station.pallets.firstOrNull { it.type == fillType.type }?.let { station to it } }
      .sortedBy { it.second.price }
  }

  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(fillType.title, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
      if (fillType.isCrop) {
        Text("CROP", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
      }
    }
    // Wrapping, not a Row: three figures with a station name under one of them will not fit a narrow
    // detail pane side by side, and a Row would simply push the third off the edge.
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(18.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // The reference price, not a price anybody pays: a station's own scale puts it permanently above
      // or below this. It is here because it is the number the fluctuation is a fluctuation *of*.
      Figure("Reference", formatPrice(fillType.basePrice))
      Figure("Best offer", sale?.let { formatPrice(it.price) } ?: "—", sale?.station ?: "no station buys this")
      // Zero when the curve is flat, which is most of what a shop sells you: a "peak" picked out of
      // twelve identical numbers is not one.
      val month = peakMonth(fillType).takeIf { it > 0 }
      Figure(
        "Peak month",
        month?.let { periodLabel(periods, it) } ?: "—",
        month?.let { "market avg ${formatPrice(fillType.months[it - 1])}" } ?: "no seasonal movement",
      )
    }
    if (fillType.months.size >= CropCalendarData.PERIODS) {
      PriceCurve(fillType, data.period, periods)
    }
    if (sellers.isNotEmpty()) {
      SectionLabel("Sells to (${sellers.size})")
      sellers.forEach { (station, row) ->
        StationLine(
          station = station,
          price = formatPrice(row.price),
          trend = row.trend,
          demand = if (row.greatDemand) demandBadge(row.demandMultiplier, row.demandHoursLeft) else "",
        )
      }
    }
    if (buyers.isNotEmpty()) {
      SectionLabel("Buy from (${buyers.size})")
      buyers.forEach { (station, row) -> StationLine(station, formatPrice(row.price), "", "") }
    }
    if (pallets.isNotEmpty()) {
      SectionLabel("Pallets (whole pallet, not per 1000 l)")
      pallets.forEach { (station, row) -> StationLine(station, formatPrice(row.price), "", "") }
    }
    if (sellers.isEmpty() && buyers.isEmpty() && pallets.isEmpty()) {
      Text("Nothing on this map trades it.", color = VdtColors.DarkGray, fontSize = 11.sp)
    }
  }
}

private fun demandBadge(multiplier: Float?, hoursLeft: Int?): String {
  val premium = multiplier?.let { "×$it" } ?: ""
  val left = hoursLeft?.let { if (premium.isEmpty()) "$it h" else " · $it h" } ?: ""
  return if (premium.isEmpty() && left.isEmpty()) "DEMAND" else "DEMAND $premium$left"
}

@Composable
private fun Figure(label: String, value: String, caption: String = "") {
  // Capped so a long station name under "best offer" cannot stretch the strip past the pane.
  Column(Modifier.widthIn(max = 180.dp)) {
    Text(label.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Text(value, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    if (caption.isNotBlank()) {
      Text(caption, color = VdtColors.DarkGray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun StationLine(station: PricesStation, price: String, trend: String, demand: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    if (trend.isNotBlank()) {
      TrendMark(trend, Modifier.padding(end = 4.dp))
    }
    Text(
      station.name,
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    // The game flags a train station because you cannot simply drive there — a price you can't reach
    // with a trailer is a different offer, so the row says so.
    if (station.isTrainStation) {
      Text("TRAIN", color = VdtColors.DarkGray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(6.dp))
    }
    if (demand.isNotBlank()) {
      Text(demand, color = VdtColors.DarkGray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(6.dp))
    }
    Text(price, color = VdtColors.TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
  }
}

// ---- The twelve-month curve ----------------------------------------------------------------------

private val CURVE_HEIGHT = 120.dp

/**
 * The commodity's twelve monthly prices, as the game's own fluctuation graph plots them.
 *
 * **What the bars are is worth being exact about**: `economy.history` is the running average of what
 * *every* station accepting this fill type pays, re-averaged hourly, so a bar is the market and not a
 * shop. That is why the best offer above it can sit above the curve, and why the stock table projects
 * rather than quotes off it.
 *
 * Three marks, none of them a hue: the peak month is drawn at full ink where the rest are muted, the
 * current period is outlined, and both are named in the labels underneath. A zero baseline, because a
 * bar chart cropped to its own range turns a 5% seasonal wobble into a cliff.
 */
@Composable
private fun PriceCurve(fillType: PricesFillType, period: Int, periods: List<String>) {
  val months = fillType.months
  val peak = months.max()
  // 0 for a flat curve, which draws every bar the same height and names none of them the peak.
  val peakMonth = peakMonth(fillType)
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Canvas(Modifier.fillMaxWidth().height(CURVE_HEIGHT)) {
      drawCurve(months, peak, peakMonth, period)
    }
    Row(Modifier.fillMaxWidth()) {
      for (index in 1..CropCalendarData.PERIODS) {
        val isNow = index == period
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            periodLabel(periods, index).uppercase(),
            color = if (isNow || index == peakMonth) VdtColors.TextDark else VdtColors.DarkGray,
            fontSize = 8.sp,
            fontWeight = if (isNow || index == peakMonth) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
          )
          if (isNow) {
            Text("NOW", color = VdtColors.TextDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
          } else if (index == peakMonth) {
            Text("PEAK", color = VdtColors.TextDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

private fun DrawScope.drawCurve(months: List<Float>, peak: Float, peakMonth: Int, period: Int) {
  if (peak <= 0f) return
  val step = size.width / CropCalendarData.PERIODS
  val gap = step * 0.18f
  months.take(CropCalendarData.PERIODS).forEachIndexed { index, value ->
    val height = (value / peak).coerceIn(0f, 1f) * size.height
    val left = step * index + gap / 2
    val width = step - gap
    val top = size.height - height
    drawRect(
      color = VdtColors.ProgressBlue.copy(alpha = if (index + 1 == peakMonth) 1f else 0.4f),
      topLeft = Offset(left, top),
      size = Size(width, height),
    )
    if (index + 1 == period) {
      // The current period is outlined rather than recoloured: it can also be the peak, and two
      // states that share one bar have to be able to show at once.
      drawRect(
        color = VdtColors.TextDark,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 2f),
      )
    }
  }
}
