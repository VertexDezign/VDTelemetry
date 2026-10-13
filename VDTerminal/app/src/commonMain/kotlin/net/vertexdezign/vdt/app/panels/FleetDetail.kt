package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.AdsState
import net.vertexdezign.vdt.model.FleetAds
import net.vertexdezign.vdt.model.FleetBreakdown
import net.vertexdezign.vdt.model.FleetVehicle
import net.vertexdezign.vdt.model.FleetWorkshop
import net.vertexdezign.vdt.model.GameDate
import net.vertexdezign.vdt.model.PropertyState

/**
 * One machine, in full: what condition it is in, what it is carrying, what it is worth, and — where
 * Advanced Damage System is installed — its maintenance record.
 *
 * Nothing here is a control. The game's own overview can sell and reset a machine; those are
 * irreversible and stay where the game put them. What this screen adds is the one action a second
 * screen is better at: showing you where the thing actually is.
 */
@Composable
internal fun FleetVehicleDetail(
  vehicle: FleetVehicle,
  today: GameDate?,
  rig: FleetVehicle?,
  onShowOnMap: (FleetVehicle) -> Unit,
) {
  val strings = LocalStrings.current
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f)) {
        Text(
          vehicle.name,
          color = VdtColors.TextDark,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(headline(strings, vehicle, rig?.name), color = VdtColors.DarkGray, fontSize = 11.sp)
      }
      if (vehicle.posX != null && vehicle.posZ != null) {
        FinanceButton(strings.common.showOnMap, VdtColors.Green, { onShowOnMap(vehicle) })
      }
    }

    ConditionCard(vehicle)
    FactsCard(vehicle, rig)

    val fillUnits = buildList {
      vehicle.motorFillUnits?.let { addAll(listOfNotNull(it.fuel, it.def, it.air)) }
      vehicle.fillUnits?.let { addAll(it.fillUnit) }
    }
    if (fillUnits.isNotEmpty()) {
      DetailCard(strings.fleet.cardLevels) { FillUnitsDisplay(fillUnits) }
    }

    vehicle.ads?.let { AdsCard(it, today) }
  }
}

/** Category, how the farm holds it, and the rig it is on — the line under the name. */
internal fun headline(strings: Strings, vehicle: FleetVehicle, attachedToName: String?): String = buildList {
  vehicle.category?.takeIf { it.isNotBlank() }?.let { add(it) }
  when (vehicle.propertyState) {
    PropertyState.LEASED -> add(strings.fleet.leased)
    PropertyState.MISSION -> add(strings.fleet.contractEquipment)
    else -> Unit
  }
  attachedToName?.let { add(strings.fleet.attachedTo(it)) }
}.joinToString(" · ")

// ---- Cards ---------------------------------------------------------------------------------------

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(title.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    content()
  }
}

@Composable
private fun ConditionCard(vehicle: FleetVehicle) {
  val s = LocalStrings.current.fleet
  val condition = fleetCondition(vehicle)
  DetailCard(s.cardCondition) {
    if (condition == null) {
      Text(
        if (vehicle.ads != null) s.neverInspected else s.noCondition,
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
      )
    } else {
      ProgressBar(
        condition / 100f,
        leftLabel = if (conditionIsApproximate(vehicle)) s.conditionEstimate else s.condition,
        rightLabel = "$condition%",
      )
      if (conditionIsApproximate(vehicle)) {
        // ADS gives an exact figure only after a full defectoscopy; anything else is its own coarse
        // band. Saying so is the difference between a reading and a guess.
        Text(
          s.estimateNote,
          color = VdtColors.DarkGray,
          fontSize = 10.sp,
        )
      }
    }
    // Wear and dirt are shown whatever manages the machine. Advanced Damage System replaces the
    // *damage* figure and nothing else, so these two stay live under it — the multiplayer capture has
    // a 6R 215 reading 83% condition on ADS's own inspection with its paint worn clean through.
    vehicle.wearable?.let { wearable ->
      ProgressBar(wearable.wear / 100f, leftLabel = s.wear, rightLabel = "${wearable.wear}%")
      ProgressBar(wearable.dirt / 100f, leftLabel = s.dirt, rightLabel = "${wearable.dirt}%")
    }
  }
}

@Composable
private fun FactsCard(vehicle: FleetVehicle, rig: FleetVehicle?) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val s = strings.fleet
  DetailCard(s.cardMachine) {
    FactRow(s.age, s.age(vehicle.age))
    FactRow(s.operatingHours, "${numbers.formatHours(vehicle.hours)} h")
    vehicle.sellPrice?.let { FactRow(s.sellValue, numbers.formatMoney(it.toLong())) }
    vehicle.leasePerDay?.let { FactRow(s.leasePerDay, numbers.formatMoney(it.toLong())) }
    FactRow(s.status, statusLabel(strings, vehicle, rig))
  }
}

