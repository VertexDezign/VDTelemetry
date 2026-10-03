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
 * service level are known to a player only through a workshop inspection, so no condition of any kind
 * is here. (What an inspection reported was, until mod version 28; ADS 0.9.9.3 rewrote the record it
 * came from, and the integration was cut back to the parts of ADS that survived.) The pre-shift chores (radiator and air-intake clogging, lubrication) are absent for
 * the same reason: a driver learns those by getting out and walking round the machine. The dashboard
 * never knows more than the driver does. See the mod's `src/integrations/AdvancedDamageSystem.lua`.
 */
@Serializable
data class Ads(
  val lamps: AdsLamps? = null,
  val service: AdsService? = null,
  val electrical: AdsElectrical? = null,
  val load: AdsLoad? = null,
  /**
   * The transmission's own oil temperature, which ADS models separately and which can cook while the
   * engine still reads fine. Present exactly where ADS's dashboard prints it — every gearbox but a
   * plain manual, on a machine new enough for the transmission lamp (mod version 29; before that,
   * CVTs only). Null everywhere else — never a very cold reading.
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
 * Two lamps have a gate beyond the year, both ADS's own: a plain manual gearbox has no [transmission]
 * lamp, and an electric machine no [coolant] one. [transmission] and [oil] arrived with mod version
 * 29, when ADS 0.9.9.3 started drawing them. [service] lights only once the service interval is
 * past, as in ADS's own HUD.
 */
@Serializable
data class AdsLamps(
  val engine: AdsLamp? = null,
  val warning: AdsLamp? = null,
  val brakes: AdsLamp? = null,
  val battery: AdsLamp? = null,
  val coolant: AdsLamp? = null,
  val service: AdsLamp? = null,
  val transmission: AdsLamp? = null,
  val oil: AdsLamp? = null,
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
data class AdsService(val hours: Float = 0f, val interval: Float = 0f) {
  /** How far through the interval the machine is; over 1 is overdue, which is what lights the lamp. */
  val fraction: Float get() = if (interval > 0f) hours / interval else 0f
}

/**
 * The load Advanced Damage System wears the engine on.
 *
 * **Not the same quantity as [Motor.load]**, which is the plain engine load and is still exported and
 * still true. This is that load plus what the driveline is doing under it — identical everywhere
 * except on a field with an implement down and working, where ADS adds the draft term its wear model
 * charges for. It is what the mod puts on its own dashboard, so it is the figure that agrees with
 * what the driver sees in the cab.
 *
 * [value] can exceed 100. ADS clips its own readout there; we don't, because how far over is exactly
 * what a driver would change their driving for. [overloadAt] is where ADS starts charging wear, and
 * travels with the value because a player can move it in the mod's settings.
 */
@Serializable
data class AdsLoad(val value: Double = 0.0, val overloadAt: Double = 0.0, val unit: String = "") {
  /** Working the engine harder than ADS is willing to let you without wearing it for it. */
  val overloaded: Boolean get() = overloadAt > 0.0 && value > overloadAt
}

/**
 * The electrical system. [systemVoltage] is what the machine's electrics see rather than the
 * battery's own terminal voltage — the figure ADS puts on its dashboard, and the one that sags when
 * the alternator cannot keep up with the load.
 */
@Serializable
data class AdsElectrical(val systemVoltage: Float = 0f, val unit: String = "")
