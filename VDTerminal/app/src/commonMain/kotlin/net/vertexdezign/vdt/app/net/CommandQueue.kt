package net.vertexdezign.vdt.app.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import net.vertexdezign.vdt.ClientMessage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Outbound app -> server commands, waiting for a session to carry them.
 *
 * Buffered with a drop-oldest overflow so a UI tap never suspends and a burst can't grow unbounded.
 * What it deliberately does NOT do is hold a command across an outage: one that has waited longer
 * than [maxAge] is dropped when a session gets to it rather than sent.
 *
 * Replaying them was only safe while every command set an absolute state, and that stopped being true
 * long ago -- `TakeLoan`, `RepayLoan`, `CreateInvoice`, `CreateTask`, `CreateRotation`,
 * `AddRotationSlot` and `UnloadObjectStorage` each do something *again* every time they arrive, so a
 * driver tapping "take loan" twice at a dead connection got two loans the moment it came back. And
 * even an absolute command is wrong late: a light switched on half a minute after the tap, by a
 * dashboard that showed the tap doing nothing, is a surprise rather than a delivery. The connection
 * scrim keeps taps from landing while the app *knows* it is offline; this covers the stretch where the
 * socket has died and nobody has noticed yet.
 *
 * The ground-layer subscription is not lost by this: it is session state, which [TelemetryRepository]
 * remembers and restates at the top of every session on its own.
 */
internal class CommandQueue(
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val maxAge: Duration = MAX_AGE,
  private val onDropped: (ClientMessage) -> Unit = {},
) {
  private class Queued(val message: ClientMessage, val queuedAt: TimeMark)

  private val channel = Channel<Queued>(capacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** Enqueue a command (non-blocking; safe to call from the UI). */
  fun offer(message: ClientMessage) {
    channel.trySend(Queued(message, timeSource.markNow()))
  }

  /** The next command still fresh enough to send, suspending until there is one. */
  suspend fun receive(): ClientMessage {
    while (true) {
      fresh(channel.receive())?.let { return it }
    }
  }

  /** The next command still fresh enough to send, or null when none is waiting. */
  fun poll(): ClientMessage? {
    while (true) {
      val queued = channel.tryReceive().getOrNull() ?: return null
      fresh(queued)?.let { return it }
    }
  }

  private fun fresh(queued: Queued): ClientMessage? {
    if (queued.queuedAt.elapsedNow() <= maxAge) return queued.message
    onDropped(queued.message)
    return null
  }

  companion object {
    /** A normal delivery takes milliseconds; a tap still waiting after this has visibly done nothing. */
    val MAX_AGE: Duration = 3.seconds
    const val CAPACITY = 64
  }
}
