package net.vertexdezign.vdt.app.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ServerMessage
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData
import kotlin.math.roundToInt
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

enum class ConnectionState { Connecting, Connected, Disconnected }

/**
 * Connects to the server WebSocket, decodes [ServerMessage]s, and exposes the latest telemetry
 * plus the connection state as [StateFlow]s. Reconnects with a fixed 2 s backoff (same behavior as
 * the old `socket.ts`).
 */
class TelemetryRepository(private val scope: CoroutineScope, private val wsUrl: String) {
  private val json = Json { ignoreUnknownKeys = true }
  private val client = HttpClient { install(ClientWebSockets) }

  // Outbound app -> server commands. Buffered + conflating overflow so a UI click never suspends and
  // a burst while briefly disconnected can't grow unbounded; queued commands flush on (re)connect.
  // Commands are absolute-state (idempotent), so sending a slightly stale one on reconnect is safe.
  private val commandQueue =
    Channel<ClientMessage>(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

  /** Enqueue a command for delivery to the server (non-blocking; safe to call from the UI). */
  fun send(message: ClientMessage) {
    commandQueue.trySend(message)
  }

  private val _telemetry = MutableStateFlow<VdtData?>(null)
  val telemetry: StateFlow<VdtData?> = _telemetry.asStateFlow()

  // Optional FS25_TaskList channel; null while the mod isn't installed. The server sends that null
  // explicitly (message with `data: null`) when the file goes away, so uninstalling the mod clears the
  // panel instead of freezing the last data. Separate flow (not folded into telemetry) because it
  // arrives on its own cadence.
  private val _taskList = MutableStateFlow<TaskListData?>(null)
  val taskList: StateFlow<TaskListData?> = _taskList.asStateFlow()

  // Optional FS25_CropRotation channel; same "null means not installed, and the null is sent" contract
  // as taskList, on its own event-driven cadence.
  private val _cropRotation = MutableStateFlow<CropRotationData?>(null)
  val cropRotation: StateFlow<CropRotationData?> = _cropRotation.asStateFlow()

  private val _connection = MutableStateFlow(ConnectionState.Connecting)
  val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

  // Smoothed wall-clock interval between telemetry samples (ms). The UI uses it as the animation
  // duration so interpolation tracks the mod's *actual* write rate (configurable 100–1000 ms)
  // instead of a hardcoded guess. EMA-smoothed to ride out network/debounce jitter.
  private val _sampleIntervalMs = MutableStateFlow(DEFAULT_SAMPLE_INTERVAL_MS)
  val sampleIntervalMs: StateFlow<Int> = _sampleIntervalMs.asStateFlow()

  private var lastSampleMark: TimeSource.Monotonic.ValueTimeMark? = null

  private fun recordSampleInterval() {
    val mark = TimeSource.Monotonic.markNow()
    val prev = lastSampleMark
    lastSampleMark = mark
    if (prev != null) {
      val dt = (mark - prev).toDouble(DurationUnit.MILLISECONDS)
      // Ignore outliers (first sample after a stall/reconnect, or absurdly fast bursts).
      if (dt in MIN_SAMPLE_INTERVAL_MS.toDouble()..MAX_SAMPLE_INTERVAL_MS.toDouble()) {
        val ema = _sampleIntervalMs.value * (1 - EMA_ALPHA) + dt * EMA_ALPHA
        _sampleIntervalMs.value = ema.roundToInt().coerceIn(MIN_SAMPLE_INTERVAL_MS, MAX_SAMPLE_INTERVAL_MS)
      }
    }
  }

  fun start() {
    scope.launch {
      while (isActive) {
        try {
          _connection.value = ConnectionState.Connecting
          client.webSocket(wsUrl) {
            _connection.value = ConnectionState.Connected
            // Drain queued outbound commands for the life of this session.
            val sendJob =
              launch {
                for (message in commandQueue) {
                  send(Frame.Text(json.encodeToString(ClientMessage.serializer(), message)))
                }
              }
            try {
              for (frame in incoming) {
                if (frame is Frame.Text) {
                  when (val msg = json.decodeFromString(ServerMessage.serializer(), frame.readText())) {
                    is ServerMessage.Telemetry -> {
                      recordSampleInterval()
                      _telemetry.value = msg.data
                    }

                    is ServerMessage.TaskList -> {
                      _taskList.value = msg.data
                    }

                    is ServerMessage.CropRotation -> {
                      _cropRotation.value = msg.data
                    }

                    is ServerMessage.Error -> {
                      /* surfaced later; ignore for now */
                    }
                  }
                }
              }
            } finally {
              sendJob.cancel()
            }
          }
        } catch (_: Throwable) {
          // fall through to reconnect
        }
        _connection.value = ConnectionState.Disconnected
        lastSampleMark = null // don't measure across the reconnect gap
        delay(RECONNECT_DELAY_MS)
      }
    }
  }

  private companion object {
    const val RECONNECT_DELAY_MS = 2000L
    const val DEFAULT_SAMPLE_INTERVAL_MS = 100
    const val MIN_SAMPLE_INTERVAL_MS = 50
    const val MAX_SAMPLE_INTERVAL_MS = 1200 // above the largest mod interval preset (1000)
    const val EMA_ALPHA = 0.3
  }
}
