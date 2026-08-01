package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * What a Precision Farming sprayer or spreader is putting on the ground, hanging off the object that
 * is doing it ([Vehicle.precisionFarming] / [Implement.precisionFarming]).
 *
 * Present only when the internal `FS25_precisionFarming` mod is loaded *and* this object is one of its
 * tools — so absent is the normal case, and means "no rates to show", never "rate zero".
 *
 * **The two halves do not reach equally far.** [nitrogen] and [ph] are the boom averages PF streams to
 * every client, which is what its own HUD draws. [workAreas] is the per-slice detail, and PF only ever
 * computes it on the server — so on a multiplayer client it is simply not there. A consumer reads the
 * averages as the primary value and treats the strip as detail on top of it, never the other way
 * round.
 */
@Serializable
data class PrecisionFarming(
  /** `LIME`, `FERTILIZER`, or `OTHER` for a tool spraying something PF keeps no rates for. */
  val mode: PfMode = PfMode.OTHER,
  /** PF's auto mode: the tool picks the rate from the maps instead of the manual step. */
  val auto: Boolean = false,
  val nitrogen: PfValue? = null,
  val ph: PfValue? = null,
  val workAreas: List<PfWorkArea> = emptyList(),
) {
  /** The value this machine's mode is about — what a rate readout should lead with. */
  val primary: PfValue?
    get() =
      when (mode) {
        PfMode.LIME -> ph
        else -> nitrogen
      }
}

@Serializable
enum class PfMode { LIME, FERTILIZER, OTHER }

/**
 * A reading and what it should be, in the units a player reads: kg N/ha for nitrogen, a pH value for
 * lime. The mod converts them out of PF's internal levels, which are table indices and mean nothing
 * on their own.
 */
@Serializable
data class PfValue(
  val level: Float = 0f,
  val target: Float = 0f,
  val unit: String? = null,
) {
  /** How far below target this reading is; 0 when it is at or above it. */
  val deficit: Float get() = (target - level).coerceAtLeast(0f)
}

/** The sub-sections of one work area, joined to [WorkArea.index] by [index]. */
@Serializable
data class PfWorkArea(
  val index: Int = 0,
  val subSections: List<PfSubSection> = emptyList(),
)

/**
 * One ~2 m slice across the boom, left to right — PF's own sub-division of a work area, and the strip
 * a variable-rate terminal draws.
 *
 * [valid] is PF's own flag: there is a reading here. It goes false off the field, on ground the soil
 * sample has not uncovered, and whenever the tool is doing something that is neither liming nor
 * fertilizing — so an invalid slice is "no data", not "nothing needed".
 */
@Serializable
data class PfSubSection(
  val valid: Boolean = false,
  val n: Float? = null,
  val nTarget: Float? = null,
  val ph: Float? = null,
  val phTarget: Float? = null,
)
