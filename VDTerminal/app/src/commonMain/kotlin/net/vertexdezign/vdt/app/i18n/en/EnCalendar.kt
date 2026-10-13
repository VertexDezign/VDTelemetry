package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.CalendarStrings

internal object EnCalendar : CalendarStrings {
  override val waiting = "Waiting for calendar data…"
  override val waitingCrops = "Waiting for the crop calendar…"
  override val noCrops = "No crops on this map"
  override val search = "Search crop…"
  override fun sowNow(count: Int): String = "Sow now ($count)"
  override fun harvestNow(count: Int): String = "Harvest now ($count)"
  override val noMatch = "No crops match"
  override val coverCrop = "Cover crop"
  override val sowKey = "S"
  override val harvestKey = "H"
  override val sow = "Sow"
  override val harvest = "Harvest"

  override val spring = "Spring"
  override val summer = "Summer"
  override val autumn = "Autumn"
  override val winter = "Winter"
  override val growthDaily = "Growth is set to Daily — every crop can be sown and harvested in any period."
  override val growthDisabled = "Growth is disabled — every crop can be sown and harvested in any period."
  override val growthOther =
    "This savegame is not on seasonal growth — every crop can be sown and harvested in any period."

  override val weather = "Weather"
  override val waitingForecast = "Waiting for the forecast…"
  override val today = "Today"
  override fun beaufort(force: Int): String = "Bft $force"
  override fun windFrom(degrees: Int): String = "Wind from $degrees°"

  override val sunny = "Sunny"
  override val partlyCloudy = "Partly cloudy"
  override val cloudy = "Cloudy"
  override val rain = "Rain"
  override val snow = "Snow"
  override val hail = "Hail"
  override val twister = "Twister"
  override val thunderstorm = "Thunderstorm"
  override val unknown = "Unknown"
}
