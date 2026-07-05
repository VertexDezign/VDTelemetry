package net.vertexdezign.vdt

import kotlinx.serialization.json.Json

/**
 * Parses the mod's `vdTelemetry.json` into the typed [VdtData] model.
 *
 * Configured to tolerate the mod running ahead of the client: unknown keys are ignored and omitted
 * fields fall back to the model's data-class defaults (the mod writes only what's present).
 */
object VdtParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseJson(text: String): VdtData = json.decodeFromString(VdtData.serializer(), text)
}