/**
 * Who has the machine right now, in words, first answer wins. [rig] is the root vehicle an implement
 * hangs off, when it has one.
 *
 * **Parked means put away**, not merely standing still: it is the machine's tab-rotation flag, which
 * is what the parking mods turn off, so it is something the player did on purpose. A machine nobody
 * is driving and nobody has parked is *idle* — the difference matters to anyone running such a mod,
 * and calling that state "parked" quietly took their word for it.
 *
 * **An implement is told by its rig**, and only half of that comes for free: the engine chain-walks
 * `getIsAIActive()` up the attacher joints, so a plough behind a helper already says so — but
 * `isControlled` lives on the seat, which an implement does not have, so one behind a tractor a
 * *person* is driving would otherwise sit there claiming to be merely attached while it works.
 */
internal fun statusLabel(strings: Strings, vehicle: FleetVehicle, rig: FleetVehicle? = null): String = when {
  // First, because it answers the question the others are about: nobody is taking this one anywhere.
  vehicle.broken -> strings.fleet.statusDrowned

  vehicle.isEntered -> strings.fleet.statusEntered

  vehicle.isAI -> strings.fleet.statusAi

  vehicle.isControlled -> strings.fleet.statusInUse

  rig != null && (rig.isControlled || rig.isEntered) -> strings.fleet.statusInUse

  vehicle.isParked -> strings.fleet.statusParked

  vehicle.attachedTo != null -> strings.fleet.statusAttached

  else -> strings.fleet.statusIdle
}

@Composable
private fun AdsCard(ads: FleetAds, today: GameDate?) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val s = strings.fleet
  DetailCard(s.cardMaintenance) {
    FactRow(s.state, adsStateLabel(strings, ads.state))
    ads.service?.let { service ->
      ProgressBar(
        service.fraction.coerceIn(0f, 1f),
        leftLabel = if (ads.isServiceOverdue) s.serviceOverdue else s.serviceInterval,
        rightLabel = "${numbers.formatHours(service.hours)} / ${numbers.formatHours(service.interval)} h",
      )
    }
    today?.let { now ->
      ads.lastInspection?.let { FactRow(s.lastInspection, s.monthsAgo(monthsBetween(it, now))) }
      ads.lastMaintenance?.let { FactRow(s.lastMaintenance, s.monthsAgo(monthsBetween(it, now))) }
    }
    ads.maintenanceCost?.let { FactRow(s.maintenanceSpent, numbers.formatMoney(it.toLong())) }
    ads.workshop?.let { WorkshopRows(it) }

    if (ads.breakdowns.isEmpty()) {
      Text(
        if (ads.state == AdsState.READY) s.noFaults else s.noFaultsYet,
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
      )
    } else {
      ads.breakdowns.forEach { BreakdownRow(it) }
    }
  }
}

@Composable
private fun WorkshopRows(workshop: FleetWorkshop) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  workshop.remaining?.let { FactRow(strings.fleet.workLeft, "${numbers.formatHours(it)} h") }
  workshop.finishHour?.let { FactRow(strings.fleet.readyAt, formatFinish(strings, it, workshop.finishInDays)) }
  workshop.price?.let { FactRow(strings.fleet.serviceCost, numbers.formatMoney(it.toLong())) }
}

/** ADS's finish time: an hour of the game's day, plus how many midnights away it is. */
internal fun formatFinish(strings: Strings, hour: Float, inDays: Int): String {
  val minutes = ((hour % 24f) * 60).toInt().coerceAtLeast(0)
  val clock = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
  return strings.fleet.finish(clock, inDays)
}

/** ADS's states, as the sentence a driver would say rather than the enum name. */
internal fun adsStateLabel(strings: Strings, state: AdsState): String = when (state) {
  AdsState.READY -> strings.fleet.adsReady

  AdsState.INSPECTION -> strings.fleet.adsInspection

  AdsState.MAINTENANCE -> strings.fleet.adsMaintenance

  AdsState.REPAIR -> strings.fleet.adsRepair

  AdsState.OVERHAUL -> strings.fleet.adsOverhaul

  AdsState.BROKEN -> strings.fleet.adsBroken

  // Not a state ADS has: a token this build could not name. Said plainly, because the alternative is
  // to print one of the five above over a machine nobody has read the state of.
  AdsState.UNKNOWN -> strings.fleet.adsUnknown
}

@Composable
private fun BreakdownRow(breakdown: FleetBreakdown) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        breakdown.part ?: breakdown.id,
        color = VdtColors.TextDark,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
      )
      breakdown.description?.let { Text(it, color = VdtColors.DarkGray, fontSize = 10.sp) }
    }
    // The severity is ADS's own word for the stage, so it reads the same here as in its workshop.
    breakdown.severity?.let {
      Text(it.uppercase(), color = VdtColors.TextDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun FactRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = VdtColors.DarkGray, fontSize = 11.sp, modifier = Modifier.width(150.dp))
    Box(Modifier.weight(1f)) {
      Text(value, color = VdtColors.TextDark, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}
