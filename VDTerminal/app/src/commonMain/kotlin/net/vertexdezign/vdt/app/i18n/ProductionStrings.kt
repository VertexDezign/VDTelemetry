package net.vertexdezign.vdt.app.i18n

/** The Production app: production points, their lines, and the plants they belong to. */
interface ProductionStrings {
  val waiting: String
  val empty: String
  val listTitle: String
  val noLines: String
  fun lines(count: Int): String
  val factory: String
  val inputs: String
  val outputs: String

  /** A line's monthly figures, both already formatted. */
  fun perMonth(cycles: String, costs: String): String
  val on: String
  val off: String
  val changeMode: String

  val statusRunning: String
  val statusMissingInput: String
  val statusNoSpace: String
  val statusInactive: String

  val modeDirectSell: String
  val modeDistribute: String
  val modeWithinPlant: String
  val modeKeep: String

  /** A plant's part roles, plural: one row stands for every machine of that role. */
  val roleBunkers: String
  val roleFermenters: String
  val roleCogeneration: String
  val roleDigestate: String
  val roleTorch: String

  val plant: String
  val utilization: String
  val noReading: String

  /** A utilization reading's band, in a word; the torch has its own two. */
  val torchBurning: String
  val torchIdle: String
  val atLimit: String
  val optimal: String
  val ok: String
  val poor: String
  val stopped: String
  // ---- The plant page: a Pumps & Hoses biogas plant drawn as one process ------------------------

  /** The list row of a plant and the page it opens. */
  val plantOverview: String
  val roleSlurry: String
  val roleSeparator: String

  /** A feed rate, a production rate: the amount already formatted, in L/h. */
  fun perHour(amount: String): String
  val feed: String
  val produced: String
  val efficiency: String
  val temperature: String

  /** A temperature, already formatted, in degrees Celsius. */
  fun degrees(value: String): String
  val setpoint: String
  val agitator: String
  val dryMatter: String
  val ph: String
  val fill: String
  val biology: String
  val recirculation: String
  val load: String
  val income: String
  val throughput: String
  val methane: String

  /** A stepper's two buttons, read out for a screen reader: what they change. */
  fun decrease(what: String): String
  fun increase(what: String): String

  /** The running cost per hour and the upkeep per day, both already formatted. */
  fun plantCosts(operating: String, upkeep: String): String

  /** How long the bacteria hold out before dying, already formatted; and when they no longer do. */
  fun biologyBuffer(remaining: String): String
  val biologyDying: String

  /** A duration in in-game hours and minutes. */
  fun hoursMinutes(hours: Int, minutes: Int): String

  /** The process mode BGA Control works out, by token. */
  val bgaModeInactive: String
  val bgaModeLiquidMono: String
  val bgaModeSolidMono: String
  val bgaModeSolidMulti: String
  val bgaModeRecirculation: String
  val bgaModeMultiSubstrate: String
  val bgaModeCoFermentation: String
  val bgaModeManureLiquid: String
  val bgaModeBeetFermentation: String

  /** BGA Control's status line, by token. */
  val bgaStatusStopped: String
  fun bgaStatusStartup(activity: Int): String
  val bgaStatusCold: String
  val bgaStatusEconomicalStable: String
  val bgaStatusRecircStable: String
  val bgaStatusDryOk: String
  val bgaStatusThermophilic: String
  val bgaStatusMesophilicOptimal: String
  val bgaStatusBuffering: String
  val bgaStatusNoFeed: String
  val bgaStatusScumDry: String
  val bgaStatusHighViscosity: String
  val bgaStatusHighShear: String
  fun bgaStatusAcidCrash(ph: String): String
  val bgaStatusAmmoniaInhibition: String

  /** Where the bacterial culture is heading, by token. */
  val bgaTrendDying: String
  val bgaTrendStressed: String
  val bgaTrendDecay: String
  val bgaTrendDormant: String
  val bgaTrendStable: String
  val bgaTrendGrowing: String

  /** The separator's state, by token. */
  val separatorInactive: String
  val separatorOperating: String
  val separatorFull: String
  val separatorNoSilo: String
  val separatorStandby: String
}
