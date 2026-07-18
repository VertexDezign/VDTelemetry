package net.vertexdezign.vdt.app.state

import androidx.compose.runtime.staticCompositionLocalOf
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.WakeLockStatus
import net.vertexdezign.vdt.app.alerts.AlertEngine
import net.vertexdezign.vdt.app.net.ConnectionState
import net.vertexdezign.vdt.app.pages.PageStore
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.ProductionsData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData

/**
 * Ambient container for everything a screen or widget might need: the live telemetry channels (as
 * [StateFlow]s, so a widget can `collectAsState()` only the one it renders and stay out of unrelated
 * ticks) plus the stable environment/actions (map URL, persisted [Settings], the command sink).
 *
 * Its identity is stable — built once in `main()` and provided through [LocalVdtStore] — which is why
 * a `staticCompositionLocalOf` is correct: reading the local never triggers recomposition; the
 * fine-grained updates come from the individual flows.
 */
class VdtStore(
  val telemetry: StateFlow<VdtData?>,
  val connection: StateFlow<ConnectionState>,
  val sampleIntervalMs: StateFlow<Int>,
  val taskList: StateFlow<TaskListData?>,
  val cropRotation: StateFlow<CropRotationData?>,
  val mapData: StateFlow<MapData?>,
  val mapVehicles: StateFlow<MapVehiclesData?>,
  val fieldInfo: StateFlow<FieldInfoData?>,
  val productions: StateFlow<ProductionsData?>,
  val wakeLock: StateFlow<WakeLockStatus>,
  val mapUrl: String,
  val settings: Settings,
  /** The user's pages (created/edited at runtime, persisted); see [PageStore]. */
  val pages: PageStore,
  /** Shell-wide alert state (banners + sticky active set); see [AlertEngine]. */
  val alerts: AlertEngine,
  val onToggleWakeLock: () -> Unit,
  val onCommand: (ClientMessage) -> Unit,
)

/**
 * The ambient [VdtStore]; provided at the app root. Reading it outside that scope is a bug.
 *
 * Intentionally a CompositionLocal: the store is a single app-wide, root-provided dependency (the
 * data layer every screen/widget draws from), which is exactly the sanctioned use. It is allow-listed
 * for the compose ktlint rule via `compose_allowed_composition_locals` in the build config.
 */
val LocalVdtStore = staticCompositionLocalOf<VdtStore> { error("VdtStore not provided") }
