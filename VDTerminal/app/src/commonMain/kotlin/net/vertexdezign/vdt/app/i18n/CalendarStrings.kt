package net.vertexdezign.vdt.app.i18n

/** The Calendar app: the crop calendar and the forecast, and the Weather tile. */
interface CalendarStrings {
  val waiting: String
  val waitingCrops: String
  val noCrops: String
  val search: String
  fun sowNow(count: Int): String
  fun harvestNow(count: Int): String
  val noMatch: String
  val coverCrop: String

  /** A crop row's two lane letters, and the legend that explains them. */
  val sowKey: String
  val harvestKey: String
  val sow: String
  val harvest: String

  val spring: String
  val summer: String
  val autumn: String
  val winter: String
  val growthDaily: String
  val growthDisabled: String
  val growthOther: String

  val weather: String
  val waitingForecast: String
  val today: String
  fun beaufort(force: Int): String
  fun windFrom(degrees: Int): String

  val sunny: String
  val partlyCloudy: String
  val cloudy: String
  val rain: String
  val snow: String
  val hail: String
  val twister: String
  val thunderstorm: String
  val unknown: String
}
