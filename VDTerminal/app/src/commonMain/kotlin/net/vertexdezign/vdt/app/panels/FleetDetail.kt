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
import net.vertexdezign.vdt.model.FleetVehicle
import net.vertexdezign.vdt.model.PropertyState

/**
 * One machine, in full: what condition it is in, what it is carrying, what it is worth, and — where
 * Advanced Damage System is installed — whether it is in the workshop or due for service.
 *
 * Nothing here is a control. The game's own overview can sell and reset a machine; those are
 * irreversible and stay where the game put them. What this screen adds is the one action a second
 * screen is better at: showing you where the thing actually is.
 */
@Composable
internal fun FleetVehicleDetail(vehicle: FleetVehicle, rig: FleetVehicle?, onShowOnMap: (FleetVehicle) -> Unit) {
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
        Text(headline(vehicle, rig?.name), color = VdtColors.DarkGray, fontSize = 11.sp)
      }
      if (vehicle.posX != null && vehicle.posZ != null) {
        FinanceButton("Show on map", VdtColors.Green, { onShowOnMap(vehicle) })
      }
    }

    ConditionCard(vehicle)
    FactsCard(vehicle, rig)

    val fillUnits = buildList {
      vehicle.motorFillUnits?.let { addAll(listOfNotNull(it.fuel, it.def, it.air)) }
      vehicle.fillUnits?.let { addAll(it.fillUnit) }
    }
    if (fillUnits.isNotEmpty()) {
      DetailCard("Levels") { FillUnitsDisplay(fillUnits) }
    }

    vehicle.ads?.let { AdsCard(it) }
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
  val condition = fleetCondition(vehicle)
  DetailCard("Condition") {
    if (condition == null) {
      Text(
        if (vehicle.ads !=
          null
        ) {
          "Kept by Advanced Damage System — inspect it in a workshop"
        } else {
          "No condition reported"
        },
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
      )
    } else {
      ProgressBar(condition / 100f, leftLabel = "CONDITION", rightLabel = "$condition%")
    }
    // Wear and dirt are shown whatever manages the machine. Advanced Damage System replaces the
    // *damage* figure and nothing else, so these two stay live under it.
    vehicle.wearable?.let { wearable ->
      ProgressBar(wearable.wear / 100f, leftLabel = "WEAR", rightLabel = "${wearable.wear}%")
      ProgressBar(wearable.dirt / 100f, leftLabel = "DIRT", rightLabel = "${wearable.dirt}%")
    }
  }
}

@Composable
private fun FactsCard(vehicle: FleetVehicle, rig: FleetVehicle?) {
  DetailCard("Machine") {
    FactRow("Age", formatAge(vehicle.age))
    FactRow("Operating hours", "${formatHours(vehicle.hours)} h")
    vehicle.sellPrice?.let { FactRow("Sell value", formatMoney(it.toLong())) }
    vehicle.leasePerDay?.let { FactRow("Leasing, per day", formatMoney(it.toLong())) }
    FactRow("Status", statusLabel(vehicle, rig))
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
internal fun statusLabel(vehicle: FleetVehicle, rig: FleetVehicle? = null): String = when {
  // First, because it answers the question the others are about: nobody is taking this one anywhere.
  vehicle.broken -> "Drowned — needs a reset"

  vehicle.isEntered -> "You are in it"

  vehicle.isAI -> "Helper driving"

  vehicle.isControlled -> "In use"

  rig != null && (rig.isControlled || rig.isEntered) -> "In use"

  vehicle.isParked -> "Parked"

  vehicle.attachedTo != null -> "Attached"

  else -> "Idle"
}

@Composable
private fun AdsCard(ads: FleetAds) {
  DetailCard("Maintenance") {
    FactRow("State", adsStateLabel(ads.state))
    ads.service?.let { service ->
      ProgressBar(
        service.fraction.coerceIn(0f, 1f),
        leftLabel = if (ads.isServiceOverdue) "SERVICE OVERDUE" else "SERVICE INTERVAL",
        rightLabel = "${formatHours(service.hours)} / ${formatHours(service.interval)} h",
      )
    }
  }
}

/** ADS's states, as the sentence a driver would say rather than the enum name. */
internal fun adsStateLabel(state: AdsState): String = when (state) {
  AdsState.READY -> "Ready to work"

  AdsState.INSPECTION -> "In the workshop — inspection"

  AdsState.MAINTENANCE -> "In the workshop — maintenance"

  AdsState.REPAIR -> "In the workshop — repair"

  AdsState.BROKEN -> "Broken down"

  // Not a state ADS has: a token this build could not name. Said plainly, because the alternative is
  // to print one of the four above over a machine nobody has read the state of.
  AdsState.UNKNOWN -> "State not recognised"
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
