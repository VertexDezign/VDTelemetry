package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.VdtData
import net.vertexdezign.vdt.VdtParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Watches the *directory* containing [path] (the mod replaces the file rather than editing it),
 * debounces bursts of events, parses the JSON telemetry, and publishes the latest good [VdtData]
 * into a [StateFlow].
 *
 * The StateFlow is the broadcast hub: every WebSocket session is a collector. Parse/read failures
 * are logged and the last good state is kept, so the dashboard survives the file being deleted and
 * recreated mid-write (a torn read simply fails to parse and the next sample recovers).
 *
 * Ports `watcher.go` + `parser.go`.
 */
class TelemetrySource(private val path: Path) {
    private val log = LoggerFactory.getLogger(TelemetrySource::class.java)

    private val _state = MutableStateFlow<VdtData?>(null)
    val state: StateFlow<VdtData?> = _state.asStateFlow()

    private fun reparse() {
        try {
            if (!path.exists()) {
                log.warn("Telemetry file not present (yet): {}", path)
                return
            }
            _state.value = VdtParser.parseJson(path.readText())
            log.debug("Parsed telemetry from {}", path)
        } catch (e: Exception) {
            log.error("Failed to parse {}; keeping last good state", path, e)
        }
    }

    fun launchIn(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        val dir = path.parent
        val fileName = path.fileName

        reparse() // initial

        if (dir == null || !dir.exists()) {
            log.warn("Watch directory does not exist: {} — serving last good state only", dir)
            return@launch
        }

        val watcher = FileSystems.getDefault().newWatchService()
        dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        log.info("Watching {} for {}", dir, fileName)

        watcher.use { ws ->
            while (isActive) {
                // Poll (rather than take()) so the coroutine stays cancellable.
                val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
                val relevant = key.pollEvents().any { event ->
                    (event.context() as? Path)?.fileName == fileName
                }
                key.reset()
                if (relevant) {
                    delay(DEBOUNCE_MS) // debounce burst of writes
                    reparse()
                }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }
}
