package net.vertexdezign.vdt.app.state

import androidx.compose.runtime.staticCompositionLocalOf
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import net.vertexdezign.vdt.ChannelStatsData
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.WakeLockStatus
import net.vertexdezign.vdt.app.alerts.AlertEngine
import net.vertexdezign.vdt.app.net.ConnectionState
import net.vertexdezign.vdt.app.pages.PageStore
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldStatuses
import net.vertexdezign.vdt.model.FinanceData
import net.vertexdezign.vdt.model.FleetData
import net.vertexdezign.vdt.model.GpsCourseData
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.InvoicesData
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapLayersInfo
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.MissionsData
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.StorageData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData
import net.vertexdezign.vdt.model.WeatherForecastData

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
  /** The steering assist's guidance lines for the field being driven; null when there is no course. */
  val gpsCourse: StateFlow<GpsCourseData?>,
  /** Ground-layer legends (crops/growth/soil); the raster PNG is fetched from [mapLayerUrl] on demand. */
  val mapLayers: StateFlow<MapLayersInfo?>,
  val fieldInfo: StateFlow<FieldInfoData?>,
  /**
   * What each field is in and what condition it is in, counted off the growth and soil rasters rather
   * than sampled at its centre the way [fieldInfo] is. Derived by the server, so null is "no raster
   * yet" — including "nobody is subscribed to those planes", which is why a screen that reads this
   * registers with [LayerSubscriptions] while it is open.
   */
  val fieldStatus: StateFlow<FieldStatuses?>,
  val production: StateFlow<ProductionData?>,
  val storage: StateFlow<StorageData?>,
  val husbandry: StateFlow<HusbandriesData?>,
  /**
   * The map's price board — what every station pays and charges, and the twelve-month curve behind
   * each commodity. Not farm-scoped and carrying no fill levels: valuing stock against it is the
   * Market app's join with the three channels above.
   */
  val prices: StateFlow<PricesData?>,
  /** Every machine the farm owns, with its condition and ADS's record; null when absent. */
  val fleet: StateFlow<FleetData?>,
  /** The farm's contracts — on offer, running, finished; null when the channel is absent. */
  val missions: StateFlow<MissionsData?>,
  /** The farm's books — balance, loan, the monthly table, the money log; null when absent. */
  val finance: StateFlow<FinanceData?>,
  /**
   * Billing between farms (FS25_Invoices). Null means the **mod is not installed** — distinct from an
   * installed mod with nothing to show, which sends an empty list.
   */
  val invoices: StateFlow<InvoicesData?>,
  /**
   * Which of the twelve periods each crop may be sown and harvested in — the game's own
   * Anbaukalender. Null when the channel is absent.
   */
  val cropCalendar: StateFlow<CropCalendarData?>,
  /** The forecast: now, twelve two-hourly steps, six days out; null when the channel is absent. */
  val weather: StateFlow<WeatherForecastData?>,
  /** Server-measured observed cadence of each channel file (diagnostics app); null until first frame. */
  val channelStats: StateFlow<ChannelStatsData?>,
  val wakeLock: StateFlow<WakeLockStatus>,
  val mapUrl: String,
  /** Base URL for ground-layer raster PNGs; the map widget appends `/{layerId}` (see [mapLayers]). */
  val mapLayerUrl: String,
  /**
   * POST here to clear the worked-coverage mask. The one ground layer the server accumulates itself
   * rather than reading from the mod, so wiping it is an HTTP call and not a [ClientMessage] — there
   * is nothing in the game to tell.
   */
  val coverageResetUrl: String,
  val settings: Settings,
  /** The user's pages (created/edited at runtime, persisted); see [PageStore]. */
  val pages: PageStore,
  /** Shell-wide alert state (banners, sticky active set, session history); see [AlertEngine]. */
  val alerts: AlertEngine,
  /** Screens pinned to the bottom bar; see [FavouritesStore]. */
  val favourites: FavouritesStore,
  /** Whether this device is a chrome-free display, and what it is pinned to; see [DisplayStore]. */
  val display: DisplayStore,
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
