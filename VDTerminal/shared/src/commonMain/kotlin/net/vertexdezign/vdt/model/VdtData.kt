package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the VDT telemetry emitted by the mod into `vdTelemetry.json`.
 *
 * A single `@Serializable` type: the mod writes it as JSON, the server decodes it (see
 * [net.vertexdezign.vdt.VdtParser]), and the same instances travel to the web client over the WebSocket.
 *
 * Everything the mod treats as situational is nullable with a default: the mod writes only what's
 * present and evolves faster than the client, so parsing ignores unknown keys and lets omitted
 * fields fall back to these defaults.
 */
@Serializable
data class VdtData(
  val version: String = "",
  val environment: Environment? = null,
  val vehicle: Vehicle? = null,
)
