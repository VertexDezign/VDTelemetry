package net.vertexdezign.vdt.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import kotlinx.coroutines.delay
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
        val sampleIntervalMs by repository.sampleIntervalMs.collectAsState()

        val supported = remember { WakeLock.supported }
        // Reflect whether the lock is *actually* held: the request resolves asynchronously and can
        // fail (e.g. Firefox iOS), and the browser drops the lock when the tab hides. Poll the flag.
        var active by remember { mutableStateOf(false) }
        LaunchedEffect(supported) {
            while (supported) {
                active = WakeLock.active
                delay(500)
            }
        }
        val wakeStatus = when {
            !supported -> WakeLockStatus.Unsupported
            active -> WakeLockStatus.On
            else -> WakeLockStatus.Off
        }

        App(
            telemetry,
            connection,
            mapUrl,
            settings,
            sampleIntervalMs = sampleIntervalMs,
            wakeLock = wakeStatus,
            onToggleWakeLock = {
                WakeLock.toggle()
                active = WakeLock.active
            },
        )
    }
}
