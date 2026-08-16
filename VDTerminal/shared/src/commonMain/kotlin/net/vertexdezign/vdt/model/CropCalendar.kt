package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **crop calendar** channel the mod writes to `cropCalendar.json` (separate file,
 * event-driven — see the mod's `src/collect/CropCalendarExporter.lua`): for every crop the game
 * shows on its map, which of the twelve periods it may be sown in and which it may be harvested in.
 *
 * This is the game's own *Anbaukalender*. World state rather than farm state — every farm on a
 * server reads the identical calendar — and near-static: the crop rows are fixed at map load and only
 * [today] moves, which is why the channel is rewritten per in-game day rather than on a clock.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 */
@Serializable
data class CropCalendarData(
  val version: String = "",
  /**
   * `SEASONAL`, `DAILY` or `DISABLED`. **This decides what the rest of the file means.** Outside
   * `SEASONAL` the game answers "yes" to every period for every crop, so [CalendarCrop.plant] and
   * [CalendarCrop.harvest] are all twelve and say nothing — the app tells the user that instead of
   * drawing twelve full bars and letting them conclude the data is broken. See [isSeasonal].
   */
  val growthMode: String = "",
  val today: CalendarToday? = null,
  /** The twelve columns, in order. */
  val periods: List<CalendarPeriod> = emptyList(),
  /** Sorted by [CalendarCrop.name], the way the game's own frame sorts them. */
  val crops: List<CalendarCrop> = emptyList(),
) {
  /** Whether the sow/harvest periods carry information at all — see [growthMode]. */
  val isSeasonal: Boolean get() = growthMode == SEASONAL

  /**
   * Where the "today" line sits across the whole twelve-period grid, as a fraction in `[0,1)`; null
   * while [today] is absent.
   *
   * The half-day offset centres the line on the current day rather than putting it at the day's
   * leading edge, matching what the game's own `updateTodayBar` draws. Expressed per period rather
   * than per season (the game's form is `season * 0.25 + intoSeason * 0.25`) — identical arithmetic,
   * since a season is exactly three periods, and it avoids carrying a second unit around.
   */
  val todayFraction: Float?
    get() {
      val now = today ?: return null
      val days = now.daysPerPeriod.coerceAtLeast(1)
      val intoPeriod = (now.dayInPeriod - 1 + 0.5f) / days
      return ((now.period - 1) + intoPeriod) / PERIODS
    }

  companion object {
    /** The calendar is always twelve periods; the game hardcodes the same bound. */
    const val PERIODS: Int = 12
    const val SEASONAL: String = "SEASONAL"
  }
}

/** Where the year currently stands, for the "today" marker. */
@Serializable
data class CalendarToday(
  /** The current period, 1..12 (1 is the first period of spring). */
  val period: Int = 1,
  /** The day within that period, 1..[daysPerPeriod]. */
  val dayInPeriod: Int = 1,
  /** The season-length setting; the player can change it mid-game, which moves the marker. */
  val daysPerPeriod: Int = 1,
  /** The current game year, 1-based. */
  val year: Int = 1,
)

/** One column of the calendar. */
@Serializable
data class CalendarPeriod(
  /** 1..12. */
  val period: Int = 0,
  /**
   * The game's own localized short label ("Mar", "Sep", …).
   *
   * **Not derivable from [period].** The game shifts the month by hemisphere, so on a southern map
   * period 1 is September rather than March — which is why the label crosses the wire instead of
   * being a lookup table on this side.
   */
  val label: String = "",
  /** `SPRING`, `SUMMER`, `AUTUMN` or `WINTER` — three periods each. */
  val season: String = "",
)

/** One crop row. */
@Serializable
data class CalendarCrop(
  /** The fruit type's internal name ("WHEAT"). Stable across locales, so it is the row key. */
  val id: String = "",
  /** The localized display name, from the fruit's fill type title ("Weizen"). */
  val name: String = "",
  /** True for a cover/catch crop. */
  val catchCrop: Boolean = false,
  /** The periods it may be sown in, ascending; empty when there are none. */
  val plant: List<Int> = emptyList(),
  /** The periods it may be harvested in, ascending; empty when there are none. */
  val harvest: List<Int> = emptyList(),
)

/**
 * Merge ascending period numbers into contiguous runs, so a crop draws as bars rather than as twelve
 * separate cells.
 *
 * A crop's periods are not necessarily one run: the game's meadow grass sows March through October
 * *and* again in February, which arrives as `[1..8, 12]` and must draw as two bars. The list is
 * sorted defensively — the mod emits it ascending, but a single out-of-order entry would otherwise
 * silently split every following run.
 */
fun List<Int>.periodRuns(): List<IntRange> {
  if (isEmpty()) return emptyList()
  val runs = mutableListOf<IntRange>()
  val ordered = sorted()
  var start = ordered.first()
  var previous = start
  for (period in ordered.drop(1)) {
    // Equal rather than only greater: a duplicate would otherwise open a new run of length zero.
    if (period == previous || period == previous + 1) {
      previous = period
      continue
    }
    runs += start..previous
    start = period
    previous = period
  }
  runs += start..previous
  return runs
}
