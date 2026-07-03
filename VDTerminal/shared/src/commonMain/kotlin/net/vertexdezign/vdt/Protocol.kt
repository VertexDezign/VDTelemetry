package net.vertexdezign.vdt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Messages pushed server -> client over the WebSocket, JSON-encoded.
 *
 * A `ClientMessage` mirror can be added when the `commands.xml` back-channel (app -> mod) lands;
 * nothing here precludes it.
 */
@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("telemetry")
    data class Telemetry(val data: VdtData) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ServerMessage
}
