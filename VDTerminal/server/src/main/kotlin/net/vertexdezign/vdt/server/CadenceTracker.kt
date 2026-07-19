package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.ChannelStat
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks the **observed** write cadence of one channel file: how often its content actually changes on
 * disk. This is what the WebSocket consumer receives, so it's the honest measure to verify the mod's
 * configured interval/profile against — independent of what the mod *intends* to write.
 *
 * Fed one [recordWrite] per successful content reparse (see [TelemetryWatcher]); file *deletions* are
 * not writes and are not recorded. The timestamp passed is the file's **mtime** — the true write
 * instant — so intervals reflect the mod's real cadence without the file-watch debounce offset, and a
 * repeated mtime (a duplicate filesystem event for one write) is ignored.
 *
 * Thread-safe: [recordWrite] runs on the watcher's IO coroutine while [snapshot] is read from the
 * stats-broadcast timer on another dispatcher.
 */
internal class CadenceTracker(
  private val name: String,
) {
  private var writes = 0L
  private var lastWriteMs: Long? = null
  private var lastIntervalMs: Long? = null
  private var minIntervalMs: Long? = null
  private var maxIntervalMs: Long? = null
  private var emaMs: Double? = null

  /** Record a write at [writeMs] (the file mtime, epoch ms). The first call is just a baseline. */
  @Synchronized
  fun recordWrite(writeMs: Long) {
    val prev = lastWriteMs
    // The same mtime twice is a duplicate FS event for one write (the watcher also guards this) — not
    // a new write, so don't double-count it.
    if (writeMs == prev) return
    // Guard writeMs < prev: an mtime that steps backwards (file replaced with an older copy / NTP)
    // would otherwise yield a negative interval.
    if (prev != null && writeMs > prev) {
      val delta = writeMs - prev
      lastIntervalMs = delta
      minIntervalMs = min(minIntervalMs ?: delta, delta)
      maxIntervalMs = max(maxIntervalMs ?: delta, delta)
      emaMs = emaMs?.let { EMA_ALPHA * delta + (1 - EMA_ALPHA) * it } ?: delta.toDouble()
    }
    lastWriteMs = writeMs
    writes += 1
  }

  @Synchronized
  fun snapshot(): ChannelStat =
    ChannelStat(
      name = name,
      writes = writes,
      lastWriteEpochMs = lastWriteMs,
      lastIntervalMs = lastIntervalMs,
      meanIntervalMs = emaMs,
      minIntervalMs = minIntervalMs,
      maxIntervalMs = maxIntervalMs,
    )

  private companion object {
    // Same smoothing the app uses for its telemetry-tick estimate; rides out debounce/FS jitter.
    const val EMA_ALPHA = 0.3
  }
}
