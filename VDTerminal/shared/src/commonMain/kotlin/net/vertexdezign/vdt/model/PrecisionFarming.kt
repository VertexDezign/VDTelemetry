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
  /**
   * Present only while fertilizing. PF stops refreshing nitrogen the moment the tank holds anything
   * else and never resets it, so outside that mode the mod withholds it rather than passing on a
   * reading that is minutes or fields out of date.
   */
  val nitrogen: PfValue? = null,
  /** Present only while liming, for the same reason [nitrogen] is present only while fertilizing. */
  val ph: PfValue? = null,
  /**
   * Whether PF's spot-spray configuration is fitted; null when the machine has no such config at all.
   *
   * It is what makes [PfNozzles.saved] mean anything: with spot spraying, a boom running at 40% is
   * covering the full width and skipping the clean ground. Without it, 40% just means most of the boom
   * is folded away — same liquid per hectare, less hectares.
   */
  val spotSpray: Boolean? = null,
  val workAreas: List<PfWorkArea> = emptyList(),
  val nozzles: PfNozzles? = null,
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

/**
 * The boom's nozzles, left to right — what is *actually coming out* right now.
 *
 * A different question from the shutoff sections, and a better-answered one. Each state already folds
 * in the section being off, reversing or crawling, spot spraying finding no weed under that nozzle,
 * and liquid fertilizer skipping ground that already has some. It is also the only per-position signal
 * here that survives multiplayer: PF recomputes these on every client, because they drive what the
 * player sees leaving the boom.
 *
 * Present only on the sprayers PF ships nozzle data for — which are exactly the machines where it
 * removes the base game's work-width controls, so where [WorkWidth.sections] freezes, this is live.
 *
 * [individual] is false on a machine PF switches a whole section at a time.
 */
@Serializable
data class PfNozzles(
  val count: Int = 0,
  val activeCount: Int = 0,
  val individual: Boolean = false,
  val active: List<Boolean> = emptyList(),
) {
  /** How much of the boom is spraying, `0..1` — PF's own `getNumExtendedSprayerNozzleEffectsActive`. */
  val fraction: Float get() = if (count > 0) activeCount.toFloat() / count else 0f

  /**
   * The share of a full-boom application this pass is *not* using, `0..1`.
   *
   * Not an estimate: PF multiplies the sprayer's usage by exactly [fraction]
   * (`WeedSpotSpray:getSprayerUsage`), so this is the liquid saved against spraying the same ground
   * at full width. Only meaningful with [PrecisionFarming.spotSpray] fitted — otherwise the closed
   * nozzles are folded-away boom, and less liquid over less ground is not a saving.
   */
  val saved: Float get() = 1f - fraction
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
