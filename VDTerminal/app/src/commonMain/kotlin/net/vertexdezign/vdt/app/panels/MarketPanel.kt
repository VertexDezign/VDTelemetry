package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ViewTab
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.StorageData
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Market app full page — the price board and the farm's own stock, on two tabs of one panel
 * because they are two halves of one question: what is it worth, and how much of it have I got.
 *
 * **Stock** ([StockTable]) joins the four channels that report holdings against the board: a sortable
 * table of every commodity the farm owns, what it would fetch now and where, and what the year's peak
 * would make of it. **Prices** ([PriceBoard]) is the board itself — every commodity, the stations that
 * buy and sell it, and the twelve-month curve behind it, which is the game's own Prices table without
 * the walk through a menu.
 *
 * Stock opens first: the board is the reference the stock table reads from, and a reader who wanted
 * the reference alone would have gone to the game's menu (issue #118).
 *
 * Every channel here is optional and each has its own cadence, so each tab renders its own waiting
 * state rather than the panel gating on all of them. [calendar] is consulted for one thing only — the
 * game's own month labels, which are not derivable from a period number (see [CalendarPeriod.label]).
 */
@Composable
fun MarketPanel(
  prices: PricesData?,
  storage: StorageData?,
  production: ProductionData?,
  husbandry: HusbandriesData?,
  calendar: CropCalendarData?,
  modifier: Modifier = Modifier,
) {
  var tab by remember { mutableStateOf(MarketTab.STOCK) }
  Panel(
    title = "Market",
    icon = Icons.Filled.Storefront,
    modifier = modifier,
    headerActions = {
      MarketTab.entries.forEach { entry ->
        ViewTab(entry.label, tab == entry, { tab = entry })
      }
    },
  ) {
    val periods = remember(calendar) { periodLabels(calendar) }
    when (tab) {
      MarketTab.STOCK -> StockTable(prices, storage, production, husbandry, periods)

      MarketTab.PRICES ->
        if (prices == null) Centered("Waiting for the price board…") else PriceBoard(prices, periods)
    }
  }
}

enum class MarketTab(val label: String) {
  STOCK("Stock"),
  PRICES("Prices"),
}

// ---- Shared pieces -------------------------------------------------------------------------------

/**
 * The game's own localized short labels for the twelve periods, indexed 0 = period 1.
 *
 * From the crop-calendar channel when it is there, because the month a period falls in is a property
 * of the map and not of the number: a southern-hemisphere map starts its first period in September.
 * Without that channel the label falls back to the period number, which says less but never says
 * something false.
 */
internal fun periodLabels(calendar: CropCalendarData?): List<String> {
  val byPeriod = calendar?.periods.orEmpty().associate { it.period to it.label }
  return (1..CropCalendarData.PERIODS).map { period ->
    byPeriod[period]?.takeIf { it.isNotBlank() } ?: "P$period"
  }
}

/** One period's label, for a period number that may be out of range or unknown. */
internal fun periodLabel(periods: List<String>, period: Int): String = periods.getOrNull(period - 1) ?: "P$period"

/**
 * A board price. Rounded to whole currency once it is over 100 per 1000 l — the range where the table
 * is comparing hundreds and the decimals are noise — and to one place below it, where water at 6.4
 * and lime at 62.5 would otherwise read as the same kind of number.
 */
internal fun formatPrice(value: Float): String = if (abs(value) >= 100f) {
  formatMoney(value.roundToLong())
} else {
  val tenths = (value * 10).roundToInt()
  "${formatMoney((tenths / 10).toLong())}.${abs(tenths % 10)}"
}

/** The economy multiplier, to one decimal — the game's own three settings are 3.0 / 1.8 / 1.0. */
internal fun formatMultiplier(value: Float): String {
  val tenths = (value * 10).roundToInt()
  return "${tenths / 10}.${tenths % 10}"
}

/** A total, in whole currency: nobody reads the pennies on a silo full of wheat. */
internal fun formatCurrency(value: Float): String = formatMoney(value.roundToLong())

/**
 * Where a price is heading, as the game's own three-way arrow.
 *
 * An [Icon] and not a character: the wasm build has no font fallback, so `▲ ▼` would render as tofu
 * (see `VDTerminal/README.md` → "Design rules"). Shape carries the state — up, down, flat — and the
 * colour only reinforces it, so the three stay apart for a reader who cannot tell green from red.
 */
@Composable
internal fun TrendMark(trend: String, modifier: Modifier = Modifier) {
  val (icon, tint, label) = when (trend) {
    "climbing" -> Triple(Icons.Filled.TrendingUp, VdtColors.AccentText, "climbing")
    "falling" -> Triple(Icons.Filled.TrendingDown, VdtColors.Red, "falling")
    else -> Triple(Icons.Filled.TrendingFlat, VdtColors.DarkGray, "steady")
  }
  Icon(icon, contentDescription = label, tint = tint, modifier = modifier.size(14.dp))
}

/** Ink for a projection: the same green the app uses for money earned, muted when there is none. */
internal fun projectionColor(gain: Boolean): Color = if (gain) VdtColors.AccentText else VdtColors.DarkGray
