package net.vertexdezign.vdt.app.net

import net.vertexdezign.vdt.ServerMessage
import net.vertexdezign.vdt.VdtData
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

enum class ConnectionState { Connecting, Connected, Disconnected }

/**
 * Connects to the server WebSocket, decodes [ServerMessage]s, and exposes the latest telemetry
 * plus the connection state as [StateFlow]s. Reconnects with a fixed 2 s backoff (same behavior as
 * the old `socket.ts`).
 */
class TelemetryRepository(
    private val scope: CoroutineScope,
    private val wsUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient { install(ClientWebSockets) }

    private val _telemetry = MutableStateFlow<VdtData?>(null)
    val telemetry: StateFlow<VdtData?> = _telemetry.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.Connecting)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    _connection.value = ConnectionState.Connecting
                    client.webSocket(wsUrl) {
                        _connection.value = ConnectionState.Connected
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                when (val msg = json.decodeFromString(ServerMessage.serializer(), frame.readText())) {
                                    is ServerMessage.Telemetry -> _telemetry.value = msg.data
                                    is ServerMessage.Error -> { /* surfaced later; ignore for now */ }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // fall through to reconnect
                }
                _connection.value = ConnectionState.Disconnected
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2000L
    }
}
