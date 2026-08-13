package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.model.AdsCheck
import net.vertexdezign.vdt.model.AdsService
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.abs

/**
 * The maintenance tile: how long this machine has left before its service is due, what the pre-shift
 * walk-round would find, and what the electrics are doing.
 *
 * All of it comes from Advanced Damage System, and all of it is what the mod already tells a player
 * who asks — the service interval is printed in the shop and the fleet menu, the checks are what a
 * field inspection reports (in its own coarse bands, which is why they are words and not
 * percentages), and the voltage is on ADS's own dashboard. Nothing here is a number ADS hides behind
 * a workshop diagnostic; see the mod's `src/integrations/AdvancedDamageSystem.lua`.
 *
 * It answers a question the rest of the cluster cannot: *should I take this machine out today.* The
 * lamps say what has already gone wrong, the levels say what will run out this hour — this is the one
 * tile about the shift ahead, which is why it is a tile of its own rather than more rows on either.
 */
@Composable
fun ClusterService(vehicle: Vehicle, modifier: Modifier = Modifier) {
  val ads = vehicle.ads
  ClusterSurface(modifier) {
    if (ads?.service == null && ads?.checks == null && ads?.electrical == null) {
      // No ADS, or a machine it does not manage. Nothing to say beats a tile of zeroes.
      ClusterLabel("NO SERVICE DATA", Modifier.align(Alignment.Center), ClusterColors.Dim)
      return@ClusterSurface
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ads.service?.let { ServiceInterval(it) }
      ads.checks?.let { checks ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Check("RAD", checks.radiator, Modifier.weight(1f))
          Check("AIR", checks.airIntake, Modifier.weight(1f))
          Check("LUBE", checks.lubrication, Modifier.weight(1f))
        }
      }
      ads.electrical?.let {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
          ClusterLabel("VOLTS", Modifier.weight(1f))
          ClusterDigits(
            format1(it.systemVoltage),
            cells = 4,
            size = VALUE_SP.sp,
            colour = if (it.systemVoltage < LOW_VOLTS) ClusterColors.Warn else ClusterColors.Digits,
          )
        }
      }
    }
  }
}

/**
 * Hours left before the service is due, over a bar of the whole interval.
 *
 * Hours *remaining* rather than hours elapsed, because that is the number you act on — and once it
 * goes past, the sign is what says so, which is a difference you can read without comparing it to a
 * second figure. The bar keeps filling past the end of the interval so that "a bit overdue" and
 * "badly overdue" are not the same picture.
 */
@Composable
private fun ServiceInterval(service: AdsService) {
  val remaining = service.interval - service.hours
  val overdue = remaining < 0f
  val colour = when {
    overdue -> ClusterColors.Warn
    service.fraction > NEARLY_DUE -> ClusterColors.Set
    else -> ClusterColors.Digits
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
      ClusterLabel(if (overdue) "OVERDUE BY" else "SERVICE IN", Modifier.weight(1f))
      ClusterDigits(format1(abs(remaining)), cells = 4, size = VALUE_SP.sp, colour = colour)
      ClusterLabel("H", Modifier.padding(start = 3.dp))
    }
    ServiceBar(service.fraction, colour, Modifier.fillMaxWidth().height(BAR_HEIGHT))
  }
}

/**
 * The interval as one horizontal bar: a fixed frame, a level inside it, and a mark where the
 * recommended interval ends.
 *
 * The mark is the point — a bar with no scale on it says only "some", and this one is read against a
 * threshold rather than against empty and full. Past the mark the level runs on into the overrun the
 * frame leaves room for, and stops at the end of it; the digits carry the number by then anyway.
 */
@Composable
private fun ServiceBar(fraction: Float, colour: Color, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val full = size.width
    // The whole bar is one interval plus the overrun, so the due mark sits where it does regardless
    // of how far past it the machine is.
    val due = full / (1f + OVERRUN)
    drawRect(ClusterColors.Dim, size = size)
    drawRect(colour, size = Size((fraction / (1f + OVERRUN) * full).coerceIn(0f, full), size.height))
    drawRect(ClusterColors.Surface, topLeft = Offset(due, 0f), size = Size(MARK_WIDTH_PX, size.height))
  }
}

/**
 * One pre-shift chore: what it is, and how bad it is in ADS's own word for it.
 *
 * The word is the state, not a colour on a dot — these are read once before setting off rather than
 * at a glance while driving, and "HEAVY" is a thing you can act on where an amber pip is a thing you
 * have to look up. Colour still runs alongside it, so the worst of the three is findable first.
 */
@Composable
private fun Check(label: String, check: AdsCheck?, modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    ClusterLabel(label, align = TextAlign.Center)
    ClusterLabel(
      check?.name ?: "--",
      color = checkColour(check),
      align = TextAlign.Center,
      size = VALUE_SP.sp,
    )
  }
}

/**
 * How urgent a chore reads. Deliberately keyed off [AdsCheck.level] and not the enum's ordinal: two
 * ladders share that enum, so `DIRTY` and `DRY` are the same rung under different names.
 */
internal fun checkColour(check: AdsCheck?): Color = when (check?.level ?: 0) {
  0 -> ClusterColors.Label
  1 -> ClusterColors.Fill
  2, 3 -> ClusterColors.Set
  else -> ClusterColors.Warn
}

/** Amber once the interval is this far gone: enough warning to plan the trip to the workshop. */
private const val NEARLY_DUE = 0.8f

/** How much past the recommended interval the bar can still show, as a share of it. */
private const val OVERRUN = 0.5f

/** ADS puts its own voltage readout in warning colours below this. */
private const val LOW_VOLTS = 12f

private const val VALUE_SP = 13
private val BAR_HEIGHT = 6.dp
private const val MARK_WIDTH_PX = 2f
