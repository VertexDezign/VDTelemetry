package net.vertexdezign.vdt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.model.VdtData

/**
 * Messages pushed server -> client over the WebSocket, JSON-encoded.
 *
 * See [ClientMessage] for the app -> mod direction.
 */
@Serializable
sealed interface ServerMessage {
  @Serializable
  @SerialName("telemetry")
  data class Telemetry(
    val data: VdtData,
  ) : ServerMessage

  @Serializable
  @SerialName("error")
  data class Error(
    val message: String,
  ) : ServerMessage
}

/**
 * Messages sent client -> server over the WebSocket (app -> mod back-channel), JSON-encoded.
 *
 * A placeholder until the `commands.xml` back-channel lands; the [ToDo] member is a stand-in so the
 * real messages have a home to slot into.
 */
@Serializable
sealed interface ClientMessage {
  data class ToDo(
    val todo: String,
  ) : ClientMessage
}
