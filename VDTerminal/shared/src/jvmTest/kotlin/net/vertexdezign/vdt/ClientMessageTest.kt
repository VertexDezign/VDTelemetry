package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/**
 * The [ClientMessage.SetCruiseControl] finiteness invariant. Enforced in the type's `init` block so a
 * non-finite speed can neither be constructed nor decoded off the wire — the server writer relies on
 * that instead of screening messages itself.
 */
class ClientMessageTest {
  @Test
  fun `a finite cruise speed is accepted`() {
    assertEquals(15.5f, ClientMessage.SetCruiseControl(CruiseAction.SET_SPEED, speed = 15.5f).speed)
  }

  @Test
  fun `a null speed is accepted (the non-setSpeed actions carry none)`() {
    assertEquals(null, ClientMessage.SetCruiseControl(CruiseAction.ENABLE).speed)
  }

  @Test
  fun `constructing a non-finite cruise speed is rejected`() {
    for (speed in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)) {
      assertFailsWith<IllegalArgumentException> {
        ClientMessage.SetCruiseControl(CruiseAction.SET_SPEED, speed = speed)
      }
    }
  }

  @Test
  fun `decoding an overflowing speed fails instead of yielding infinity`() {
    // `1e400` is valid JSON but overflows Float to +Infinity. The decode has to run the init-block
    // require (a hostile client can't smuggle a non-finite speed through), so this must throw rather
    // than silently produce a message with speed == Infinity.
    assertFails {
      Json.decodeFromString(
        ClientMessage.serializer(),
        """{"type":"setCruiseControl","action":"setSpeed","speed":1e400}""",
      )
    }
  }
}
