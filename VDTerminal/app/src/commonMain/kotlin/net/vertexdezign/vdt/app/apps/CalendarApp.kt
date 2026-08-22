package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.CalendarPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.WeatherWidget
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * The Calendar app: the game's own crop calendar — which periods each crop may be sown and harvested
 * in — over the weather forecast, plus the search and the two "now" filters the in-game screen makes
 * you scan for. Base-game data, so it is always available (the panel renders its own waiting states).
 *
 * The forecast also goes out as a placeable [WeatherWidget]: it is the half you want glanceable while
 * driving. The grid is not — it is a look-it-up screen, and it needs the width.
 */
object CalendarApp : VdtApp {
  override val id = "calendar"
  override val title = "Calendar"
  override val icon: ImageVector = Icons.Filled.CalendarMonth
  override val widgets: List<Widget> = listOf(WeatherWidget)

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val calendar by store.cropCalendar.collectAsState()
    val weather by store.weather.collectAsState()
    CalendarPanel(calendar, weather, modifier)
  }
}
