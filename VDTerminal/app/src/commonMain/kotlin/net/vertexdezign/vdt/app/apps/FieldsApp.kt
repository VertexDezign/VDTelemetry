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
import net.vertexdezign.vdt.app.alerts.AlertInputs
import net.vertexdezign.vdt.app.alerts.AlertRule
import net.vertexdezign.vdt.app.alerts.AlertSeverity
import net.vertexdezign.vdt.app.alerts.KeyedAlertRule
import net.vertexdezign.vdt.app.panels.FieldTaskType
import net.vertexdezign.vdt.app.panels.FieldsPanel
import net.vertexdezign.vdt.app.panels.fieldRows
import net.vertexdezign.vdt.app.panels.fieldWork
import net.vertexdezign.vdt.app.state.LayerSubscriptions
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.state.MapFocus
import net.vertexdezign.vdt.app.widgets.FieldsWidget
import net.vertexdezign.vdt.app.widgets.Widget

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
  const val WITHERED_ALERT_ID = "fields.withered"
  const val HARVEST_ALERT_ID = "fields.harvest"

  override val id = "fields"
  override val title = "Fields"
  override val icon: ImageVector = Icons.Filled.Grass
  override val widgets: List<Widget> = listOf(FieldsWidget)

  /**
   * Two rules, both keyed per field so a batch ripening together is one alert rather than twelve, and
   * both scoped to **own** fields — the map is mostly somebody else's land, and an alert about it is
   * noise by construction.
   *
   * Withered is a Warning: the crop is already lost and the only thing left to decide is when to
   * clear it. Ready is Info, which stays silent by design — it is good news to act on when convenient,
   * not something to chime at a driver mid-row.
   *
   * Both freeze while the map or the field channel is absent (null activeEntities), so a channel
   * switched off never reads as "nothing is withered any more".
   */
  override val alerts: List<AlertRule> =
    listOf(
      KeyedAlertRule(
        id = WITHERED_ALERT_ID,
        severity = AlertSeverity.Warning,
        title = "CROP WITHERED",
        activeEntities = { inputs -> ownFieldsWhere(inputs, FieldTaskType.CULTIVATE) },
        message = ::fieldAlertMessage,
      ),
      KeyedAlertRule(
        id = HARVEST_ALERT_ID,
        severity = AlertSeverity.Info,
        title = "READY TO HARVEST",
        activeEntities = { inputs -> ownFieldsWhere(inputs, FieldTaskType.HARVEST) },
        message = ::fieldAlertMessage,
      ),
    )

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
    val tasks by store.taskList.collectAsState()
    val rotation by store.cropRotation.collectAsState()
    val calendar by store.cropCalendar.collectAsState()
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
      tasks = tasks,
      rotation = rotation,
      calendar = calendar,
      playerFarmId = telemetry?.environment?.pda?.player?.farmId,
      modifier = modifier,
      onShowOnMap = { x, z ->
        MapFocus.request(x, z)
        navigate(Screen.OpenApp(MapApp.id))
      },
      onCommand = store.onCommand,
    )
  }
}

/**
 * The player's own fields currently asking for [type], as `id -> label`, or null to freeze the rule.
 *
 * Null on an absent map or field channel rather than an empty map: "I cannot see the fields" is not
 * "nothing is wrong with them", and clearing an alert on missing data is how a dashboard tells its
 * worst kind of lie.
 */
private fun ownFieldsWhere(inputs: AlertInputs, type: FieldTaskType): Map<String, String>? {
  val map = inputs.mapData ?: return null
  val info = inputs.fieldInfo ?: return null
  val farmId = inputs.telemetry?.environment?.pda?.player?.farmId ?: return null
  return fieldRows(map, info, inputs.fieldStatus, null, null, farmId)
    .filter { it.owned && type in fieldWork(it) }
    .associate { it.id.toString() to "Field ${it.label}" }
}

/** One field by name, several as a count and the first few — the same shape the task alert uses. */
private fun fieldAlertMessage(labels: List<String>): String = if (labels.size == 1) {
  labels.single()
} else {
  "${labels.size} fields: " + labels.take(3).joinToString(", ") + if (labels.size > 3) ", …" else ""
}
