package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.DiagnosticsStrings

internal object DeDiagnostics : DiagnosticsStrings {
  override val title = "Kanal-Diagnose"
  override val waiting = "WARTE AUF STATISTIK…"
  override val channel = "KANAL"
  override val cadence = "TAKT"
  override val minMax = "MIN / MAX"
  override val lastSeen = "ZULETZT"
  override val writes = "SCHREIBVORGÄNGE"
  override val never = "nie"
  override val justNow = "gerade eben"
  override fun secondsAgo(seconds: Int): String = "vor $seconds s"
  override fun minutesAgo(minutes: Int): String = "vor $minutes min"
  override fun mapLayer(plane: String): String = "Kartenebene: $plane"

  override fun channelName(fileName: String): String? = DE_CHANNELS[fileName]
}

// index.json is mapLayers/index.json, the only index.json there is.
private val DE_CHANNELS =
  mapOf(
    "vdTelemetry.json" to "Fahrzeugtelemetrie",
    "map.json" to "Karte",
    "index.json" to "Kartenebenen (Katalog)",
    "mapVehicles.json" to "Kartenfahrzeuge",
    "gpsCourse.json" to "GPS-Spur",
    "production.json" to "Produktion",
    "storage.json" to "Lager",
    "husbandry.json" to "Tiere",
    "fleet.json" to "Fuhrpark",
    "prices.json" to "Preise",
    "autoDrive.json" to "AutoDrive",
    "missions.json" to "Aufträge",
    "fieldInfo.json" to "Feldinfo",
    "cropCalendar.json" to "Anbaukalender",
    "weather.json" to "Wetter",
    "finance.json" to "Finanzen",
    "invoices.json" to "Rechnungen",
    "taskList.json" to "Aufgabenliste",
    "cropRotation.json" to "Fruchtfolge",
  )
