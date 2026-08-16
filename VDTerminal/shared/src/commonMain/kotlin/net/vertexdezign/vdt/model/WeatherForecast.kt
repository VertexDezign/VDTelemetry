package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **weather** channel the mod writes to `weather.json` (separate file,
 * event-driven — see the mod's `src/collect/WeatherExporter.lua`): the forecast, as the game's own
 * *Anbaukalender* shows it — current conditions, twelve two-hourly steps ahead, six days out.
 *
 * Named `WeatherForecastData` rather than `Weather` because [Weather] is already taken: that one is
 * the live min/max/current temperature block on the telemetry channel's [Environment], published at
 * the ~100 ms tick. This is the eighteen-entry forecast, published on the in-game hour.
 *
 * Every temperature here is **already in the player's chosen unit** and [temperatureUnit] names it,
 * so the app prints rather than converts.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 */
@Serializable
data class WeatherForecastData(
  val version: String = "",
  /** The unit every temperature in this payload is in — "°C" or "°F". */
  val temperatureUnit: String = "",
  val today: WeatherDay? = null,
  val current: ForecastNow? = null,
  /** Twelve steps two hours apart, starting now. Ordered, not sorted — see [ForecastHour.hour]. */
  val hourly: List<ForecastHour> = emptyList(),
  /** Six days, starting tomorrow; today is [current] / [today]. */
  val daily: List<ForecastDay> = emptyList(),
)

/** Which day the forecast starts from. */
@Serializable
data class WeatherDay(
  /**
   * The game's own localized caption ("August 1"). Not derivable on this side: it folds in the
   * hemisphere's month shift, and the game drops the day number entirely when a period is one day
   * long.
   */
  val label: String = "",
  val period: Int = 0,
  val dayInPeriod: Int = 0,
)

/** Current conditions. */
@Serializable
data class ForecastNow(
  /** A [WeatherKind] name; see [kind] for the parsed form. */
  val type: String = "",
  val temperature: Int = 0,
  /** m/s — the honest measurement. [windBeaufort] is what the game prints. */
  val windSpeed: Float = 0f,
  /** The game's own Beaufort number, so our readout matches the one in the menu. */
  val windBeaufort: Int = 0,
  /** Degrees; the arrow points at `windDirection + 180`, as the game draws it. */
  val windDirection: Int = 0,
) {
  val kind: WeatherKind get() = WeatherKind.of(type)
}

/** One step of the hourly strip. */
@Serializable
data class ForecastHour(
  /**
   * The hour of the in-game day, 0..23. The strip runs forward from now and **wraps past midnight**,
   * so it is ordered rather than sorted: this number alone does not say which day the step is on,
   * and the list must be rendered in the order it arrived.
   */
  val hour: Int = 0,
  val type: String = "",
  val temperature: Int = 0,
  val windSpeed: Float = 0f,
  val windBeaufort: Int = 0,
  val windDirection: Int = 0,
) {
  val kind: WeatherKind get() = WeatherKind.of(type)
}

/** One day of the outlook. */
@Serializable
data class ForecastDay(
  /** The game's own localized short caption ("Aug 2"). */
  val label: String = "",
  val period: Int = 0,
  val dayInPeriod: Int = 0,
  /** The day's dominant type, as the game aggregates it across that day's forecast items. */
  val type: String = "",
  val high: Int = 0,
  val low: Int = 0,
) {
  val kind: WeatherKind get() = WeatherKind.of(type)
}

/**
 * The engine's `WeatherType`, parsed from the wire's name.
 *
 * Deliberately parsed by hand rather than serialized as an enum: a future game version adding a
 * weather type would make a strict enum decode throw and take the whole channel down, where
 * [UNKNOWN] costs one unrecognised icon.
 */
enum class WeatherKind {
  SUN,
  PARTIALLY_CLOUDY,
  CLOUDY,
  RAIN,
  SNOW,
  HAIL,
  TWISTER,
  THUNDER,
  UNKNOWN,
  ;

  companion object {
    fun of(name: String): WeatherKind = entries.firstOrNull { it.name == name } ?: UNKNOWN
  }
}
