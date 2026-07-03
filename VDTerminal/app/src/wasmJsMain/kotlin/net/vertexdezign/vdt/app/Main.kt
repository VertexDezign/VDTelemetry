package net.vertexdezign.vdt.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import net.vertexdezign.vdt.app.net.TelemetryRepository
import com.russhwolf.settings.StorageSettings
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope

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

    val settings = StorageSettings()

    ComposeViewport(document.body!!) {
        val telemetry by repository.telemetry.collectAsState()
        val connection by repository.connection.collectAsState()
        App(telemetry, connection, mapUrl, settings, onMenuClick = { WakeLock.toggle() })
    }
}
