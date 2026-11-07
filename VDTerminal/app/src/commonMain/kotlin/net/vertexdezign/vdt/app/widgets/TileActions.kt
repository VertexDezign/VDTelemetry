package net.vertexdezign.vdt.app.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.CruiseAction
import net.vertexdezign.vdt.LightTarget
import net.vertexdezign.vdt.TirePressureScope
import net.vertexdezign.vdt.TurnLightState
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.panels.ClusterIcons
import net.vertexdezign.vdt.app.panels.RigSlot
import net.vertexdezign.vdt.app.panels.TireRig
import net.vertexdezign.vdt.app.panels.findImplement
import net.vertexdezign.vdt.model.AutoDriveData
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Lights
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.TireMode
import net.vertexdezign.vdt.model.Vehicle

/**
 * The word an action button shows under its icon, saying which state the switch is in.
 *
 * The word is there because the fill alone is not enough. Fill and brightness do differ between on
 * and off, but a lamp keeps its icon in both states, and the design rules require two states to
 * differ in a word or a shape as well as in colour (`VDTerminal/README.md` → "Design rules").
 */
internal enum class StateWord {
  ON,
  OFF,
  LOWERED,
  RAISED,
  FOLDED,
  UNFOLDED,
  ROAD,
  FIELD,
  MIXED,
  ;

  fun label(strings: Strings): String = when (this) {
    ON -> strings.isoBus.on
    OFF -> strings.isoBus.off
    LOWERED -> strings.isoBus.lowered
    RAISED -> strings.isoBus.raised
    FOLDED -> strings.isoBus.folded
    UNFOLDED -> strings.isoBus.unfolded
    ROAD -> strings.tirePressure.road
    FIELD -> strings.tirePressure.field
    MIXED -> strings.tirePressure.mixed
  }
}

/**
 * What one action's button shows right now, and what a tap on it would send.
 *
 * [icon] is the icon for the *state*, the same one the full panels draw. A machine folded and
 * unfolded, or an engine running and off, differ in shape, not just in fill. [on] drives the fill.
 *
 * [command] is null when the game would refuse the tap right now (Speed Sync with nothing in range,
 * AutoDrive with no destination). The button is still drawn with its state, but it does nothing when
 * tapped. That is not the same as having no state at all, where [TileAction.stateOf] returns null and
 * the tile says the action is unavailable.
 */
internal data class ActionState(
  val on: Boolean,
  val word: StateWord,
  val icon: ImageVector,
  val command: ClientMessage?,
)

/**
 * One switch an action button can be pointed at.
 *
 * [id] is persisted in page layouts, so it must never change once shipped. [icon] is the action's
 * identity in the picker and on a tile that has no state to show. [stateOf] is pure, so the catalog
 * can be tested without composing anything.
 *
 * Every command here carries the **absolute** target worked out from the state being shown, never a
 * toggle. The command channel can drop or repeat a message, and a repeated "set on" does no harm
 * where a repeated toggle would switch the light straight back off (see [ClientMessage]). The game's
 * raw input actions are deliberately not offered for the same reason: pressing a key is a toggle.
 * One-shot actions (unloading a bale, resetting the counter) are left out because they have no state
 * to show, which is what this widget is for.
 */
internal class TileAction(
  val id: String,
  val icon: ImageVector,
  private val name: (Strings) -> String,
  private val read: (Vehicle, AutoDriveData?) -> ActionState?,
) {
  fun title(strings: Strings): String = name(strings)

  /**
   * The state on [vehicle], or null when there is none to show: no vehicle, or a vehicle without
   * this switch (no beacon, no Speed Sync installed, nothing hitched at the rear). [autoDrive] is
   * AutoDrive's network channel, which is needed only to tell whether a start would be refused.
   */
  fun stateOf(vehicle: Vehicle?, autoDrive: AutoDriveData? = null): ActionState? = vehicle?.let { read(it, autoDrive) }
}

/**
 * The positions a machine action can be addressed to: the three [RigSlot]s plus the game's own
 * selection, which is the only way to reach a machine further down a hitch chain (see
 * [ControlTarget.SELECTED]).
 */
internal enum class ActionSlot(val token: String, val target: ControlTarget) {
  VEHICLE("vehicle", ControlTarget.VEHICLE),
  FRONT("front", ControlTarget.FRONT),
  REAR("rear", ControlTarget.BACK),
  SELECTED("selected", ControlTarget.SELECTED),
  ;

