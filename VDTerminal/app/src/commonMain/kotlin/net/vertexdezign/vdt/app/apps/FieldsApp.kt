package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.LocalNavigator
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.panels.FieldsPanel
import net.vertexdezign.vdt.app.state.LayerSubscriptions
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.state.MapFocus

/**
 * The plane the field breakdown is counted off, mirroring the server's `FIELD_STATUS_LAYER_ID`.
 *
 * Held as a subscription for as long as this screen is composed, because the mod sweeps only what
 * something is subscribed to: without this, the histogram would be of a raster nobody is refreshing.
 * The consequence is worth designing for rather than hiding — opening the app makes the mod start
 * sweeping, and a full sweep takes seconds, so the first breakdown is *late*, not wrong. The panel
 * says "sampling…" for it.
 */
private const val GROWTH_LAYER = "growth"

/**
 * The Fields app: what is on the farm's land, and what each field is asking for (issue #131).
 *
 * A separate app rather than a mode of the Map, because what it adds is sorting and filtering
 * *across* fields — which the map's per-field popup cannot do. The two cross-link: a row hands its
 * position to [MapFocus] and opens the map, the same way the fleet list does.
 *
 * Available whenever the map channel has fields. This is core data, not an optional mod, so it does
 * not use the null-channel convention; the panel renders its own waiting state until the map arrives.
 */
object FieldsApp : VdtApp {
  override val id = "fields"
  override val title = "Fields"
  override val icon: ImageVector = Icons.Filled.Grass

  @Composable
  override fun isAvailable(): Boolean {
    val map by LocalVdtStore.current.mapData.collectAsState()
    return map?.fields?.isNotEmpty() == true
  }

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val map by store.mapData.collectAsState()
    val info by store.fieldInfo.collectAsState()
    val status by store.fieldStatus.collectAsState()
    val missions by store.missions.collectAsState()
    val telemetry by store.telemetry.collectAsState()
    val navigate = LocalNavigator.current

    // The subscription is this screen's, keyed by a token of its own so two of them (a page and the
    // launcher's full view) count as two subscribers and the first to close does not cancel the
    // other's sweep. Registered and dropped in the same effect: a token left behind would keep the
    // mod sweeping a plane nobody is looking at for as long as the tab is open.
    val token = remember { Any() }
    val send by rememberUpdatedState(store.onCommand)
    DisposableEffect(token) {
      send(ClientMessage.SetMapLayers(LayerSubscriptions.union(token, listOf(GROWTH_LAYER))))
      onDispose { send(ClientMessage.SetMapLayers(LayerSubscriptions.union(token, null))) }
    }

    FieldsPanel(
      map = map,
      info = info,
      status = status,
      missions = missions,
      playerFarmId = telemetry?.environment?.pda?.player?.farmId,
      modifier = modifier,
    ) { x, z ->
      MapFocus.request(x, z)
      navigate(Screen.OpenApp(MapApp.id))
    }
  }
}
