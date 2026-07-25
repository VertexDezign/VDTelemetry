package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Coarse cover state. A vehicle may carry several covers, so this only says whether *any* is open —
 * [Cover.index] says which.
 *
 * `UNKNOWN` is no longer emitted: the mod used to report it for every cover past the first, which was
 * a bug rather than a real state. It is kept so telemetry from an older mod still decodes.
 */
@Serializable
enum class CoverType { CLOSED, OPEN, UNKNOWN }
