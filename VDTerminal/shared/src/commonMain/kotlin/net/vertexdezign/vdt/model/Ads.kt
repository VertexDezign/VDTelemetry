package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * What the optional **FS25_AdvancedDamageSystem** mod ("ADS") says about the machine you are in —
 * `vehicle.ads`, from mod version 13 on. Null when ADS isn't installed, when the object is an
 * implement (ADS attaches to motorized vehicles only) or when it is one of the machines ADS excludes.
 *
 * ADS replaces the vanilla damage model outright, which is why one thing here is a *correction*
 * rather than an addition and lives elsewhere: [Motor.temperatur] is ADS's engine temperature when
 * ADS is installed. Two things follow from the same replacement and are worth knowing when reading
 * a capture: [Wearable.damage] is pinned to 0 on a vehicle under ADS (an implement's is still real),
 * and the vanilla temperature it overwrites was never synced in multiplayer at all.
 *
 * **Nothing here is a value ADS hides.** Condition, per-system condition and stress and the live
 * service level are known to a player only through a workshop inspection, and as exact percentages
 * only after a full defectoscopy — so [inspected] carries what an inspection actually reported and
 * the pre-shift [checks] come in ADS's own coarse bands. The dashboard never knows more than the
 * driver does. See the mod's `src/integrations/AdvancedDamageSystem.lua`.
 */
@Serializable
data class Ads(
  val lamps: AdsLamps? = null,
  val service: AdsService? = null,
  val inspected: AdsInspected? = null,
  val checks: AdsChecks? = null,
  val electrical: AdsElectrical? = null,
  /**
   * A CVT's own oil temperature, which ADS models separately and which can cook while the engine
   * still reads fine. Null on any machine without one — never a very cold reading.
   */
  val transmissionTemperatur: Temperatur? = null,
)

/**
 * The warning lamps ADS drives on its own dashboard, each as a severity rather than a boolean.
 *
 * **A lamp this machine does not have is null**, which the band renders as absent rather than unlit
 * — ADS gates each lamp on the vehicle's production year, so a 1960s tractor has a battery and a
 * coolant lamp and genuinely nothing else. That is the same rule the drivetrain lamps already
 * follow, and the reason these are nullable rather than defaulted.
 *
 * ADS defines two more, `transmission` and `oil`, which are deliberately not here: the first is
 * declared and never used by any breakdown in the mod itself, and the second it computes but refuses
 * to draw — so drawing it would tell the player something ADS chose to withhold.
 */
@Serializable
data class AdsLamps(
  val engine: AdsLamp? = null,
  val warning: AdsLamp? = null,
  val brakes: AdsLamp? = null,
  val battery: AdsLamp? = null,
  val coolant: AdsLamp? = null,
  val service: AdsLamp? = null,
)

/**
 * How hard one lamp is saying it — ADS's four indicator colours, by name.
 *
 * [COLD] is the coolant lamp's alone and is not a fault: it is the blue lamp a machine shows until
 * it has warmed up, and under ADS working a cold engine is what wears it. The other three are a
 * severity ladder, and the band gives them a second channel besides colour (see `Telltales.kt`),
 * because severity carried by hue alone is severity some people cannot read.
 */
@Serializable
enum class AdsLamp { OFF, COLD, WARN, CRIT }

/**
 * Where the machine is in its service interval, both in operating hours. Player-visible in game —
 * the shop, the vehicle info panel and ADS's own fleet menu all print them — unlike the service
 * *level* those hours stand in for.
 *
 * [interval] is what the manufacturer recommends for this machine specifically (ADS derives it from
 * the vehicle's reliability and the last maintenance it had), so it is not a constant to hard-code.
 */
@Serializable
data class AdsService(
  val hours: Float = 0f,
  val interval: Float = 0f,
) {
  /** How far through the interval the machine is; over 1 is overdue, which is what lights the lamp. */
  val fraction: Float get() = if (interval > 0f) hours / interval else 0f
}

/**
 * What the last workshop inspection found, as percentages — the only form in which a player knows
 * these numbers at all. Null until the machine has been inspected once.
 *
 * [complete] is ADS's own flag for a full defectoscopy as against a routine check: a complete report
 * is exact, an ordinary one is the mod's approximation, and a readout that did not distinguish them
 * would be quoting a guess as a measurement.
 */
@Serializable
data class AdsInspected(
  val condition: Int? = null,
  val service: Int? = null,
  val complete: Boolean = false,
)

/**
 * The pre-shift chores, in the bands ADS's own field inspection reports them in. A chore this
 * machine does not need is null — a trailer has nothing to grease.
 */
@Serializable
data class AdsChecks(
  val radiator: AdsCheck? = null,
  val airIntake: AdsCheck? = null,
  val lubrication: AdsCheck? = null,
)

/**
 * How bad one chore is, in ADS's own words for it.
 *
 * Two ladders share one enum, because a radiator gets worse as it fills up and grease as it runs
 * out, and ADS names the rungs after what it saw rather than after how far along they are. What the
 * driver acts on is [level] — the same four rungs either way, so `DIRTY` and `DRY` are equally
 * urgent and sort together. Ordinal is deliberately not that ladder: read [level].
 */
@Serializable
enum class AdsCheck(
  val level: Int,
) {
  OK(0),

  /** Barely started, either way: a dusty grille, grease still mostly there. */
  SLIGHT(1),

  DIRTY(2),
  DRY(2),

  HEAVY(3),
  VERY_DRY(3),

  /** Do this before you set off. */
  CRITICAL(4),
}

/**
 * The electrical system. [systemVoltage] is what the machine's electrics see rather than the
 * battery's own terminal voltage — the figure ADS puts on its dashboard, and the one that sags when
 * the alternator cannot keep up with the load.
 */
@Serializable
data class AdsElectrical(
  val systemVoltage: Float = 0f,
  val unit: String = "",
)
