package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.FleetStrings

internal object EnFleet : FleetStrings {
  override val waiting = "Waiting for fleet data…"
  override val empty = "This farm owns no machines"
  override val listTitle = "Machines"
  override val selectPrompt = "Select a machine"
  override val search = "Search machines"

  override val sortName = "Name"
  override val sortCondition = "Condition"
  override val sortHours = "Hours"
  override val sortAge = "Age"
  override val sortService = "Service due"
  override val sortValue = "Value"

  override val viewAll = "All"
  override val viewAttention = "Attention"
  override val viewWorkshop = "Workshop"
  override val viewLeased = "Leased"
  override val viewImplements = "Implements"

  override fun fuel(percent: Int) = "fuel $percent%"

  override val badgeBroken = "BROKEN"
  override val badgeWorkshop = "WORKSHOP"
  override val badgeUnknownState = "UNKNOWN STATE"
  override val badgeServiceDue = "SERVICE DUE"
  override val badgeFault = "FAULT"
  override val badgeLeased = "LEASED"
  override val badgeContract = "CONTRACT"
  override val badgeNeedsReset = "NEEDS RESET"
  override val badgeAi = "AI"
  override val badgeInUse = "IN USE"
  override val badgeParked = "PARKED"

  override val leased = "leased"
  override val contractEquipment = "contract equipment"

  override fun attachedTo(rig: String) = "on $rig"

  override val cardLevels = "Levels"
  override val cardCondition = "Condition"
  override val cardMachine = "Machine"
  override val cardMaintenance = "Maintenance"

  override val neverInspected = "Never inspected — a workshop has to look at it"
  override val noCondition = "No condition reported"
  override val condition = "CONDITION"
  override val conditionEstimate = "CONDITION (ESTIMATE)"
  override val estimateNote = "From an ordinary inspection, not a full one"
  override val wear = "WEAR"
  override val dirt = "DIRT"

  override val age = "Age"
  override val operatingHours = "Operating hours"
  override val sellValue = "Sell value"
  override val leasePerDay = "Leasing, per day"
  override val status = "Status"

  override val statusDrowned = "Drowned — needs a reset"
  override val statusEntered = "You are in it"
  override val statusAi = "Helper driving"
  override val statusInUse = "In use"
  override val statusParked = "Parked"
  override val statusAttached = "Attached"
  override val statusIdle = "Idle"

  override val state = "State"
  override val serviceOverdue = "SERVICE OVERDUE"
  override val serviceInterval = "SERVICE INTERVAL"
  override val lastInspection = "Last inspection"
  override val lastMaintenance = "Last maintenance"
  override val maintenanceSpent = "Spent on maintenance"
  override val noFaults = "No faults found"
  override val noFaultsYet = "No faults found yet"
  override val workLeft = "Work left"
  override val readyAt = "Ready at"
  override val serviceCost = "Service cost"

  override fun finish(clock: String, inDays: Int) = when (inDays) {
    0 -> clock
    1 -> "$clock tomorrow"
    else -> "$clock, in $inDays days"
  }

  override val adsReady = "Ready to work"
  override val adsInspection = "In the workshop — inspection"
  override val adsMaintenance = "In the workshop — maintenance"
  override val adsRepair = "In the workshop — repair"
  override val adsOverhaul = "In the workshop — overhaul"
  override val adsBroken = "Broken down"
  override val adsUnknown = "State not recognised"

  override fun age(months: Int): String {
    if (months < 12) return "$months mo"
    val years = months / 12
    val rest = months % 12
    return if (rest == 0) "$years y" else "$years y $rest mo"
  }

  override fun monthsAgo(months: Int) = when (months) {
    0 -> "this month"
    1 -> "1 month ago"
    else -> "$months months ago"
  }
}
