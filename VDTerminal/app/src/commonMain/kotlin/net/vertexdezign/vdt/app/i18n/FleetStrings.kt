package net.vertexdezign.vdt.app.i18n

/** The Fleet app: the machine list, its filters, and a machine's detail cards. */
interface FleetStrings {
  val waiting: String
  val empty: String
  val listTitle: String
  val selectPrompt: String
  val search: String

  val sortName: String
  val sortCondition: String
  val sortHours: String
  val sortAge: String
  val sortService: String
  val sortValue: String

  val viewAll: String
  val viewAttention: String
  val viewWorkshop: String
  val viewLeased: String
  val viewImplements: String

  /** The row's fuel figure: [percent] left in the tank. */
  fun fuel(percent: Int): String

  /** Row badges: short, upper case, read at 8 sp. */
  val badgeBroken: String
  val badgeWorkshop: String
  val badgeUnknownState: String
  val badgeServiceDue: String
  val badgeLeased: String
  val badgeContract: String
  val badgeNeedsReset: String
  val badgeAi: String
  val badgeInUse: String
  val badgeParked: String

  /** The line under a machine's name. */
  val leased: String
  val contractEquipment: String

  fun attachedTo(rig: String): String

  val cardLevels: String
  val cardCondition: String
  val cardMachine: String
  val cardMaintenance: String

  val keptByAds: String
  val noCondition: String
  val condition: String
  val wear: String
  val dirt: String

  val age: String
  val operatingHours: String
  val sellValue: String
  val leasePerDay: String
  val status: String

  val statusDrowned: String
  val statusEntered: String
  val statusAi: String
  val statusInUse: String
  val statusParked: String
  val statusAttached: String
  val statusIdle: String

  val state: String
  val serviceOverdue: String
  val serviceInterval: String

  val adsReady: String
  val adsInspection: String
  val adsMaintenance: String
  val adsRepair: String
  val adsBroken: String
  val adsUnknown: String

  /** A machine's age, which the game counts in months, as years and months once there are enough. */
  fun age(months: Int): String
}
