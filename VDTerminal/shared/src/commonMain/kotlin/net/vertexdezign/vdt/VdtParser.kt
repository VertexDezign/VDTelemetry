package net.vertexdezign.vdt

import nl.adaptivity.xmlutil.serialization.XML

/**
 * Parses `vdTelemetry.xml` content into the typed [VdtData] model.
 *
 * Configured to ignore unknown children so the client survives the mod adding elements/attributes
 * ahead of the client catching up (the schema already lags what the mod emits).
 */
object VdtParser {
    private val xml = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
    }

    fun parse(text: String): VdtData = xml.decodeFromString(VdtData.serializer(), text)
}
