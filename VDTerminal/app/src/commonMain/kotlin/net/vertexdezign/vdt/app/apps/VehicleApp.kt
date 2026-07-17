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
import net.vertexdezign.vdt.app.widgets.EngineWidget
import net.vertexdezign.vdt.app.widgets.ImplementsWidget
import net.vertexdezign.vdt.app.widgets.LightingWidget
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * The vehicle itself: owns everything about the machine you're driving. Provides the engine,
 * implements and lighting tiles, and a full page that puts all three side by side for a focused
 * look at the current vehicle.
 */
object VehicleApp : VdtApp {
  override val id = "vehicle"
  override val title = "Vehicle"
  override val icon: ImageVector = Icons.Filled.Agriculture
  override val widgets: List<Widget> = listOf(EngineWidget, ImplementsWidget, LightingWidget)

  @Composable
  override fun FullPage(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      EngineWidget.Content(Modifier.weight(1f).fillMaxHeight())
      ImplementsWidget.Content(Modifier.weight(1f).fillMaxHeight())
      LightingWidget.Content(Modifier.weight(1f).fillMaxHeight())
    }
  }
}
