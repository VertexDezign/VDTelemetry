package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

@Serializable
data class Environment(
  val date: String? = null,
  val time: String? = null,
  val weather: Weather? = null,
  val pda: Pda? = null,
)

@Serializable
data class Weather(
  val temperature: Temperature? = null,
)

@Serializable
data class Temperature(
  val min: Int = 0,
  val max: Int = 0,
  val current: Int = 0,
  val unit: String = "",
)

/**
 * PDA / map data. `filename`/`width`/`height` are absent when the mod has no PDA reference, so they
 * are optional.
 */
@Serializable
data class Pda(
  val filename: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  val player: Player? = null,
)

@Serializable
data class Player(
  val posX: Float = 0f,
  val posZ: Float = 0f,
)
