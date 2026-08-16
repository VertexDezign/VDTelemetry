package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SearchField
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CalendarCrop
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.ForecastDay
import net.vertexdezign.vdt.model.ForecastHour
import net.vertexdezign.vdt.model.ForecastNow
import net.vertexdezign.vdt.model.WeatherForecastData
import net.vertexdezign.vdt.model.periodRuns

/**
 * The Calendar screen: the game's own *Anbaukalender*, plus the two questions it makes you scan the
 * whole grid to answer — **what can I sow now**, and **what can I harvest now** — as a search box and
 * two filters.
 *
 * Two channels feed it (`cropCalendar.json` and `weather.json`) on two very different cadences, and
 * each half renders its own absent state: turning one channel off in the mod's settings leaves the
 * other working.
 */
@Composable
fun CalendarPanel(calendar: CropCalendarData?, weather: WeatherForecastData?, modifier: Modifier = Modifier) {
  Panel(title = "Calendar", icon = Icons.Filled.CalendarMonth, modifier = modifier) {
    if (calendar == null && weather == null) {
      Centered("Waiting for calendar data…")
      return@Panel
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      CropCalendarSection(calendar, Modifier.weight(1f))
      WeatherSection(weather)
    }
  }
}

// ---- Crop calendar ----

@Composable
private fun CropCalendarSection(data: CropCalendarData?, modifier: Modifier = Modifier) {
  if (data == null || data.crops.isEmpty()) {
    Box(modifier.fillMaxWidth()) {
      Centered(if (data == null) "Waiting for the crop calendar…" else "No crops on this map")
    }
    return
  }

  var query by remember { mutableStateOf("") }
  var sowNow by remember { mutableStateOf(false) }
  var harvestNow by remember { mutableStateOf(false) }
  // Held by crop id, not by row index: the list is re-filtered and re-sorted under it, and an index
  // would silently move the highlight to whatever crop landed in that slot.
  var selectedId by remember { mutableStateOf<String?>(null) }

  val period = data.today?.period ?: 0
  val sowable = remember(data) { data.crops.count { period in it.plant } }
  val harvestable = remember(data) { data.crops.count { period in it.harvest } }
  val rows = remember(data, query, sowNow, harvestNow) { filterCrops(data, query, sowNow, harvestNow) }

  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SearchField(
        value = query,
        placeholder = "Search crop…",
        onValueChange = { query = it },
        modifier = Modifier.width(180.dp),
      )
      // The counts sit in the labels so the answer is there without clicking: the whole reason to
      // open this screen is usually just "how many can I sow today".
      FilterChip("Sow now ($sowable)", sowNow, { sowNow = !sowNow })
      FilterChip("Harvest now ($harvestable)", harvestNow, { harvestNow = !harvestNow })
      Spacer(Modifier.weight(1f))
      Legend()
    }

    if (!data.isSeasonal) {
      // Outside seasonal growth the game answers "yes" to every period for every crop, so every bar
      // below is full. Saying so beats letting the grid look broken.
      GrowthModeBanner(data.growthMode)
    }

    if (rows.isEmpty()) {
      Box(Modifier.fillMaxWidth().weight(1f)) { Centered("No crops match") }
      return@Column
    }

    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
      // Fill the width when there is room, scroll when there is not: the grid never squeezes a period
      // narrower than PERIOD_MIN_WIDTH, below which a one-period bar stops being a bar.
      val viewport = maxWidth - NAME_COLUMN_WIDTH
      val periodWidth = maxOf(PERIOD_MIN_WIDTH, viewport / CropCalendarData.PERIODS)
      val gridWidth = periodWidth * CropCalendarData.PERIODS
      val todayFraction = data.todayFraction

      // Four scroll containers over two shared ScrollStates, rather than one per row.
      //
      // Horizontally, the header and the bars move together so a column stays under its label;
      // vertically, the names and the bars move together so a row stays beside its name. Sharing a
      // ScrollState is what couples each pair: both containers of a pair hold the same content and
      // viewport size, so they agree on the scroll range and simply read the same offset.
      //
      // The header sits OUTSIDE the vertical pair on purpose — it scrolls sideways with the grid and
      // stays put as the crops scroll under it. The name column is outside the horizontal one for the
      // mirrored reason: it is the pinned column.
      val hScroll = rememberScrollState()
      val vScroll = rememberScrollState()

      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
          Spacer(Modifier.width(NAME_COLUMN_WIDTH))
          Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
            CalendarHeader(data, periodWidth, gridWidth, todayFraction)
          }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
          // Alternate rows are shaded, the way the in-game calendar shades its own: twelve columns
          // wide, the eye needs something to run along or it loses which row a far-right bar belongs
          // to. The stripe is keyed on the row's position in the list, so the name column and the
          // bars — which scroll together but are laid out separately — shade the same rows.
          Column(Modifier.width(NAME_COLUMN_WIDTH).verticalScroll(vScroll)) {
            rows.forEachIndexed { index, crop ->
              CropNameCell(
                crop = crop,
                currentPeriod = period,
                striped = isStriped(index),
                selected = selectedId == crop.id,
                onSelect = { selectedId = toggleSelection(selectedId, crop.id) },
              )
            }
          }
          Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Column(Modifier.verticalScroll(vScroll)) {
              rows.forEachIndexed { index, crop ->
                CropLanes(
                  crop = crop,
                  periodWidth = periodWidth,
                  gridWidth = gridWidth,
                  todayFraction = todayFraction,
                  striped = isStriped(index),
                  selected = selectedId == crop.id,
                  onSelect = { selectedId = toggleSelection(selectedId, crop.id) },
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The season band over the twelve period labels.
 *
 * Laid out over the fixed twelve rather than over `data.periods`, and each column looked up by its
 * number: the bars and the grid lines are always twelve wide, so a file that arrived short a period
 * would otherwise slide every label out from under its column instead of leaving one blank.
 *
 * Neither band is given a fixed height. `Modifier.height()` is an *exact* constraint, so a text
 * measured inside one is clipped rather than overflowing when the font's line box is taller than the
 * number guessed here — which is what cut the descenders off "Spring" and "Autumn". Padding sizes
 * these rows instead, and they end up as tall as the type actually needs.
 */
@Composable
private fun CalendarHeader(data: CropCalendarData, periodWidth: Dp, gridWidth: Dp, todayFraction: Float?) {
  val byPeriod = remember(data) { data.periods.associateBy { it.period } }
  Column(Modifier.width(gridWidth)) {
    Row(Modifier.fillMaxWidth()) {
      // One cell per season rather than per period: a season is exactly three periods, so the label
      // centres over its own span the way the game's season mark does.
      for (season in 0 until CropCalendarData.PERIODS / SEASON_PERIODS) {
        Box(
          Modifier.width(periodWidth * SEASON_PERIODS).padding(vertical = 3.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            seasonLabel(byPeriod[season * SEASON_PERIODS + 1]?.season ?: ""),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = VdtColors.DarkGray,
            maxLines = 1,
            overflow = TextOverflow.Clip,
          )
        }
      }
    }
    Box(Modifier.fillMaxWidth()) {
      GridLines(todayFraction, Modifier.matchParentSize())
      Row(Modifier.fillMaxWidth()) {
        for (period in 1..CropCalendarData.PERIODS) {
          Box(
            Modifier.width(periodWidth).padding(vertical = 3.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              (byPeriod[period]?.label ?: "").uppercase(),
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
              color = VdtColors.TextDark,
              maxLines = 1,
              overflow = TextOverflow.Clip,
            )
          }
        }
      }
    }
    // The rule the grid hangs from, as in the game's own header.
    Box(Modifier.fillMaxWidth().height(1.dp).background(VdtColors.PanelBorder))
  }
}

/**
 * The pinned left cell: the crop's name, and the two lane letters.
 *
 * The letters are the lane key. Sow is always the upper lane and harvest always the lower, so
 * position alone already decides which bar is which — `S` and `H` make that readable without
 * consulting the legend, and neither depends on telling green from blue.
 *
 * Selection lives on the leading edge as a solid bar as well as in the row's wash: the wash alone is
 * a small step in lightness, and a bar at the row's start is what makes the highlight unmistakable
 * without leaning on a colour.
 */
@Composable
private fun CropNameCell(
  crop: CalendarCrop,
  currentPeriod: Int,
  striped: Boolean,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  Row(
    Modifier
      .width(NAME_COLUMN_WIDTH)
      .height(ROW_HEIGHT)
      .background(rowShade(striped, selected))
      .selectable(selected = selected, onClick = onSelect)
      .padding(end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .width(SELECTION_EDGE_WIDTH)
        .fillMaxHeight()
        .background(if (selected) VdtColors.TextDark else Color.Transparent),
    )
    Spacer(Modifier.width(4.dp))
    Column(Modifier.weight(1f)) {
      Text(
        crop.name,
        fontSize = 11.sp,
        color = VdtColors.TextDark,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (crop.catchCrop) {
        // Every ink on a selected row is TextDark: DarkGray reads at only 3.9:1 on the selection
        // wash, under AA. Quiet-vs-loud inside the row is carried by weight and size instead, which
        // is what the palette asks for anyway (see VdtColors) — never by a paler grey.
        Text("Cover crop", fontSize = 8.sp, color = rowInk(selected, quiet = true), maxLines = 1)
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(LANE_GAP)) {
      LaneKey("S", currentPeriod in crop.plant, selected)
      LaneKey("H", currentPeriod in crop.harvest, selected)
    }
  }
}

/**
 * One lane's letter. [activeNow] bolds and darkens it — two channels, because [VdtColors.DarkGray]
 * and any of the fills sit at nearly the same contrast and would otherwise differ in hue alone.
 *
 * On a [selected] row the ink is forced dark for contrast (see [rowInk]), so there the two states are
 * told apart by weight alone. That is the channel the palette prefers regardless; the colour was only
 * ever reinforcing it.
 */
@Composable
private fun LaneKey(letter: String, activeNow: Boolean, selected: Boolean) {
  Box(Modifier.height(LANE_HEIGHT), contentAlignment = Alignment.Center) {
    Text(
      letter,
      fontSize = 8.sp,
      fontWeight = if (activeNow) FontWeight.Bold else FontWeight.Normal,
      color = rowInk(selected, quiet = !activeNow),
    )
  }
}

/** A crop's two bar lanes over the twelve periods. Selectable too — a row is tapped from either half. */
@Composable
private fun CropLanes(
  crop: CalendarCrop,
  periodWidth: Dp,
  gridWidth: Dp,
  todayFraction: Float?,
  striped: Boolean,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  Box(
    Modifier
      .width(gridWidth)
      .height(ROW_HEIGHT)
      .background(rowShade(striped, selected))
      .selectable(selected = selected, onClick = onSelect),
  ) {
    GridLines(todayFraction, Modifier.fillMaxSize())
    Column(
      Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
    ) {
      Lane(crop.plant, periodWidth, VdtColors.Green, RectangleShape)
      Spacer(Modifier.height(LANE_GAP))
      Lane(crop.harvest, periodWidth, VdtColors.ProgressBlue, RoundedCornerShape(percent = 50))
    }
  }
}

/**
 * One lane's bars.
 *
 * [shape] is not decoration: square-ended for sowing and capsule-ended for harvest is the third thing
 * separating the two lanes (after their fixed order and their letters), so nothing about reading this
 * grid rests on telling the two fills apart by hue.
 *
 * Periods are merged into runs first — a crop that sows March through October *and again* in February
 * is one list of periods that has to draw as two bars.
 */
@Composable
private fun Lane(periods: List<Int>, periodWidth: Dp, color: Color, shape: Shape) {
  Box(Modifier.fillMaxWidth().height(LANE_HEIGHT)) {
    periods.periodRuns().forEach { run ->
      Box(
        Modifier
          .offset(x = periodWidth * (run.first - 1) + LANE_INSET)
          .width(periodWidth * (run.last - run.first + 1) - LANE_INSET * 2)
          .height(LANE_HEIGHT)
          .clip(shape)
          .background(color),
      )
    }
  }
}

/**
 * The period separators and the today line, drawn behind the bars.
 *
 * Works from its own measured width rather than from the period width the callers lay out with: the
 * canvas is always exactly the twelve periods wide, so dividing by [CropCalendarData.PERIODS] is the
 * same number and cannot drift from it.
 *
 * The today line is dashed for the same reason the game's is: it crosses every bar in the grid, and a
 * solid rule at that length reads as part of the chart rather than as a marker on it.
 */
@Composable
private fun GridLines(todayFraction: Float?, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val step = size.width / CropCalendarData.PERIODS
    for (index in 1 until CropCalendarData.PERIODS) {
      val x = step * index
      // Season boundaries every third period get the heavier rule, as the game's grid does.
      val seasonBoundary = index % SEASON_PERIODS == 0
      drawLine(
        color = if (seasonBoundary) GRID_RULE_SEASON else GRID_RULE,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = if (seasonBoundary) 1.5f else 1f,
      )
    }
    if (todayFraction != null) drawTodayLine(todayFraction)
  }
}

private fun DrawScope.drawTodayLine(fraction: Float) {
  val x = size.width * fraction
  drawLine(
    color = VdtColors.TextDark,
    start = Offset(x, 0f),
    end = Offset(x, size.height),
    strokeWidth = 1.5f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
  )
}

@Composable
private fun Legend() {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    LegendEntry("Sow", VdtColors.Green, RectangleShape)
    LegendEntry("Harvest", VdtColors.ProgressBlue, RoundedCornerShape(percent = 50))
  }
}

@Composable
private fun LegendEntry(label: String, color: Color, shape: Shape) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.width(16.dp).height(LANE_HEIGHT).clip(shape).background(color))
    Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
  }
}

