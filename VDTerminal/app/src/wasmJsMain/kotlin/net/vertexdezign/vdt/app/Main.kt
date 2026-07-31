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
import net.vertexdezign.vdt.app.state.DisplayRequest
import net.vertexdezign.vdt.app.state.DisplayStore
import net.vertexdezign.vdt.app.state.FavouritesStore
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

  val settings = StorageSettings()

  // Display mode is settled before the first composition: the URL sets it, storage keeps it. Reading
  // `location.search` here is the app's only routing — everything downstream just sees a pinned id.
  val display = DisplayStore(settings)
  display.apply(DisplayRequest.parse(location.search))

  // Screen wake lock: reflect whether the lock is *actually* held. The request resolves
  // asynchronously and can fail (e.g. Firefox iOS), and the browser drops the lock when the tab
  // hides, so poll the flag into a flow the store exposes.
  //
  // A display asks for it up front — there is no header toggle on one, and a cluster that dims after
  // 30 seconds is useless. The Wake Lock spec needs no user gesture, but a browser that declines the
  // unprompted request gets one more try on the first touch; if it still refuses, the reveal overlay
  // is where that shows.
  if (display.target.value != null) WakeLock.enable()
  val wakeLock = MutableStateFlow(currentWakeStatus())
  scope.launch {
    while (isActive && WakeLock.supported) {
      wakeLock.value = currentWakeStatus()
      delay(500)
    }
  }

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
  //
  // Silent on a display, which shows no banners either: the tablet announces things, so two screens in
  // one cab don't chime at each other slightly out of sync. Read live rather than at startup so
  // leaving display mode restores the sound without a reload.
  AlertSound.install()
  scope.launch {
    alerts.raised.collect {
      if (display.target.value == null && it.rule.severity != AlertSeverity.Info) AlertSound.play()
    }
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
      gpsCourse = repository.gpsCourse,
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
      favourites = FavouritesStore(settings),
      display = display,
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
