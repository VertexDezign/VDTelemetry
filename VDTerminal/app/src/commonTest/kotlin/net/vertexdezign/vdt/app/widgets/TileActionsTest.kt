package net.vertexdezign.vdt.app.widgets

import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.CruiseAction
import net.vertexdezign.vdt.LightTarget
import net.vertexdezign.vdt.TirePressureScope
import net.vertexdezign.vdt.TurnLightState
import net.vertexdezign.vdt.model.AutoDrive
import net.vertexdezign.vdt.model.AutoDriveData
import net.vertexdezign.vdt.model.AutoDriveDestination
import net.vertexdezign.vdt.model.AutoDriveTarget
import net.vertexdezign.vdt.model.CruiseControl
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Indicator
import net.vertexdezign.vdt.model.Lights
import net.vertexdezign.vdt.model.Motor
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.Selection
import net.vertexdezign.vdt.model.SpeedSync
import net.vertexdezign.vdt.model.TireMode
import net.vertexdezign.vdt.model.TirePressure
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The action buttons' catalog: which state each action reads, and the absolute command a tap sends
 * from it. These tests are the place to look first if a button shows the wrong state, because the
 * catalog is the only part that knows the model.
 */
class TileActionsTest {
  private fun action(id: String) = TileActions.byId(id) ?: error("no action $id")

  @Test
  fun idsAreUniqueAndThePersistedOnesStayPut() {
    val ids = TileActions.all.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    // Page layouts store these. Renaming one breaks every button placed with it.
    for (id in listOf("engine", "speedSync", "light.beacon", "hazard", "fold.rear", "power.selected", "lower.front")) {
      assertTrue(id in ids, id)
    }
  }

  @Test
  fun offTheVehicleNothingIsAvailable() {
    for (action in TileActions.all) assertNull(action.stateOf(null), action.id)
  }

  @Test
  fun aLightSendsTheOppositeOfWhatItShows() {
    val off = action("light.beacon").stateOf(Vehicle(lights = Lights(beaconLight = false)))!!
    assertFalse(off.on)
    assertEquals(StateWord.OFF, off.word)
    assertEquals(ClientMessage.SetLight(LightTarget.BEACON, true), off.command)

    val on = action("light.beacon").stateOf(Vehicle(lights = Lights(beaconLight = true)))!!
    assertEquals(ClientMessage.SetLight(LightTarget.BEACON, false), on.command)
  }

  @Test
  fun aVehicleWithoutTheLampHasNoButton() {
    // Lights reported, but no beacon on this machine: unavailable, not "off".
    assertNull(action("light.beacon").stateOf(Vehicle(lights = Lights())))
    assertNull(action("light.lowBeam").stateOf(Vehicle()))
  }

  @Test
  fun hazardClearsTheIndicatorsWhenOn() {
    val on = action("hazard").stateOf(Vehicle(lights = Lights(indicator = Indicator(hazard = true))))!!
    assertEquals(ClientMessage.SetTurnLight(TurnLightState.OFF), on.command)
    val off = action("hazard").stateOf(Vehicle(lights = Lights(indicator = Indicator())))!!
    assertEquals(ClientMessage.SetTurnLight(TurnLightState.HAZARD), off.command)
  }

  @Test
  fun aCrankingEngineAsksForOnAgain() {
    // Cranking is not running. A second tap during the crank must not stop the engine just started.
    val state = action("engine").stateOf(Vehicle(motor = Motor(state = MotorState.STARTING)))!!
    assertFalse(state.on)
    assertEquals(ClientMessage.SetMotorState(on = true), state.command)
  }

  @Test
  fun speedSyncIsInertWhereTheModWouldRefuse() {
    val state = action("speedSync").stateOf(Vehicle(speedSync = SpeedSync(active = false, available = false)))!!
    assertNull(state.command)
    val ready = action("speedSync").stateOf(Vehicle(speedSync = SpeedSync(active = false, available = true)))!!
    assertEquals(ClientMessage.SetSpeedSync(active = true), ready.command)
  }

  @Test
  fun cruiseTogglesByEnableAndDisable() {
    val state = action("cruise").stateOf(Vehicle(cruiseControl = CruiseControl(active = true)))!!
    assertEquals(ClientMessage.SetCruiseControl(CruiseAction.DISABLE), state.command)
  }

  @Test
  fun autoDriveStartsOnlyWithADestinationAndANetwork() {
    val network = AutoDriveData(destinations = listOf(AutoDriveDestination(id = 1, name = "Farm")))
    val noTarget = Vehicle(autoDrive = AutoDrive(active = false))
    assertNull(action("autoDrive").stateOf(noTarget, network)!!.command)

    val withTarget = Vehicle(autoDrive = AutoDrive(active = false, target = AutoDriveTarget(1, "Farm")))
    assertNull(action("autoDrive").stateOf(withTarget, null)!!.command)
    assertEquals(ClientMessage.SetAutoDriveActive(true), action("autoDrive").stateOf(withTarget, network)!!.command)

    // Stopping is never refused.
    val running = Vehicle(autoDrive = AutoDrive(active = true))
    assertEquals(ClientMessage.SetAutoDriveActive(false), action("autoDrive").stateOf(running, null)!!.command)
  }

  @Test
  fun aMixedRigGoesToRoad() {
    val vehicle =
      Vehicle(
        tirePressure = TirePressure(mode = TireMode.FIELD),
        implement = listOf(Implement(position = "BACK", tirePressure = TirePressure(mode = TireMode.ROAD))),
      )
    val state = action("tirePressure").stateOf(vehicle)!!
    assertEquals(StateWord.MIXED, state.word)
    assertEquals(ClientMessage.SetTirePressure(TirePressureScope.ALL, road = true), state.command)
  }

  @Test
  fun foldSendsFoldedTrueFromUnfolded() {
    // `on` is *folded* on the wire, which is easy to get backwards.
    val vehicle = Vehicle(implement = listOf(Implement(position = "BACK", foldable = FoldableState.EXTENDED)))
    val state = action("fold.rear").stateOf(vehicle)!!
    assertTrue(state.on)
    assertEquals(StateWord.UNFOLDED, state.word)
    assertEquals(ClientMessage.SetFolded(ControlTarget.BACK, on = true), state.command)
  }

  @Test
  fun aPositionWithNothingHitchedIsUnavailable() {
    val vehicle = Vehicle(implement = listOf(Implement(position = "BACK", lowered = true)))
    assertNull(action("lower.front").stateOf(vehicle))
    assertEquals(ClientMessage.SetLowered(ControlTarget.BACK, false), action("lower.rear").stateOf(vehicle)!!.command)
  }

  @Test
  fun theSelectionIsFoundDownTheChain() {
    val vehicle =
      Vehicle(
        isTurnedOn = false,
        implement =
        listOf(
          Implement(
            position = "BACK",
            isTurnedOn = false,
            implement = listOf(Implement(isTurnedOn = true, selection = Selection(selected = true))),
          ),
        ),
      )
    val state = action("power.selected").stateOf(vehicle)!!
    assertTrue(state.on)
    assertEquals(ClientMessage.SetActivated(ControlTarget.SELECTED, false), state.command)
  }

  @Test
  fun theSelectionCanBeTheVehicleItself() {
    val vehicle = Vehicle(isTurnedOn = true, selection = Selection(selected = true))
    assertTrue(action("power.selected").stateOf(vehicle)!!.on)
    // Nothing selected anywhere: nothing to show.
    assertNull(action("power.selected").stateOf(Vehicle(isTurnedOn = true)))
  }
}
