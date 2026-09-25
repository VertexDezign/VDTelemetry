package net.vertexdezign.vdt.app.net

import net.vertexdezign.vdt.ClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CommandQueueTest {
  private val clock = TestTimeSource()
  private val dropped = mutableListOf<ClientMessage>()
  private val lost = mutableListOf<ClientMessage>()
  private val queue =
    CommandQueue(clock, maxAge = 3.seconds) { message, reason ->
      when (reason) {
        CommandQueue.DropReason.Expired -> dropped += message
        CommandQueue.DropReason.Lost -> lost += message
      }
    }

  @Test
  fun sendsWhatWasQueuedInOrder() {
    queue.offer(ClientMessage.TakeLoan(10_000, 5))
    queue.offer(ClientMessage.PayInvoice(7))
    assertEquals(ClientMessage.TakeLoan(10_000, 5), queue.poll())
    assertEquals(ClientMessage.PayInvoice(7), queue.poll())
    assertNull(queue.poll())
  }

  // The failure this guards: "take loan" tapped twice at a dead connection became two loans when the
  // connection came back.
  @Test
  fun dropsACommandThatWaitedOutAnOutage() {
    queue.offer(ClientMessage.TakeLoan(10_000, 5))
    queue.offer(ClientMessage.TakeLoan(10_000, 5))
    clock += 30.seconds
    assertNull(queue.poll())
    assertEquals(2, dropped.size)
  }

  @Test
  fun keepsTheFreshOnesBehindAStaleOne() {
    queue.offer(ClientMessage.PayInvoice(1))
    clock += 5.seconds
    queue.offer(ClientMessage.PayInvoice(2))
    assertEquals(ClientMessage.PayInvoice(2), queue.poll())
    assertEquals(listOf<ClientMessage>(ClientMessage.PayInvoice(1)), dropped)
  }

  @Test
  fun theAgeLimitIsInclusive() {
    queue.offer(ClientMessage.PayInvoice(1))
    clock += 3.seconds
    assertEquals(ClientMessage.PayInvoice(1), queue.poll())
    queue.offer(ClientMessage.PayInvoice(2))
    clock += 3.seconds + 1.milliseconds
    assertNull(queue.poll())
  }

  @Test
  fun aBurstPastCapacityKeepsTheNewest() {
    repeat(CommandQueue.CAPACITY + 1) { queue.offer(ClientMessage.PayInvoice(it)) }
    assertEquals(ClientMessage.PayInvoice(1), queue.poll())
    assertEquals(listOf<ClientMessage>(ClientMessage.PayInvoice(0)), lost)
    assertEquals(emptyList(), dropped)
  }
}
