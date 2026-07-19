package net.vertexdezign.vdt.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.vertexdezign.vdt.ChannelStatsData
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
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

    // Observed write cadence for the diagnostics channel (see snapshotCadence). Only successful
    // content parses feed it; an absent/torn-read reparse is not a write.
    val cadence = CadenceTracker(fileName)

    // mtime of the last write we successfully parsed. A single logical write surfaces as several
    // filesystem events (truncate + write + close), and the debounce doesn't fully coalesce them —
    // so without this guard we'd parse, and count in the cadence, the same write two or three times,
    // reporting a cadence near the debounce interval rather than the mod's real write rate. Committed
    // only on a successful parse, so a torn read (mid-write) is still retried on the next event.
    private var lastGoodMtimeMs: Long? = null

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
        val mtimeMs = path.getLastModifiedTime().toMillis()
        if (mtimeMs == lastGoodMtimeMs) return // duplicate event for a write we already processed
        flow.value = parse(path.readText())
        lastGoodMtimeMs = mtimeMs
        // Record the file mtime, not now(): it's the true write instant, free of the debounce offset.
        cadence.recordWrite(mtimeMs)
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

  /**
   * Snapshot the observed write cadence of every registered file, in registration order, tagged with
   * [nowMs] (server epoch ms) so the app can compute per-channel staleness against one consistent
   * clock. Broadcast to clients as [net.vertexdezign.vdt.ServerMessage.ChannelStats].
   */
  fun snapshotCadence(nowMs: Long = System.currentTimeMillis()): ChannelStatsData =
    ChannelStatsData(serverNowEpochMs = nowMs, channels = channels.map { it.cadence.snapshot() })

  /**
   * Watch until cancelled, restarting the [java.nio.file.WatchService] if it fails.
   *
   * The watch has to survive the directory going away and coming back: the mod recreates `telemetry/`
   * on every map load, and on Linux we tell users to mount tmpfs over it (see the mod's Readme), so an
   * unmount/remount — or a game restart — invalidates the watch key underneath us. Letting the
   * exception escape would kill this coroutine for good and freeze *every* channel, telemetry
   * included, until the server was restarted; nothing else would report it. So a failure logs, waits,
   * and re-establishes the watch instead.
   */
  fun launchIn(scope: CoroutineScope): Job =
    scope.launch(Dispatchers.IO) {
      channels.forEach { it.reparse() } // initial read of whatever is already present

      while (isActive) {
        try {
          watchOnce()
        } catch (e: CancellationException) {
          throw e // cooperative cancellation, not a failure
        } catch (e: Exception) {
          log.warn("Watch on {} failed; retrying in {} ms", dir, RETRY_MS, e)
        }
        if (isActive) delay(RETRY_MS)
      }
    }

  /** One watch session: register, then dispatch events until the service or the directory fails. */
  private suspend fun watchOnce() {
    if (!dir.exists()) {
      // Not an error: the mod creates the folder on map load, so before the first session there is
      // simply nothing to watch yet. Retry rather than give up (this used to end the coroutine).
      log.warn("Watch directory does not exist: {} — serving last good state only", dir)
      return
    }

    FileSystems.getDefault().newWatchService().use { ws ->
      dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
      log.info("Watching {} for {}", dir, channels.map { it.fileName })

      while (currentCoroutineContext().isActive) {
        // Poll (rather than take()) so the coroutine stays cancellable.
        val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
        val changed =
          key.pollEvents().mapNotNullTo(mutableSetOf()) { event ->
            val name = (event.context() as? Path)?.fileName?.toString()
            channels.firstOrNull { it.fileName == name }
          }
        // A key that can't be reset is no longer valid — the directory was deleted or replaced (a
        // tmpfs remount does exactly this). Leave, so the caller re-registers on the new directory.
        val valid = key.reset()
        if (changed.isNotEmpty()) {
          delay(debounceMs) // coalesce the burst of events from one write
          changed.forEach { it.reparse() }
        }
        if (!valid) {
          log.warn("Watch key for {} is no longer valid (directory replaced?); re-registering", dir)
          return
        }
      }
    }
  }

  private companion object {
    const val RETRY_MS = 1000L
  }
}
