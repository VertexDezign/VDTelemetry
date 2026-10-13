package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.DiagnosticsStrings

internal object EnDiagnostics : DiagnosticsStrings {
  override val title = "Channel Diagnostics"
  override val waiting = "WAITING FOR STATS…"
  override val channel = "CHANNEL"
  override val cadence = "CADENCE"
  override val minMax = "MIN / MAX"
  override val lastSeen = "LAST SEEN"
  override val writes = "WRITES"
  override val never = "never"
  override val justNow = "just now"
  override fun secondsAgo(seconds: Int): String = "${seconds}s ago"
  override fun minutesAgo(minutes: Int): String = "${minutes}m ago"
  override fun mapLayer(plane: String): String = "Map layer: $plane"

  override fun channelName(fileName: String): String? = EN_CHANNELS[fileName]
}

// index.json is mapLayers/index.json, the only index.json there is.
private val EN_CHANNELS =
  mapOf(
    "vdTelemetry.json" to "Vehicle telemetry",
    "map.json" to "Map",
    "index.json" to "Map layers (catalogue)",
    "mapVehicles.json" to "Map vehicles",
    "gpsCourse.json" to "GPS course",
    "production.json" to "Production",
    "storage.json" to "Storage",
    "husbandry.json" to "Animals",
    "fleet.json" to "Fleet",
    "prices.json" to "Prices",
    "autoDrive.json" to "AutoDrive",
    "missions.json" to "Missions",
    "fieldInfo.json" to "Field info",
    "cropCalendar.json" to "Crop calendar",
    "weather.json" to "Weather",
    "finance.json" to "Finance",
    "invoices.json" to "Invoices",
    "taskList.json" to "Task list",
    "cropRotation.json" to "Crop rotation",
  )
