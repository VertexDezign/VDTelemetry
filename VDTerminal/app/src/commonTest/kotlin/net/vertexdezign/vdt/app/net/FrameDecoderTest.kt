package net.vertexdezign.vdt.app.net

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FrameDecoderTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val logged = mutableListOf<String>()
  private val decoder = FrameDecoder(json) { logged += it }

  private fun frame(message: ServerMessage) = json.encodeToString(ServerMessage.serializer(), message)

  @Test
  fun decodesAServerMessage() {
    assertIs<ServerMessage.Error>(decoder.decode(frame(ServerMessage.Error("x"))))
    assertEquals(emptyList(), logged)
  }

  // The failure this guards: one frame that did not decode ended the session, the reconnect was sent
  // the same frame again, and the dashboard sat on "CONNECTING" for good.
  @Test
  fun skipsAFrameThatDoesNotDecodeAndCarriesOn() {
    assertNull(decoder.decode("""{"type":"no-such-message"}"""))
    assertNull(decoder.decode("not json"))
    assertIs<ServerMessage.Error>(decoder.decode(frame(ServerMessage.Error("x"))))
    assertEquals(2, logged.size)
  }

  @Test
  fun capsTheLogPerSessionAndStartsOverOnTheNext() {
    repeat(20) { decoder.decode("not json") }
    // five failures, then one line saying the rest are not logged
    assertEquals(6, logged.size)
    decoder.newSession()
    decoder.decode("not json")
    assertEquals(7, logged.size)
  }
}
