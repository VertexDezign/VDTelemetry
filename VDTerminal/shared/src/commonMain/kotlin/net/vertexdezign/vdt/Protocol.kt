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
 * Messages sent client -> server over the WebSocket (app -> mod back-channel), JSON-encoded. The
 * server turns these into `<command>` entries in `commands.xml`, which the mod polls and executes.
 *
 * Commands carry an **absolute** target state, never a toggle: the file channel is lossy/async, so
 * an idempotent set-to-state is self-correcting where a dropped or doubled toggle would desync. The
 * app already knows the current state (it renders it), so a button tap computes the target itself.
 */
@Serializable
sealed interface ClientMessage {
  /** Set one light on/off. The four beam/work lights are mask bits mod-side; `beacon` is a bool. */
  @Serializable
  @SerialName("setLight")
  data class SetLight(
    val light: LightTarget,
    val on: Boolean,
  ) : ClientMessage

  /** Set the (single) turn-light state — indicators are one enum, not three independent booleans. */
  @Serializable
  @SerialName("setTurnLight")
  data class SetTurnLight(
    val state: TurnLightState,
  ) : ClientMessage
}

/**
 * The settable lights. [token] is the wire vocabulary shared with the mod (the `light=` attribute in
 * `commands.xml`); it is kept explicit so the enum can be renamed without breaking the contract.
 */
@Serializable
enum class LightTarget(
  val token: String,
) {
  BEACON("beacon"),
  LOW_BEAM("lowBeam"),
  HIGH_BEAM("highBeam"),
  WORK_FRONT("workFront"),
  WORK_BACK("workBack"),
}

/** Turn-light state. [token] is the wire vocabulary shared with the mod (the `state=` attribute). */
@Serializable
enum class TurnLightState(
  val token: String,
) {
  OFF("off"),
  LEFT("left"),
  RIGHT("right"),
  HAZARD("hazard"),
}
