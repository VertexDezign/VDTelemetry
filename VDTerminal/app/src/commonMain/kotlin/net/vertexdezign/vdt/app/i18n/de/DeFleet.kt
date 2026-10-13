package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.FleetStrings

internal object DeFleet : FleetStrings {
  override val waiting = "Warte auf Fuhrparkdaten…"
  override val empty = "Dieser Hof besitzt keine Maschinen"
  override val listTitle = "Maschinen"
  override val selectPrompt = "Maschine wählen"
  override val search = "Maschinen suchen"

  override val sortName = "Name"
  override val sortCondition = "Zustand"
  override val sortHours = "Stunden"
  override val sortAge = "Alter"
  override val sortService = "Wartung fällig"
  override val sortValue = "Wert"

  override val viewAll = "Alle"
  override val viewAttention = "Achtung"
  override val viewWorkshop = "Werkstatt"
  override val viewLeased = "Geleast"
  override val viewImplements = "Geräte"

  override fun fuel(percent: Int) = "Tank $percent%"

  override val badgeBroken = "DEFEKT"
  override val badgeWorkshop = "WERKSTATT"
  override val badgeUnknownState = "UNBEK. ZUSTAND"
  override val badgeServiceDue = "WARTUNG FÄLLIG"
  override val badgeFault = "FEHLER"
  override val badgeLeased = "GELEAST"
  override val badgeContract = "AUFTRAG"
  override val badgeNeedsReset = "RESET NÖTIG"
  override val badgeAi = "HELFER"
  override val badgeInUse = "IN BENUTZUNG"
  override val badgeParked = "GEPARKT"

  override val leased = "geleast"
  override val contractEquipment = "Auftragsgerät"

  override fun attachedTo(rig: String) = "an $rig"

  override val cardLevels = "Füllstände"
  override val cardCondition = "Zustand"
  override val cardMachine = "Maschine"
  override val cardMaintenance = "Wartung"

  override val neverInspected = "Nie inspiziert — eine Werkstatt muss sie ansehen"
  override val noCondition = "Kein Zustand gemeldet"
  override val condition = "ZUSTAND"
  override val conditionEstimate = "ZUSTAND (GESCHÄTZT)"
  override val estimateNote = "Aus einer einfachen Inspektion, keiner vollständigen"
  override val wear = "VERSCHLEISS"
  override val dirt = "SCHMUTZ"

  override val age = "Alter"
  override val operatingHours = "Betriebsstunden"
  override val sellValue = "Verkaufswert"
  override val leasePerDay = "Leasing pro Tag"
  override val status = "Status"

  override val statusDrowned = "Ertrunken — muss zurückgesetzt werden"
  override val statusEntered = "Du sitzt darin"
  override val statusAi = "Helfer fährt"
  override val statusInUse = "In Benutzung"
  override val statusParked = "Geparkt"
  override val statusAttached = "Angehängt"
  override val statusIdle = "Unbenutzt"

  override val state = "Zustand"
  override val serviceOverdue = "WARTUNG ÜBERFÄLLIG"
  override val serviceInterval = "WARTUNGSINTERVALL"
  override val lastInspection = "Letzte Inspektion"
  override val lastMaintenance = "Letzte Wartung"
  override val maintenanceSpent = "Bisherige Wartungskosten"
  override val noFaults = "Keine Fehler gefunden"
  override val noFaultsYet = "Noch keine Fehler gefunden"
  override val workLeft = "Verbleibende Arbeit"
  override val readyAt = "Fertig um"
  override val serviceCost = "Werkstattkosten"

  override fun finish(clock: String, inDays: Int) = when (inDays) {
    0 -> clock
    1 -> "$clock morgen"
    else -> "$clock, in $inDays Tagen"
  }

  override val adsReady = "Einsatzbereit"
  override val adsInspection = "In der Werkstatt — Inspektion"
  override val adsMaintenance = "In der Werkstatt — Wartung"
  override val adsRepair = "In der Werkstatt — Reparatur"
  override val adsOverhaul = "In der Werkstatt — Überholung"
  override val adsBroken = "Ausgefallen"
  override val adsUnknown = "Zustand unbekannt"

  override fun age(months: Int): String {
    if (months < 12) return "$months Mon."
    val years = months / 12
    val rest = months % 12
    return if (rest == 0) "$years J." else "$years J. $rest Mon."
  }

  override fun monthsAgo(months: Int) = when (months) {
    0 -> "diesen Monat"
    1 -> "vor 1 Monat"
    else -> "vor $months Monaten"
  }
}
