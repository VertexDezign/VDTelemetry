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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

  val store =
    VdtStore(
      telemetry = repository.telemetry,
      connection = repository.connection,
      sampleIntervalMs = repository.sampleIntervalMs,
      taskList = repository.taskList,
      cropRotation = repository.cropRotation,
      mapData = repository.mapData,
      mapVehicles = repository.mapVehicles,
      wakeLock = wakeLock.asStateFlow(),
      mapUrl = mapUrl,
      settings = settings,
      pages = PageStore(settings),
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
