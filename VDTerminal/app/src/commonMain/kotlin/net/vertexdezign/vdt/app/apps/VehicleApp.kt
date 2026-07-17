package net.vertexdezign.vdt.app.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.alerts.AlertRule
import net.vertexdezign.vdt.app.alerts.AlertSeverity
import net.vertexdezign.vdt.app.widgets.EngineWidget
import net.vertexdezign.vdt.app.widgets.ImplementsWidget
import net.vertexdezign.vdt.app.widgets.LightingWidget
import net.vertexdezign.vdt.app.widgets.Widget
import net.vertexdezign.vdt.model.VdtData

/**
 * The vehicle itself: owns everything about the machine you're driving. Provides the engine,
 * implements and lighting tiles, and a full page that puts all three side by side for a focused
 * look at the current vehicle.
 */
object VehicleApp : VdtApp {
  /** Below this the low-fuel alert fires… */
  private const val FUEL_ENTER_PERCENT = 10

  /** …and only above this does it re-arm, so sloshing around the threshold can't flap it. */
  private const val FUEL_EXIT_PERCENT = 15

  const val LOW_FUEL_ALERT_ID = "vehicle.fuel.low"

  override val id = "vehicle"
  override val title = "Vehicle"
  override val icon: ImageVector = Icons.Filled.Agriculture
  override val widgets: List<Widget> = listOf(EngineWidget, ImplementsWidget, LightingWidget)

  override val alerts: List<AlertRule> =
    listOf(
      AlertRule(
        id = LOW_FUEL_ALERT_ID,
        severity = AlertSeverity.Warning,
        title = "LOW FUEL",
        message = { "Fuel at ${it.fuelPercent}%" },
        enter = { data -> data.fuelPercent?.let { it <= FUEL_ENTER_PERCENT } == true },
        exit = { data -> data.fuelPercent?.let { it > FUEL_EXIT_PERCENT } == true },
      ),
    )

  @Composable
  override fun FullPage(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      EngineWidget.Content(Modifier.weight(1f).fillMaxHeight())
      ImplementsWidget.Content(Modifier.weight(1f).fillMaxHeight())
      LightingWidget.Content(Modifier.weight(1f).fillMaxHeight())
    }
  }
}

/** Null when on foot or the vehicle reports no fuel unit — the alert then holds its state. */
private val VdtData.fuelPercent: Int?
  get() =
    vehicle
      ?.motor
      ?.fillUnits
      ?.fuel
      ?.fillLevelPercentage
