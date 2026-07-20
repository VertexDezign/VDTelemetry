package net.vertexdezign.vdt.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.russhwolf.settings.StorageSettings
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.vertexdezign.vdt.app.alerts.AlertEngine
import net.vertexdezign.vdt.app.alerts.AlertInputs
import net.vertexdezign.vdt.app.alerts.AlertSeverity
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.net.TelemetryRepository
import net.vertexdezign.vdt.app.pages.PageStore
import net.vertexdezign.vdt.app.state.VdtStore

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val scope = MainScope()

  // Build URLs from the page location, same as the old socket.ts / API proxy.
  val location = window.location
  val wsProtocol = if (location.protocol == "https:") "wss:" else "ws:"
  val wsUrl = "$wsProtocol//${location.host}/ws"
  val mapUrl = "${location.protocol}//${location.host}/api/map-image"
  val mapLayerUrl = "${location.protocol}//${location.host}/api/map-layer"

  val repository = TelemetryRepository(scope, wsUrl)
  repository.start()

  // Screen wake lock: reflect whether the lock is *actually* held. The request resolves
  // asynchronously and can fail (e.g. Firefox iOS), and the browser drops the lock when the tab
  // hides, so poll the flag into a flow the store exposes.
  val wakeLock = MutableStateFlow(currentWakeStatus())
  scope.launch {
    while (isActive && WakeLock.supported) {
      wakeLock.value = currentWakeStatus()
      delay(500)
    }
  }

  val settings = StorageSettings()

  // Every app's alert rules run shell-wide over the raw data streams, whatever is on screen. A
  // tick of any channel re-evaluates all rules against the latest snapshot of every channel.
  val alerts = AlertEngine(AppRegistry.apps.flatMap { it.alerts })
  scope.launch {
    combine(repository.telemetry, repository.taskList) { telemetry, taskList ->
      AlertInputs(telemetry = telemetry, taskList = taskList)
    }.collect { alerts.process(it) }
  }

  // Audible cue per raise, alongside the banner. Info stays silent — it's passive by definition;
  // a chime the driver must react to means at least Warning.
  AlertSound.install()
  scope.launch {
    alerts.raised.collect { if (it.rule.severity != AlertSeverity.Info) AlertSound.play() }
  }

  val store =
    VdtStore(
      telemetry = repository.telemetry,
      connection = repository.connection,
      sampleIntervalMs = repository.sampleIntervalMs,
      taskList = repository.taskList,
      cropRotation = repository.cropRotation,
      mapData = repository.mapData,
      mapVehicles = repository.mapVehicles,
      mapLayers = repository.mapLayers,
      fieldInfo = repository.fieldInfo,
      production = repository.production,
      storage = repository.storage,
      husbandry = repository.husbandry,
      channelStats = repository.channelStats,
      wakeLock = wakeLock.asStateFlow(),
      mapUrl = mapUrl,
      mapLayerUrl = mapLayerUrl,
      settings = settings,
      pages = PageStore(settings),
      alerts = alerts,
      onToggleWakeLock = {
        WakeLock.toggle()
        wakeLock.value = currentWakeStatus()
      },
      onCommand = repository::send,
    )

  ComposeViewport(document.body!!) { App(store) }
}

private fun currentWakeStatus(): WakeLockStatus = when {
  !WakeLock.supported -> WakeLockStatus.Unsupported
  WakeLock.active -> WakeLockStatus.On
  else -> WakeLockStatus.Off
}
