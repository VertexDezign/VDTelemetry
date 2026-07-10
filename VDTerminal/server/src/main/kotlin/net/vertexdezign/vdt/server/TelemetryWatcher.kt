package net.vertexdezign.vdt.server

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
 * Watches a single directory and dispatches file events to per-file channels by name.
 *
 * The mod writes several files into the same `telemetry/` folder — `vdTelemetry.json` (~100 ms) plus
 * the optional event-driven channels (`taskList.json`) — each parsed into its own [StateFlow] that is
 * the broadcast hub for that data (every WebSocket session collects it). One [java.nio.file.WatchService]
 * over the shared directory dispatches by filename rather than one watcher per file.
 *
 * Register the files with [register] before [launchIn]. Parse/read failures are logged and the last
 * good value is kept, so a torn read (the mod mid-write) simply fails to parse and the next write
 * recovers.
 *
 * Ports + generalizes the old single-file `TelemetrySource` (`watcher.go` + `parser.go`).
 */
class TelemetryWatcher(
  private val dir: Path,
  private val debounceMs: Long = 40L,
) {
  private val log = LoggerFactory.getLogger(TelemetryWatcher::class.java)

  private val channels = mutableListOf<WatchedFile<*>>()

  /**
   * One watched file: its name in [dir], how to parse it, and the [flow] it publishes into.
   *
   * [nullOnAbsent] decides what a missing file means. Telemetry keeps its last good value (a delete
   * is usually the mod's atomic replace, or export being toggled off mid-session), so it is `false`.
   * The optional mod channels use `true`: the file's *absence is the "mod not installed" signal*, so
   * a delete must reset the flow to null and the app renders the not-installed state.
   */
  private inner class WatchedFile<T>(
    val fileName: String,
    val nullOnAbsent: Boolean,
    val parse: (String) -> T,
  ) {
    val flow = MutableStateFlow<T?>(null)

    fun reparse() {
      val path = dir.resolve(fileName)
      try {
        if (!path.exists()) {
          if (nullOnAbsent) {
            flow.value = null
          } else {
            log.warn("File not present (yet): {}", path)
          }
          return
        }
        flow.value = parse(path.readText())
        log.debug("Parsed {}", path)
      } catch (e: Exception) {
        log.error("Failed to parse {}; keeping last good state", path, e)
      }
    }
  }

  /**
   * Register a file to watch; returns the [StateFlow] carrying its latest parsed value (null until the
   * first successful parse, and — for [nullOnAbsent] files — again whenever the file is absent).
   */
  fun <T> register(
    fileName: String,
    nullOnAbsent: Boolean,
    parse: (String) -> T,
  ): StateFlow<T?> {
    val channel = WatchedFile(fileName, nullOnAbsent, parse)
    channels.add(channel)
    return channel.flow.asStateFlow()
  }

  fun launchIn(scope: CoroutineScope): Job =
    scope.launch(Dispatchers.IO) {
      channels.forEach { it.reparse() } // initial read of whatever is already present

      if (!dir.exists()) {
        log.warn("Watch directory does not exist: {} — serving last good state only", dir)
        return@launch
      }

      val watcher = FileSystems.getDefault().newWatchService()
      dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
      log.info("Watching {} for {}", dir, channels.map { it.fileName })

      watcher.use { ws ->
        while (isActive) {
          // Poll (rather than take()) so the coroutine stays cancellable.
          val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
          val changed =
            key.pollEvents().mapNotNullTo(mutableSetOf()) { event ->
              val name = (event.context() as? Path)?.fileName?.toString()
              channels.firstOrNull { it.fileName == name }
            }
          key.reset()
          if (changed.isNotEmpty()) {
            delay(debounceMs) // coalesce the burst of events from one write
            changed.forEach { it.reparse() }
          }
        }
      }
    }
}
