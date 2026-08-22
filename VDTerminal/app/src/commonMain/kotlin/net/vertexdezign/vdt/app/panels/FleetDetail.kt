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
  attachedToName: String?,
  onShowOnMap: (FleetVehicle) -> Unit,
) {
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
        Text(headline(vehicle, attachedToName), color = VdtColors.DarkGray, fontSize = 11.sp)
      }
      if (vehicle.posX != null && vehicle.posZ != null) {
        FinanceButton("Show on map", VdtColors.Green, { onShowOnMap(vehicle) })
      }
    }

    ConditionCard(vehicle)
    FactsCard(vehicle)

    val fillUnits = buildList {
      vehicle.motorFillUnits?.let { addAll(listOfNotNull(it.fuel, it.def, it.air)) }
      vehicle.fillUnits?.let { addAll(it.fillUnit) }
    }
    if (fillUnits.isNotEmpty()) {
      DetailCard("Levels") { FillUnitsDisplay(fillUnits) }
    }

    vehicle.ads?.let { AdsCard(it, today) }
  }
}

/** Category, how the farm holds it, and the rig it is on — the line under the name. */
internal fun headline(vehicle: FleetVehicle, attachedToName: String?): String = buildList {
  vehicle.category?.takeIf { it.isNotBlank() }?.let { add(it) }
  when (vehicle.propertyState) {
    PropertyState.LEASED -> add("leased")
    PropertyState.MISSION -> add("contract equipment")
    else -> Unit
  }
  attachedToName?.let { add("on $it") }
}.joinToString(" · ")

// ---- Cards ---------------------------------------------------------------------------------------

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White)
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
  val condition = fleetCondition(vehicle)
  DetailCard("Condition") {
    if (condition == null) {
      Text(
        if (vehicle.ads != null) "Never inspected — a workshop has to look at it" else "No condition reported",
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
      )
    } else {
      ProgressBar(
        condition / 100f,
        leftLabel = if (conditionIsApproximate(vehicle)) "CONDITION (ESTIMATE)" else "CONDITION",
        rightLabel = "$condition%",
      )
      if (conditionIsApproximate(vehicle)) {
        // ADS gives an exact figure only after a full defectoscopy; anything else is its own coarse
        // band. Saying so is the difference between a reading and a guess.
        Text(
          "From an ordinary inspection, not a full one",
          color = VdtColors.DarkGray,
          fontSize = 10.sp,
        )
      }
    }
    // Wear and dirt stay vanilla under ADS and mean what they always did.
    vehicle.wearable?.let { wearable ->
      if (vehicle.ads == null) {
        ProgressBar(wearable.wear / 100f, leftLabel = "WEAR", rightLabel = "${wearable.wear}%")
      }
      ProgressBar(wearable.dirt / 100f, leftLabel = "DIRT", rightLabel = "${wearable.dirt}%")
    }
  }
}

@Composable
private fun FactsCard(vehicle: FleetVehicle) {
  DetailCard("Machine") {
    FactRow("Age", formatAge(vehicle.age))
    FactRow("Operating hours", "${formatHours(vehicle.hours)} h")
    vehicle.sellPrice?.let { FactRow("Sell value", formatMoney(it.toLong())) }
    vehicle.leasePerDay?.let { FactRow("Leasing, per day", formatMoney(it.toLong())) }
    FactRow("Status", statusLabel(vehicle))
  }
}

/**
 * Who has the machine right now, in words, first answer wins.
 *
 * **Parked means put away**, not merely standing still: it is the machine's tab-rotation flag, which
 * is what the parking mods turn off, so it is something the player did on purpose. A machine nobody
 * is driving and nobody has parked is *idle* — the difference matters to anyone running such a mod,
 * and calling that state "parked" quietly took their word for it.
 */
internal fun statusLabel(vehicle: FleetVehicle): String = when {
  vehicle.isEntered -> "You are in it"
  vehicle.isAI -> "Helper driving"
  vehicle.isControlled -> "In use"
  vehicle.isParked -> "Parked"
  vehicle.attachedTo != null -> "Attached"
  else -> "Idle"
}

@Composable
private fun AdsCard(ads: FleetAds, today: GameDate?) {
  DetailCard("Maintenance") {
    FactRow("State", adsStateLabel(ads.state))
    ads.service?.let { service ->
      ProgressBar(
        service.fraction.coerceIn(0f, 1f),
        leftLabel = if (ads.isServiceOverdue) "SERVICE OVERDUE" else "SERVICE INTERVAL",
        rightLabel = "${formatHours(service.hours)} / ${formatHours(service.interval)} h",
      )
    }
    today?.let { now ->
      ads.lastInspection?.let { FactRow("Last inspection", formatMonthsAgo(monthsBetween(it, now))) }
      ads.lastMaintenance?.let { FactRow("Last maintenance", formatMonthsAgo(monthsBetween(it, now))) }
    }
    ads.maintenanceCost?.let { FactRow("Spent on maintenance", formatMoney(it.toLong())) }
    ads.workshop?.let { WorkshopRows(it) }

    if (ads.breakdowns.isEmpty()) {
      Text(
        if (ads.state == AdsState.READY) "No faults found" else "No faults found yet",
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
  workshop.remaining?.let { FactRow("Work left", "${formatHours(it)} h") }
  workshop.finishHour?.let { FactRow("Ready at", formatFinish(it, workshop.finishInDays)) }
  workshop.price?.let { FactRow("Service cost", formatMoney(it.toLong())) }
}

/** ADS's finish time: an hour of the game's day, plus how many midnights away it is. */
internal fun formatFinish(hour: Float, inDays: Int): String {
  val minutes = ((hour % 24f) * 60).toInt().coerceAtLeast(0)
  val clock = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
  return when (inDays) {
    0 -> clock
    1 -> "$clock tomorrow"
    else -> "$clock, in $inDays days"
  }
}

/** ADS's states, as the sentence a driver would say rather than the enum name. */
internal fun adsStateLabel(state: AdsState): String = when (state) {
  AdsState.READY -> "Ready to work"
  AdsState.INSPECTION -> "In the workshop — inspection"
  AdsState.MAINTENANCE -> "In the workshop — maintenance"
  AdsState.REPAIR -> "In the workshop — repair"
  AdsState.OVERHAUL -> "In the workshop — overhaul"
  AdsState.BROKEN -> "Broken down"
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
