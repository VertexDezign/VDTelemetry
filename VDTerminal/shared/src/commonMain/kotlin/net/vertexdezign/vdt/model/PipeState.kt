package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Coarse pipe state. A pipe can have more than two positions, so [EXTENDED] means "not fully
 * retracted" rather than "fully out" — [Pipe.current] and [Pipe.numStates] give the exact position.
 */
@Serializable
enum class PipeState { RETRACTED, EXTENDED, MOVING }