  fun label(strings: Strings): String = when (this) {
    VEHICLE -> strings.vehicle.slotVehicle
    FRONT -> strings.vehicle.slotFront
    REAR -> strings.vehicle.slotRear
    SELECTED -> strings.widgets.slotSelected
  }
}

/** The three aspects a machine action reads. The vehicle and an implement both have them. */
private class MachineAspects(val foldable: FoldableState?, val isTurnedOn: Boolean?, val lowered: Boolean?)

private fun Vehicle.aspects() = MachineAspects(foldable, isTurnedOn, lowered)

private fun Implement.aspects() = MachineAspects(foldable, isTurnedOn, lowered)

private fun findSelected(list: List<Implement>): Implement? {
  for (imp in list) {
    if (imp.selection?.selected == true) return imp
    findSelected(imp.implement)?.let { return it }
  }
  return null
}

/**
 * The machine at [slot] on [vehicle], or null when nothing is there.
 *
 * Front and rear are looked up with the rig slot's own [findImplement], so a button and a rig tile
 * that both say "Rear" read the same machine. The selection is the one node the game has flagged,
 * and that can be the vehicle itself.
 */
private fun machineAt(vehicle: Vehicle, slot: ActionSlot): MachineAspects? = when (slot) {
  ActionSlot.VEHICLE -> vehicle.aspects()

  ActionSlot.FRONT -> findImplement(vehicle.implement, RigSlot.FRONT.implementPosition!!)?.aspects()

  ActionSlot.REAR -> findImplement(vehicle.implement, RigSlot.REAR.implementPosition!!)?.aspects()

  ActionSlot.SELECTED ->
    if (vehicle.selection?.selected == true) vehicle.aspects() else findSelected(vehicle.implement)?.aspects()
}

/** An on/off switch whose icon does not change: the fill and the word carry the state. */
private fun switch(on: Boolean, icon: ImageVector, command: ClientMessage?) =
  ActionState(on, if (on) StateWord.ON else StateWord.OFF, icon, command)

private fun light(
  id: String,
  icon: ImageVector,
  target: LightTarget,
  name: (Strings) -> String,
  get: (Lights) -> Boolean?,
) = TileAction(id, icon, name) { v, _ ->
  v.lights?.let(get)?.let { on -> switch(on, icon, ClientMessage.SetLight(target, !on)) }
}

/** Fold, power and lower, for each [ActionSlot]. The same three actions as [net.vertexdezign.vdt.app.components.ImplementControls]. */
private fun machineActions(slot: ActionSlot): List<TileAction> = listOf(
  TileAction("fold.${slot.token}", Icons.Filled.UnfoldMore, {
    it.widgets.actionAt(it.widgets.fold, slot.label(it))
  }) { v, _ ->
    machineAt(v, slot)?.foldable?.let { f ->
      val extended = f == FoldableState.EXTENDED
      ActionState(
        on = extended,
        word = if (extended) StateWord.UNFOLDED else StateWord.FOLDED,
        icon = if (extended) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess,
        // `on` means *folded* on the wire, so an unfolded machine is sent `on = true`.
        command = ClientMessage.SetFolded(slot.target, on = extended),
      )
    }
  },
  TileAction("power.${slot.token}", Icons.Filled.PowerSettingsNew, {
    it.widgets.actionAt(it.widgets.power, slot.label(it))
  }) { v, _ ->
    machineAt(v, slot)?.isTurnedOn?.let { on ->
      switch(
        on,
        if (on) Icons.Filled.PowerSettingsNew else Icons.Filled.PowerOff,
        ClientMessage.SetActivated(slot.target, !on),
      )
    }
  },
  TileAction("lower.${slot.token}", Icons.Filled.ArrowDownward, {
    it.widgets.actionAt(it.widgets.lower, slot.label(it))
  }) { v, _ ->
    machineAt(v, slot)?.lowered?.let { down ->
      ActionState(
        on = down,
        word = if (down) StateWord.LOWERED else StateWord.RAISED,
        icon = if (down) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
        command = ClientMessage.SetLowered(slot.target, !down),
      )
    }
  },
)

