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
 * The ground-layer subscription is exempt from the age limit. It states the whole set of layers on
 * screen, so it is never wrong late, only wrong missing: dropped on a connection that was slow but never
 * dropped, it would leave the server sweeping the old layers with no reconnect coming to restate it.
 * [TelemetryRepository] restates it at the top of every session and skips a queued one that has since
 * been replaced, so the exemption can only ever deliver the newest. For the same reason it survives
 * overflow: a burst that pushes the newest one out puts it straight back at the tail, displacing the
 * next-oldest command instead.
 *
 * The age is checked once, as a command leaves this queue, and never again. Past that point the wasm
 * client cannot hold a command back: Ktor's outgoing channel is unbounded, so `send` does not suspend,
 * and its pump hands each frame straight to the browser's `WebSocket.send`, which buffers without
 * limit and cannot take a frame back. A frame stuck there on a dead socket dies with that socket --
 * a new session never replays it -- so all that remains is a connection that stalls and then recovers
 * as the *same* session, delivering late what it was given in time. Guarding that would take the
 * server judging a command's age, against a client clock it does not share; accepted instead.
 */
internal class CommandQueue(
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val maxAge: Duration = MAX_AGE,
  private val onDropped: (ClientMessage, DropReason) -> Unit = { _, _ -> },
) {
  enum class DropReason {
    /** Waited longer than [maxAge]. */
    Expired,

    /** Pushed out by a burst past [CAPACITY], or taken by a session that ended before sending it. */
    Lost,
  }

  private class Queued(val message: ClientMessage, val queuedAt: TimeMark)

  // The newest layer subscription offered, and one an offer just pushed out of the buffer while it
  // still was the newest. Requeued after the trySend returns rather than from inside the callback,
  // which runs in the middle of the channel's own send.
  private var latestLayers: ClientMessage.SetMapLayers? = null
  private var displacedLayers: Queued? = null
  private var offering = false

  // DROP_OLDEST hands the element it pushes out to onUndeliveredElement, as does a receive cancelled
  // after taking one, so a lost command is reported rather than vanishing. A subscription taken by a
  // session that then ended is not requeued: TelemetryRepository restates it on the next one.
  private val channel =
    Channel<Queued>(
      capacity = CAPACITY,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
      onUndeliveredElement = {
        if (offering && it.message === latestLayers) {
          displacedLayers = it
        } else {
          onDropped(it.message, DropReason.Lost)
        }
      },
    )

  /** Enqueue a command (non-blocking; safe to call from the UI). */
  fun offer(message: ClientMessage) {
    if (message is ClientMessage.SetMapLayers) latestLayers = message
    offering = true
    try {
      var next: Queued? = Queued(message, timeSource.markNow())
      while (next != null) {
        channel.trySend(next)
        next = displacedLayers
        displacedLayers = null
      }
    } finally {
      offering = false
    }
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
    if (queued.message is ClientMessage.SetMapLayers || queued.queuedAt.elapsedNow() <= maxAge) return queued.message
    onDropped(queued.message, DropReason.Expired)
    return null
  }

  companion object {
    /** A normal delivery takes milliseconds; a tap still waiting after this has visibly done nothing. */
    val MAX_AGE: Duration = 3.seconds
    const val CAPACITY = 64
  }
}