@Composable
private fun GrowthModeBanner(growthMode: String) {
  Text(
    growthModeNotice(growthMode),
    fontSize = 10.sp,
    color = VdtColors.TextDark,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 8.dp, vertical = 5.dp),
  )
}

// ---- Weather ----

@Composable
private fun WeatherSection(data: WeatherForecastData?, modifier: Modifier = Modifier) {
  Box(
    modifier
      .fillMaxWidth()
      .height(WEATHER_HEIGHT)
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.White.copy(alpha = 0.6f)),
  ) {
    if (data == null) {
      Centered("Waiting for the forecast…")
      return@Box
    }
    // The three blocks scroll as one strip rather than shrinking: a forecast that has squeezed its
    // temperatures out of legibility is not a forecast.
    Row(
      Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      NowBlock(data)
      VerticalRule()
      data.hourly.forEach { HourBlock(it) }
      if (data.daily.isNotEmpty()) {
        VerticalRule()
        data.daily.forEach { DayBlock(it, data.temperatureUnit) }
      }
    }
  }
}

/**
 * The forecast as a placeable tile: current conditions, then as many two-hourly steps as the tile is
 * wide enough for. Deliberately the same blocks the full page draws — a widget that rendered the
 * weather its own way would be a second thing to keep in step for no gain.
 *
 * The hours scroll rather than shrink, for the same reason the section's do.
 */
