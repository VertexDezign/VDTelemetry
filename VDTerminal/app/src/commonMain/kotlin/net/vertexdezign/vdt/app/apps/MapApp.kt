package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.widgets.MapWidget
import net.vertexdezign.vdt.app.widgets.Widget

/** The PDA map. Full page is the map filling the screen; also provides the map tile. */
object MapApp : VdtApp {
  override val id = "map"
  override val title = "Map"
  override val icon: ImageVector = Icons.Filled.Map
  override val widgets: List<Widget> = listOf(MapWidget)

  @Composable
  override fun FullPage(modifier: Modifier) = MapWidget.Content(modifier)
}