/** Every action a button can be pointed at, in the order the picker lists them. */
internal object TileActions {
  val all: List<TileAction> =
    listOf(
      TileAction("engine", Icons.Filled.Key, { it.widgets.engine }) { v, _ ->
        v.motor?.let { motor ->
          // Running and nothing looser, as on the engine panel: a key turned or a starter cranking
          // is not a running engine, and a tap from either asks for ON again rather than cancelling.
          val running = motor.state.isRunning
          switch(
            running,
            if (motor.state == MotorState.OFF) Icons.Filled.KeyOff else Icons.Filled.Key,
            ClientMessage.SetMotorState(on = !running),
          )
        }
      },
      TileAction("speedSync", ClusterIcons.SpeedSync, { it.widgets.speedSync }) { v, _ ->
        // Inert where the mod's own key would refuse (nothing in range, a helper driving), as on the
        // engine panel. The button stays, so it is there to use when you pull up beside the combine.
        v.speedSync?.let {
          switch(it.active, ClusterIcons.SpeedSync, ClientMessage.SetSpeedSync(!it.active).takeIf { _ -> it.available })
        }
      },
      TileAction("cruise", Icons.Filled.Speed, { it.widgets.cruise }) { v, _ ->
        v.cruiseControl?.let {
          val on = it.active == true
          switch(
            on,
            Icons.Filled.Speed,
            ClientMessage.SetCruiseControl(if (on) CruiseAction.DISABLE else CruiseAction.ENABLE),
          )
        }
      },
      TileAction("autoDrive", Icons.Filled.Route, { it.apps.autoDrive }) { v, network ->
        v.autoDrive?.let { ad ->
          // A start is refused without a destination or a route network, as AutoDrive's own button
          // refuses it. Stopping is always allowed.
          val canStart = ad.target != null && network?.destinations?.isNotEmpty() == true
          switch(
            ad.active,
            Icons.Filled.Route,
            ClientMessage.SetAutoDriveActive(!ad.active).takeIf {
              ad.active ||
                canStart
            },
          )
        }
      },
      TileAction("guideLines", Icons.Filled.Timeline, { it.vehicle.guideLines }) { v, _ ->
        v.gps?.let {
          switch(it.linesVisible, Icons.Filled.Timeline, ClientMessage.SetGpsLinesVisible(!it.linesVisible))
        }
      },
      TileAction("tirePressure", Icons.Filled.TireRepair, { it.tirePressure.title }) { v, _ ->
        TireRig.of(v)?.let { rig ->
          // Road and field are two modes, not on and off. The fill marks field, the mode that
          // changes how the machine drives, and the word always names which mode it is in. A mixed
          // rig goes to road, as the whole-rig key does.
          val mode = rig.mode
          ActionState(
            on = mode == TireMode.FIELD,
            word = when (mode) {
              TireMode.ROAD -> StateWord.ROAD
              TireMode.FIELD -> StateWord.FIELD
              else -> StateWord.MIXED
            },
            icon = Icons.Filled.TireRepair,
            command = ClientMessage.SetTirePressure(TirePressureScope.ALL, rig.switchesToRoad),
          )
        }
      },
      light("light.beacon", ClusterIcons.Beacon, LightTarget.BEACON, { it.widgets.beacon }) { it.beaconLight },
      light("light.lowBeam", ClusterIcons.LowBeam, LightTarget.LOW_BEAM, { it.widgets.lowBeam }) { it.light?.lowBeam },
      light("light.highBeam", ClusterIcons.HighBeam, LightTarget.HIGH_BEAM, {
        it.widgets.highBeam
      }) { it.light?.highBeam },
      light("light.workFront", ClusterIcons.WorkFront, LightTarget.WORK_FRONT, { it.widgets.workFront }) {
        it.workLight?.front
      },
      light("light.workRear", ClusterIcons.WorkRear, LightTarget.WORK_BACK, {
        it.widgets.workRear
      }) { it.workLight?.back },
      TileAction("hazard", ClusterIcons.Hazard, { it.widgets.hazard }) { v, _ ->
        v.lights?.indicator?.let {
          switch(
            it.hazard,
            ClusterIcons.Hazard,
            ClientMessage.SetTurnLight(if (it.hazard) TurnLightState.OFF else TurnLightState.HAZARD),
          )
        }
      },
    ) + ActionSlot.entries.flatMap(::machineActions)

  fun byId(id: String): TileAction? = all.firstOrNull { it.id == id }
}