@Composable
fun WeatherSummary(data: WeatherForecastData?, modifier: Modifier = Modifier) {
  Panel(title = "Weather", icon = WeatherIcons.PartiallyCloudy, modifier = modifier) {
    if (data == null) {
      Centered("Waiting for the forecast…")
      return@Panel
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Box(Modifier.height(WIDGET_NOW_HEIGHT)) { NowBlock(data) }
      if (data.hourly.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(VdtColors.PanelBorder))
        Row(
          Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          data.hourly.forEach { HourBlock(it) }
        }
      }
    }
  }
}

@Composable
private fun NowBlock(data: WeatherForecastData) {
  val now: ForecastNow? = data.current
  Row(
    Modifier.fillMaxHeight(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (now != null) {
      Icon(
        WeatherIcons.of(now.kind),
        contentDescription = WeatherIcons.labelOf(now.kind),
        tint = VdtColors.TextDark,
        modifier = Modifier.size(40.dp),
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        (data.today?.label ?: "Today").uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = VdtColors.DarkGray,
        maxLines = 1,
      )
      if (now != null) {
        Text(
          "${now.temperature}${data.temperatureUnit}",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = VdtColors.TextDark,
        )
        Text(WeatherIcons.labelOf(now.kind), fontSize = 10.sp, color = VdtColors.DarkGray, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          WindVane(now.windDirection, 12.dp)
          Text("Bft ${now.windBeaufort}", fontSize = 10.sp, color = VdtColors.DarkGray, maxLines = 1)
        }
      }
    }
  }
}

@Composable
private fun HourBlock(hour: ForecastHour) {
  Column(
    Modifier.width(FORECAST_COLUMN_WIDTH).fillMaxHeight(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(formatHour(hour.hour), fontSize = 9.sp, color = VdtColors.DarkGray, maxLines = 1)
    Icon(
      WeatherIcons.of(hour.kind),
      contentDescription = WeatherIcons.labelOf(hour.kind),
      tint = VdtColors.TextDark,
      modifier = Modifier.size(20.dp).padding(vertical = 2.dp),
    )
    Text(
      "${hour.temperature}°",
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.TextDark,
      maxLines = 1,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
      WindVane(hour.windDirection, 9.dp)
      Text("${hour.windBeaufort}", fontSize = 9.sp, color = VdtColors.DarkGray, maxLines = 1)
    }
  }
}

@Composable
private fun DayBlock(day: ForecastDay, unit: String) {
  Column(
    Modifier.width(FORECAST_COLUMN_WIDTH).fillMaxHeight(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(day.label.uppercase(), fontSize = 9.sp, color = VdtColors.DarkGray, maxLines = 1)
    Icon(
      WeatherIcons.of(day.kind),
      contentDescription = WeatherIcons.labelOf(day.kind),
      tint = VdtColors.TextDark,
      modifier = Modifier.size(20.dp).padding(vertical = 2.dp),
    )
    Text(
      "${day.high}$unit",
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.TextDark,
      maxLines = 1,
    )
    Text("${day.low}$unit", fontSize = 10.sp, color = VdtColors.DarkGray, maxLines = 1)
  }
}

/**
 * The wind arrow, rotated the way the game rotates its own: the exported angle says where the wind
 * comes **from**, so the arrow is turned half a turn past it to point where the wind is going.
 */
@Composable
internal fun WindVane(windDirection: Int, size: Dp) {
  Icon(
    WeatherIcons.WindArrow,
    contentDescription = "Wind from $windDirection°",
    tint = VdtColors.DarkGray,
    modifier = Modifier.size(size).rotate(windArrowRotation(windDirection)),
  )
}

@Composable
private fun VerticalRule() {
  Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
}

// ---- Pure helpers (kept top-level and non-private so CalendarPanelTest can reach them) ----

/**
 * The crop rows a query and the two "now" filters leave.
 *
 * The filters are **not** mutually exclusive: both on means both conditions, which is how the
 * question is actually asked ("what is ready to come off and go straight back in").
 */
internal fun filterCrops(
  data: CropCalendarData,
  query: String,
  sowNow: Boolean,
  harvestNow: Boolean,
): List<CalendarCrop> {
  val needle = query.trim().lowercase()
  val period = data.today?.period ?: 0
  return data.crops.filter { crop ->
    (needle.isEmpty() || crop.name.lowercase().contains(needle) || crop.id.lowercase().contains(needle)) &&
      (!sowNow || period in crop.plant) &&
      (!harvestNow || period in crop.harvest)
  }
}

/** The in-game clock is fixed 24h, as everywhere else in the terminal. */
internal fun formatHour(hour: Int): String = "${hour.toString().padStart(2, '0')}:00"

/**
 * Degrees to turn [WeatherIcons.WindArrow] by; see [WindVane].
 *
 * Two corrections, not one. The `+ 180` is the game's: the exported angle says where the wind comes
 * *from*, so the arrow is turned half a turn past it to point where the wind is going. The **negation**
 * is ours: the engine measures its angles the way maths does, counter-clockwise, and drives its own
 * overlay with `setImageRotation`, which turns the same way — but Compose's `Modifier.rotate` turns
 * **clockwise** for a positive number. Feeding the engine's angle to it straight produced an arrow
 * mirrored about the vertical axis, which is exactly what negating undoes.
 *
 * This is the same handedness the mod's `ValueMapper.headingFromYRotation` exists to absorb; the wind
 * angle deliberately does not go through it (see the weather collector's header for why), so the
 * correction lands here instead.
 */
internal fun windArrowRotation(windDirection: Int): Float = (180 - windDirection).mod(360).toFloat()

internal fun seasonLabel(season: String): String = when (season) {
  "SPRING" -> "Spring"
  "SUMMER" -> "Summer"
  "AUTUMN" -> "Autumn"
  "WINTER" -> "Winter"
  else -> season
}

/** What the banner says when the savegame is not on seasonal growth. */
internal fun growthModeNotice(growthMode: String): String = when (growthMode) {
  "DAILY" -> "Growth is set to Daily — every crop can be sown and harvested in any period."
  "DISABLED" -> "Growth is disabled — every crop can be sown and harvested in any period."
  else -> "This savegame is not on seasonal growth — every crop can be sown and harvested in any period."
}

/** Whether the crop row at [index] carries the guidance shade. */
internal fun isStriped(index: Int): Boolean = index % 2 == 1

/**
 * Pressing a crop row highlights it; pressing it again clears it, and pressing another moves the
 * highlight. A toggle rather than a plain set, because the highlight is a reading aid across twelve
 * columns and not a mode — there has to be a way back out of it that is not "pick a different crop".
 */
internal fun toggleSelection(current: String?, id: String): String? = if (current == id) null else id

// ---- Metrics ----

/** A season is exactly three periods, in the game and here. */
private const val SEASON_PERIODS = 3

/**
 * A crop row's background: transparent, the guidance stripe, or the selection wash.
 *
 * The three are separated by **lightness alone** and no hue is involved anywhere — the stripe lifts
 * the panel the way the in-game calendar's banding does, the selection drops well below it. Against
 * the panel's own `#F0F0F2` the steps are `+15` and `-31` per channel, roughly twice the separation
 * the first cut used, which was too timid to follow across twelve columns.
 *
 * The stripe is plain [VdtColors.White] rather than an alpha over the panel, and the selection is
 * [VdtColors.PanelBorder]: both are palette tokens. The palette has nothing between `PanelBorder` and
 * `TrackGray`, so the selection lands slightly past a literal doubling rather than being given an
 * invented tone — [VdtColors.Gray] beyond it is the "unlit mark" tone and too dark to read a row on.
 *
 * Selection also carries the leading edge bar (see [CropNameCell]) and the darker ink below, so three
 * things say which row is picked, not one.
 */
private fun rowShade(striped: Boolean, selected: Boolean): Color = when {
  selected -> VdtColors.PanelBorder
  striped -> VdtColors.White
  else -> Color.Transparent
}

/**
 * Ink for text sitting on a crop row.
 *
 * [VdtColors.DarkGray] reads at 5.0:1 on the panel and 5.7:1 on the stripe, but only **3.9:1** on the
 * selection wash — under AA. So a selected row's text is all [VdtColors.TextDark] (8.6:1 there), and
 * the [quiet] distinction falls back to weight and size. That is the palette's own instruction anyway:
 * quieter text is made with size and weight, never with a paler grey.
 */
private fun rowInk(selected: Boolean, quiet: Boolean): Color =
  if (quiet && !selected) VdtColors.DarkGray else VdtColors.TextDark

/**
 * The grid rules, as an alpha over whatever the row is painted with rather than as a fixed grey.
 *
 * A row has three possible backgrounds (see [rowShade]) and a fixed tone can only suit one of them:
 * the season rule in [VdtColors.PanelBorder] vanished entirely on a selected row, which is painted in
 * that exact colour. A translucent dark line stays a line on all three.
 */
private val GRID_RULE = VdtColors.TextDark.copy(alpha = 0.10f)
private val GRID_RULE_SEASON = VdtColors.TextDark.copy(alpha = 0.22f)

/** The selected row's leading edge bar. */
private val SELECTION_EDGE_WIDTH = 3.dp

private val NAME_COLUMN_WIDTH = 132.dp
private val PERIOD_MIN_WIDTH = 46.dp
private val ROW_HEIGHT = 28.dp
private val LANE_HEIGHT = 8.dp
private val LANE_GAP = 3.dp

/** Breathing room at each end of a bar, so two adjacent runs never read as one. */
private val LANE_INSET = 1.dp

private val WEATHER_HEIGHT = 118.dp
private val FORECAST_COLUMN_WIDTH = 40.dp

/** The widget's "now" block: fixed, so the hours below get whatever height the tile has left. */
private val WIDGET_NOW_HEIGHT = 76.dp
