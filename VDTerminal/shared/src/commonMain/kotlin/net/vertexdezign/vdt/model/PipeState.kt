package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

@Serializable
enum class PipeState { RETRACTED, EXTENDED, MOVING }
