package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.CalendarStrings

internal object DeCalendar : CalendarStrings {
  override val waiting = "Warte auf Kalenderdaten…"
  override val waitingCrops = "Warte auf den Anbaukalender…"
  override val noCrops = "Keine Feldfrüchte auf dieser Karte"
  override val search = "Frucht suchen…"
  override fun sowNow(count: Int): String = "Jetzt säen ($count)"
  override fun harvestNow(count: Int): String = "Jetzt ernten ($count)"
  override val noMatch = "Keine passende Frucht"
  override val coverCrop = "Zwischenfrucht"
  override val sowKey = "S"
  override val harvestKey = "E"
  override val sow = "Säen"
  override val harvest = "Ernten"

  override val spring = "Frühling"
  override val summer = "Sommer"
  override val autumn = "Herbst"
  override val winter = "Winter"
  override val growthDaily = "Wachstum steht auf Täglich — jede Frucht kann in jeder Periode gesät und geerntet werden."
  override val growthDisabled =
    "Wachstum ist deaktiviert — jede Frucht kann in jeder Periode gesät und geerntet werden."
  override val growthOther =
    "Dieser Spielstand nutzt kein saisonales Wachstum — jede Frucht kann in jeder Periode gesät und geerntet werden."

  override val weather = "Wetter"
  override val waitingForecast = "Warte auf die Vorhersage…"
  override val today = "Heute"
  override fun beaufort(force: Int): String = "Bft $force"
  override fun windFrom(degrees: Int): String = "Wind aus $degrees°"

  override val sunny = "Sonnig"
  override val partlyCloudy = "Teilweise bewölkt"
  override val cloudy = "Bewölkt"
  override val rain = "Regen"
  override val snow = "Schnee"
  override val hail = "Hagel"
  override val twister = "Tornado"
  override val thunderstorm = "Gewitter"
  override val unknown = "Unbekannt"
}
