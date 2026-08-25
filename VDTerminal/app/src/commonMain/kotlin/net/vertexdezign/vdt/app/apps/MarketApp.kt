package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.MarketPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore

/**
 * The Market app: the map's price board, and the farm's own stock priced against it (issue #118).
 *
 * The one app that reads five channels at once, because the question it answers spans them: the board
 * says what things are worth, and the holdings are spread over storage, production and the animal
 * pens. Base-game data throughout, so it is always available — each tab renders its own waiting state
 * for whichever channel has not arrived yet.
 *
 * The crop calendar is in there for one thing: the game's own month labels, which cannot be derived
 * from a period number on a southern-hemisphere map.
 */
object MarketApp : VdtApp {
  override val id = "market"
  override val title = "Market"
  override val icon: ImageVector = Icons.Filled.Storefront

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val prices by store.prices.collectAsState()
    val storage by store.storage.collectAsState()
    val production by store.production.collectAsState()
    val husbandry by store.husbandry.collectAsState()
    val calendar by store.cropCalendar.collectAsState()
    MarketPanel(prices, storage, production, husbandry, calendar, modifier)
  }
}
