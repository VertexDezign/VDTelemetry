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
 * not writes and are not recorded. The debounce that precedes each reparse adds a roughly constant
 * offset to every timestamp, so it cancels out of the intervals (only the absolute [ChannelStat.lastWriteEpochMs]
 * carries it, which is immaterial for a staleness readout).
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

  /** Record a write observed at [nowMs] (server epoch ms). The first call is just a baseline. */
  @Synchronized
  fun recordWrite(nowMs: Long) {
    val prev = lastWriteMs
    // Guard nowMs < prev: a wall-clock step backwards (NTP) would otherwise yield a negative interval.
    if (prev != null && nowMs >= prev) {
      val delta = nowMs - prev
      lastIntervalMs = delta
      minIntervalMs = min(minIntervalMs ?: delta, delta)
      maxIntervalMs = max(maxIntervalMs ?: delta, delta)
      emaMs = emaMs?.let { EMA_ALPHA * delta + (1 - EMA_ALPHA) * it } ?: delta.toDouble()
    }
    lastWriteMs = nowMs
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
