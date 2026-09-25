package net.vertexdezign.vdt.app.net

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.ServerMessage

/**
 * Decodes one WebSocket text frame into a [ServerMessage], or null when it doesn't decode.
 *
 * A failure is logged and skipped: one bad frame costs that one update, never the session. Logging is
 * capped per session, because a failure that persists -- a channel whose shape the app can't read --
 * repeats at that channel's cadence, up to ten times a second for telemetry, and the browser console
 * is where someone goes to find the first one, not the thousandth.
 */
internal class FrameDecoder(private val json: Json, private val log: (String) -> Unit = ::println) {
  private var failures = 0

  /** A new session starts the log budget over, so a failure that survives a reconnect is seen again. */
  fun newSession() {
    failures = 0
  }

  fun decode(text: String): ServerMessage? = try {
    json.decodeFromString(ServerMessage.serializer(), text)
  } catch (e: Exception) {
    failures++
    when {
      failures <= LOGGED_PER_SESSION -> log("VDT: skipped a server message that did not decode: $e")
      failures == LOGGED_PER_SESSION + 1 -> log("VDT: further undecodable server messages this session are not logged")
    }
    null
  }

  private companion object {
    const val LOGGED_PER_SESSION = 5
  }
}
