package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.TirePressureScope
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.TirePressureStrings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.TireAxle
import net.vertexdezign.vdt.model.TireChange
import net.vertexdezign.vdt.model.TireMode
import net.vertexdezign.vdt.model.TirePressure
import net.vertexdezign.vdt.model.Vehicle

/** One machine on the rig whose tyres have the inflation system. */
data class TireMachine(val name: String, val pressure: TirePressure)

/**
 * The rig as the tyre widget groups it: the vehicle's own tyres, and every implement that has the system,
 * depth-first — the order the mod's implements key walks them in. Null when nothing on the rig has it.
 */
data class TireRig(val vehicle: TireMachine?, val implements: List<TireMachine>) {
  /** The whole rig in one mode, or null while its parts disagree (a [TireMode.MIXED] tractor counts as disagreeing). */
  val mode: TireMode? get() = groupMode(listOfNotNull(vehicle) + implements)

  val implementsMode: TireMode? get() = groupMode(implements)

  /**
   * Which way one tap on the whole rig goes, where there is no room for a pair: down to field from a rig
   * all at road, and up to road from anything else — a rig whose parts disagree comes up together,
   * since road is the pressure nothing is harmed by.
   */
  val switchesToRoad: Boolean get() = mode != TireMode.ROAD

  companion object {
    fun of(vehicle: Vehicle): TireRig? {
      val implements = mutableListOf<TireMachine>()
      fun walk(list: List<Implement>) {
        for (implement in list) {
          implement.tirePressure?.let { implements += TireMachine(implement.name, it) }
          walk(implement.implement)
        }
      }
      walk(vehicle.implement)
      val own = vehicle.tirePressure?.let { TireMachine(vehicle.name, it) }
      return if (own == null && implements.isEmpty()) null else TireRig(own, implements)
    }

    private fun groupMode(machines: List<TireMachine>): TireMode? =
      machines.map { it.pressure.mode }.distinct().singleOrNull()?.takeIf { it != TireMode.MIXED }
  }
}

/**
 * Variable Tire Pressure on the terminal (issue #193): the tyre pressure of the machine and of everything
 * hitched to it, and the road / field switch for each — the mod's tractor key, its implements key, and
 * the whole rig at once when both have the system.
 *
 * Every switch sends an absolute mode ([ClientMessage.SetTirePressure]); the mod presses its own key
 * only where the machines are not already there. A pair with neither lit is a group whose machines
 * disagree — a tractor running its axles apart, or a trailer hitched at another pressure — and either
 * button brings it together.
 *
 * The mode is the chip's fill **and** its word, never a colour on its own; the direction of a change is
 * an arrow [Icon], with the target in its description and beside it.
 */
@Composable
fun TirePressurePanel(vehicle: Vehicle?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  val s = LocalStrings.current.tirePressure
  val rig = vehicle?.let { TireRig.of(it) }

  Panel(title = s.title, icon = Icons.Filled.TireRepair, modifier = modifier) {
    when {
      vehicle == null -> Empty(s.notInVehicle)

      rig == null -> Empty(s.noSystem)

      else -> Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        val set = { scope: TirePressureScope, road: Boolean -> onCommand(ClientMessage.SetTirePressure(scope, road)) }
        if (rig.vehicle != null && rig.implements.isNotEmpty()) {
          Group(s.wholeRig, rig.mode, onSet = { set(TirePressureScope.ALL, it) })
        }
        rig.vehicle?.let { machine ->
          Group(machine.name, machine.pressure.mode.takeIf { it != TireMode.MIXED }, onSet = {
            set(TirePressureScope.VEHICLE, it)
          }) {
            Reading(machine.pressure, showMode = machine.pressure.mode == TireMode.MIXED)
            machine.pressure.front?.let { AxleLine(s.front, it) }
            machine.pressure.rear?.let { AxleLine(s.rear, it) }
          }
        }
        if (rig.implements.isNotEmpty()) {
          Group(s.implements, rig.implementsMode, onSet = { set(TirePressureScope.IMPLEMENTS, it) }) {
            for (machine in rig.implements) {
              // Named here, since the group's title is the scope; and the mode said in words, because one
              // trailer can sit at the other pressure from the rest.
              Label(machine.name)
              Reading(machine.pressure, showMode = rig.implements.size > 1)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Empty(text: String) {
  Text(text, fontSize = 12.sp, color = VdtColors.DarkGray)
}

/** A scope's title and its road / field pair, with what it switches below. */
@Composable
private fun Group(
  title: String,
  mode: TireMode?,
  onSet: (road: Boolean) -> Unit,
  content: @Composable () -> Unit = {},
) {
  val s = LocalStrings.current.tirePressure
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = VdtColors.DarkGray,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      // Tapping the lit one re-sends it, which the mod settles as already there.
      FilterChip(s.road, active = mode == TireMode.ROAD, onClick = { onSet(true) })
      FilterChip(s.field, active = mode == TireMode.FIELD, onClick = { onSet(false) })
    }
    content()
  }
}

@Composable
private fun Label(text: String) {
  Text(text, fontSize = 11.sp, color = VdtColors.TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The pressure now, an arrow toward its target while it moves, and where it sits between the two
 * setpoints. [showMode] adds the mode in words, for a reading whose group chips cannot say it.
 */
@Composable
private fun Reading(pressure: TirePressure, showMode: Boolean) {
  val strings = LocalStrings.current
  val s = strings.tirePressure
  val numbers = strings.numbers
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        s.bar(numbers.fixed(pressure.bar, 1)),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = VdtColors.TextDark,
      )
      pressure.change?.let { change ->
        val target = numbers.fixed(pressure.targetBar, 1)
        Icon(
          if (change == TireChange.INFLATING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
          contentDescription = if (change == TireChange.INFLATING) s.inflatingTo(target) else s.deflatingTo(target),
          tint = VdtColors.DarkGray,
          modifier = Modifier.size(16.dp),
        )
        Text(target, fontSize = 12.sp, color = VdtColors.DarkGray)
      }
      if (showMode) {
        Text(
          modeWord(s, pressure.mode).uppercase(),
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          color = VdtColors.DarkGray,
        )
      }
    }
    val road = pressure.roadBar
    val field = pressure.fieldBar
    if (road != null && field != null && road > field) {
      ProgressBar(
        fraction = (pressure.bar - field) / (road - field),
        leftLabel = "${s.field} ${numbers.fixed(field, 1)}",
        rightLabel = "${s.road} ${numbers.fixed(road, 1)}",
      )
    }
  }
}

/** One axle of a tractor running them apart: its pressure and its mode, in words. */
@Composable
private fun AxleLine(name: String, axle: TireAxle) {
  val strings = LocalStrings.current
  val s = strings.tirePressure
  Text(
    "$name  ${s.bar(strings.numbers.fixed(axle.bar, 1))} · ${modeWord(s, axle.mode)}",
    fontSize = 11.sp,
    color = VdtColors.TextDark,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

private fun modeWord(s: TirePressureStrings, mode: TireMode): String = when (mode) {
  TireMode.ROAD -> s.road
  TireMode.FIELD -> s.field
  TireMode.MIXED -> s.mixed
}
