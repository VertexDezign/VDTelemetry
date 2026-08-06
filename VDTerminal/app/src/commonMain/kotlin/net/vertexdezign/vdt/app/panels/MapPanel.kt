package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SectionStrip
import net.vertexdezign.vdt.app.components.boomOf
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.app.widgets.WidgetSettings
import net.vertexdezign.vdt.model.COVERAGE_LAYER_ID
import net.vertexdezign.vdt.model.FieldCropRotation
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldInfoEntry
import net.vertexdezign.vdt.model.GpsCourseData
import net.vertexdezign.vdt.model.GpsCourseState
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapFarm
import net.vertexdezign.vdt.model.MapField
import net.vertexdezign.vdt.model.MapLayerInfo
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.MapLayersInfo
import net.vertexdezign.vdt.model.MapVehicle
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.Mission
import net.vertexdezign.vdt.model.MissionsData
import net.vertexdezign.vdt.model.Pda
import net.vertexdezign.vdt.model.Player
import net.vertexdezign.vdt.model.SweptArea
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.activeWorkAreas
import org.jetbrains.skia.Image
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.TimeSource

private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 16f

// Above this zoom the overlay shows secondary text (field area, POI names) — below it only the
// always-on field numbers and POI dots, so a zoomed-out map doesn't drown in labels.
private const val DETAIL_ZOOM = 2f

// Max on-screen distance from a field's number label at which a tap opens that field's info popup.
private val FIELD_TAP_RADIUS_DP = 20.dp

// Persistence names, scoped per placed tile by WidgetSettings — two maps on one page each keep their
// own zoom, filters and ground layer. Each is read and written in separate places, so name them once.

/**
 * The ground a contract marker covers, in metres — the game's own field-contract circle is 50 m
 * (`AbstractFieldMissionHotspot`), and this is the knob for how big the marker reads: the drawn
 * radius is this, converted through the map's own scale and then damped by √zoom.
 */
private const val MISSION_MARKER_RADIUS_M = 50f

private const val KEY_ZOOM = "zoom"
private const val KEY_AUTO_CENTER = "autoCenter"
private const val KEY_SHOW_FIELDS = "showFields"
private const val KEY_SHOW_COURSE = "showCourse"
private const val KEY_SHOW_MISSIONS = "showMissions"
private const val KEY_COURSE_UP = "courseUp"
private const val KEY_COURSE_NEARBY = "courseNearby"
private const val KEY_POI_CATS = "poiCats"
private const val KEY_VEH_STATES = "vehStates"
private const val KEY_GROUND_LAYER = "groundLayer"

/**
 * Where the vehicle sits down the screen in course-up, as a fraction of the map's side. Two thirds
 * down: on a run screen the map is spent on the ground you are about to drive over, not the strip
 * behind you, and this is roughly where the reference terminals put the machine.
 */
private const val COURSE_UP_ANCHOR_Y = 0.66f

/** The "no overlay" selection — persisted like a layer id, and the one that subscribes to nothing. */
private const val NO_GROUND_LAYER = "none"

/** Ground-layer PNG fetch: total attempts, and the pause before the retry. See the fetch effect. */
private const val LAYER_FETCH_ATTEMPTS = 2
private const val LAYER_FETCH_RETRY_MS = 750L

/** A non-2xx from `/api/map-layer`; carries the status so the fetch can tell a 409 from the rest. */
private class LayerFetchFailed(val status: HttpStatusCode) : Exception("map-layer fetch failed: $status")

/**
 * Decoded map images, held outside composition and keyed by request URL + PDA filename.
 *
 * The vehicle and farm pages each host their own [MapPanel], so entering or leaving a vehicle
 * disposes one panel and composes the other from scratch — `remember`ed state included. Without a
 * cache that outlives the panel, the new one starts with no bitmap and the map shows blank while it
 * re-fetches and re-decodes. A save has exactly one map, so this holds one entry (wasm is
 * single-threaded; no synchronization needed).
 */
private val mapImageCache = mutableMapOf<String, ImageBitmap>()

/**
 * Last-rendered ground-layer PNG, keyed by `"$mapLayerUrl/$id|$version"`. Separate from
 * [mapImageCache] on purpose: the base map has one entry for the life of a save, but a layer key
 * churns every sweep of that plane (a new [MapLayerInfo.version]), so folding it into the same map would leak
 * unboundedly across a session instead of just holding the one most-recently-shown layer.
 */
private var layerImageCache: Pair<String, ImageBitmap>? = null

/**
 * Every live map panel's ground-layer selection, keyed by panel instance, so what the app tells the
 * server is the union across them. A dashboard can hold two map widgets showing different planes, and
 * one of them leaving composition must not report "nobody is looking" while the other still is.
 *
 * Module-level for the same reason the caches above are: it has to outlive any one panel. Only touched
 * from the composition, which is single-threaded, so no synchronization is needed.
 */
private val liveLayerSelections = mutableMapOf<Any, List<String>>()

/** Record (or, with a null selection, drop) one panel's choice and return the union to report. */
private fun layerUnion(panel: Any, selection: List<String>?): List<String> {
  if (selection == null) liveLayerSelections.remove(panel) else liveLayerSelections[panel] = selection
  return liveLayerSelections.values.flatten().distinct().sorted()
}

/**
 * Shared with the caches above: outliving the panel is the whole point, so it can't be `remember`ed.
 *
 * [HttpTimeout] is installed **unconfigured** — no default deadline — purely so single requests can
 * set their own. The images this fetches have no business having one: a 2048² coverage raster over a
 * slow link is legitimately slow, and a client-wide timeout would turn that into a blank overlay.
 * Only the coverage reset opts in ([COVERAGE_RESET_TIMEOUT_MS]), because it is the one request a
 * person is waiting on.
 */
private val mapImageClient by lazy { HttpClient { install(HttpTimeout) } }

/**
 * How long to wait for the coverage reset before calling it failed.
 *
 * The browser's fetch has no deadline of its own, so without this a request that never answers — the
 * server gone, a proxy holding the connection — leaves the row armed and silent forever, and the
 * in-flight guard below would then never open again. Generous for what is a bodyless POST to a server
 * on the same machine as the game.
 */
private const val COVERAGE_RESET_TIMEOUT_MS = 10_000L

/**
 * Map panel: loads the PDA map image from the server, supports pan/zoom, draws the player marker
 * (position + heading), and auto-centers on the player until the user pans. On top of the image it
 * overlays the map channels' data: field outlines + number labels ([MapData]), POI dots, and
 * vehicle markers ([MapVehiclesData]), filtered per category/state through the filter popover
 * (Tune button), which also hosts a field/POI search that pans the map to a hit, plus an optional
 * ground-layer raster ([MapLayersInfo]: the planes the map offers, single-select, each with its own legend).
 * Zoom, auto-center, the filter selections, and the selected ground layer are persisted. Port of
 * the React `MapPanel` (no map library — a single custom composable).
 */
@Composable
fun MapPanel(
  mapUrl: String,
  pda: Pda?,
  heading: Int,
  sampleIntervalMs: Int,
  settings: WidgetSettings,
  modifier: Modifier = Modifier,
  mapData: MapData? = null,
  mapVehicles: MapVehiclesData? = null,
  fieldInfo: FieldInfoData? = null,
  mapLayerUrl: String = "",
  /** Where to POST to clear the server's worked-coverage mask; blank hides the control. */
  coverageResetUrl: String = "",
  mapLayers: MapLayersInfo? = null,
  onShowLayers: (List<String>) -> Unit = {},
  vehicle: Vehicle? = null,
  showGuidance: Boolean = false,
  showSections: Boolean = false,
  onCommand: (ClientMessage) -> Unit = {},
  gpsCourse: GpsCourseData? = null,
  /** The farm's contracts, drawn as markers and as a tint on the field each one is on. */
  missions: MissionsData? = null,
) {
  var scale by remember { mutableStateOf(settings.getFloat(KEY_ZOOM, 1f)) }
  var autoCenter by remember { mutableStateOf(settings.getBoolean(KEY_AUTO_CENTER, true)) }
  var showFields by remember { mutableStateOf(settings.getBoolean(KEY_SHOW_FIELDS, true)) }
  var showCourse by remember { mutableStateOf(settings.getBoolean(KEY_SHOW_COURSE, true)) }
  var showMissions by remember { mutableStateOf(settings.getBoolean(KEY_SHOW_MISSIONS, true)) }
  // How much of the course to draw: 0 is the whole field, N is the lines within N swaths of the rig.
  var courseNearby by remember { mutableStateOf(settings.getInt(KEY_COURSE_NEARBY, 0)) }
  // A mode you flip while driving, not a layout decision — so a header toggle beside auto-center
  // rather than widget config, persisted per placed tile like the zoom and the filters.
  var courseUp by remember { mutableStateOf(settings.getBoolean(KEY_COURSE_UP, false)) }
  // Which individual contracts are drawn, holding only what the user has *said* — everything else
  // follows [missionShownByDefault]. Deliberately not persisted like the filters around it: the key
  // is the mission id, which is the network object id, and a later session hands that id to whatever
  // object now carries it (the same trap that makes the mod resolve a command by walking the list).
  // A stored "hide 648" would eventually hide a contract nobody hid. Kept for the session instead,
  // and pruned as the board changes, which is also what lets the default reassert itself.
  var missionChoices by remember { mutableStateOf(emptyMap<Int, Boolean>()) }
  var poiCats by remember { mutableStateOf(loadFilterSet(settings, KEY_POI_CATS, PoiCategories)) }
  var vehStates by remember { mutableStateOf(loadFilterSet(settings, KEY_VEH_STATES, VehicleStates)) }
  var groundLayer by remember { mutableStateOf(settings.getString(KEY_GROUND_LAYER, NO_GROUND_LAYER)) }
  var filterOpen by remember { mutableStateOf(false) }
  // The field whose info popup is open (its id / farmland number), or null when none. Set by tapping
  // a field label, cleared by tapping empty map or the popup's close button.
  var selectedFieldId by remember { mutableStateOf<Int?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  // Normalized position of the last search hit; drawn as a ring until the query is cleared.
  var highlight by remember { mutableStateOf<Offset?>(null) }
  var dragOffset by remember { mutableStateOf(Offset.Zero) }
  var sidePx by remember { mutableFloatStateOf(0f) }
  // How much room the section strip is taking along the bottom edge, so the ground-layer legend in the
  // same corner clears it. Measured rather than assumed: the strip's height follows the text scale.
  var sectionStripHeight by remember { mutableStateOf(0.dp) }
  val player = pda?.player

  // The live end of the coverage layer. Fed only while that layer is the one on screen: it is the
  // only place it would be drawn, and the sweep it keeps is state about a pass nobody is watching
  // otherwise. Selecting the layer therefore starts an empty trail, which the server's raster fills
  // in within a publish interval.
  val coverageTrail = remember { CoverageTrail() }
  val showCoverage = groundLayer == COVERAGE_LAYER_ID
  if (showCoverage) {
    // Keyed on the sample rather than on a clock, so the trail advances exactly once per telemetry
    // tick — which is also the cadence WorkSweep's staleness guard is written against.
    LaunchedEffect(vehicle) {
      coverageTrail.advance(vehicle, mapData?.terrainSize ?: 0f, trailClock.elapsedNow().inWholeMilliseconds)
    }
  }
  // Clearing on deselect is what stops a trail from an earlier visit reappearing over ground the
  // raster has long since recorded, and what makes the reset button clear the map rather than half of it.
  LaunchedEffect(showCoverage) { if (!showCoverage) coverageTrail.clear() }

  // Seed from the cache so a panel composed after a page switch paints the map on its first frame.
  val cacheKey = if (pda?.filename.isNullOrBlank()) null else "$mapUrl|${pda.filename}"
  var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let(mapImageCache::get)) }

  // Smooth the compass heading toward each new value along the *shortest* arc: accumulate an
  // unwrapped angle so e.g. 350°→10° rotates +20°, not -340° the long way round. rotate() takes
  // any float, so the running total never needs re-wrapping.
  var targetHeading by remember { mutableFloatStateOf(heading.toFloat()) }
  var lastHeading by remember { mutableIntStateOf(heading) }
  LaunchedEffect(heading) {
    val delta = ((heading - lastHeading + 540) % 360) - 180 // shortest signed step in (-180, 180]
    targetHeading += delta
    lastHeading = heading
  }
  val animHeading by animateFloatAsState(
    targetValue = targetHeading,
    animationSpec = tween(durationMillis = sampleIntervalMs, easing = LinearEasing),
    label = "heading",
  )

  // Where the vehicle sits on screen, and so also the point the map turns about in course-up: the
  // pivot is the one place rotation leaves alone, which is what keeps the machine still while the
  // world turns under it.
  fun anchorFor(boxSide: Float): Offset =
    if (courseUp) Offset(boxSide / 2f, boxSide * COURSE_UP_ANCHOR_Y) else Offset(boxSide / 2f, boxSide / 2f)

  // Rotate the map so the direction of travel points up. The smoothed heading, not the raw one, or
  // the whole map steps 10 times a second.
  fun rotationFor(): Float = if (courseUp) -animHeading else 0f

  // Zoom by [factor] while keeping the given focal point (screen coords relative to the map's
  // top-left) pinned on screen. Used by the header +/- buttons with focal = viewport centre.
  fun zoomAround(factor: Float, focalX: Float, focalY: Float) {
    val anchor = anchorFor(sidePx)
    val base = if (autoCenter) anchorOffset(sidePx, player, scale, anchor) else dragOffset
    val newScale = (scale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    // The focal arrives in screen space, which in course-up is turned; the offset it feeds is not.
    val focal = MapProjection(sidePx, scale, base, rotationFor(), anchor).unrotate(Offset(focalX, focalY))
    dragOffset = zoomedOffset(base, focal, scale, newScale)
    scale = newScale
  }

  // Put a search hit (normalized coords) where the vehicle normally sits, zoomed in far enough that
  // its label shows, and ring-highlight it. Panning to a target naturally ends auto-centering.
  // Anchored rather than centred because the anchor is the rotation pivot: in course-up it is the one
  // screen point that means the same thing before and after the map turns.
  fun focusOn(norm: Offset) {
    val newScale = scale.coerceAtLeast(DETAIL_ZOOM)
    scale = newScale
    autoCenter = false
    dragOffset = MapProjection.anchoredAt(norm, anchorFor(sidePx), sidePx, newScale)
    highlight = norm
  }

  LaunchedEffect(cacheKey) {
    if (cacheKey == null || bitmap != null) return@LaunchedEffect // already cached, or no PDA image
    runCatching {
      val bytes = mapImageClient.get(mapUrl).readRawBytes()
      Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.onSuccess {
      mapImageCache[cacheKey] = it
      bitmap = it
    }
  }
  // Only the contracts this farm has taken on: the board's offers are shopping, and the map is for
  // work. Computed here rather than in the overlay because the filter list needs the same set.
  val accepted = remember(missions) { (missions?.missions ?: emptyList()).filter { it.own } }

  // Drop choices about contracts that have left the board, so a collected one takes its row with it —
  // and so an id the game later reuses arrives with nothing stale attached to it.
  LaunchedEffect(accepted) {
    val live = accepted.mapTo(mutableSetOf()) { it.id }
    if (missionChoices.keys.any { it !in live }) missionChoices = missionChoices.filterKeys { it in live }
  }

  LaunchedEffect(scale) { settings.putFloat(KEY_ZOOM, scale) }
  LaunchedEffect(autoCenter) { settings.putBoolean(KEY_AUTO_CENTER, autoCenter) }
  LaunchedEffect(showFields) { settings.putBoolean(KEY_SHOW_FIELDS, showFields) }
  LaunchedEffect(showCourse) { settings.putBoolean(KEY_SHOW_COURSE, showCourse) }
  LaunchedEffect(showMissions) { settings.putBoolean(KEY_SHOW_MISSIONS, showMissions) }
  LaunchedEffect(courseNearby) { settings.putInt(KEY_COURSE_NEARBY, courseNearby) }
  LaunchedEffect(courseUp) { settings.putBoolean(KEY_COURSE_UP, courseUp) }
  LaunchedEffect(poiCats) { settings.putString(KEY_POI_CATS, poiCats.joinToString(",")) }
  LaunchedEffect(vehStates) { settings.putString(KEY_VEH_STATES, vehStates.joinToString(",")) }
  LaunchedEffect(groundLayer) { settings.putString(KEY_GROUND_LAYER, groundLayer) }

  // Tell the mod which plane to sweep: it grid-samples only what a dashboard is actually showing, so
  // the selection is what causes a raster to exist at all. Cleared when the panel leaves composition
  // (page switch, tab closed), which is what makes a map nobody is looking at cost the mod nothing.
  val panelToken = remember { Any() }
  // rememberUpdatedState, so the dispose path reports through the CURRENT sink rather than the one
  // captured when the panel first composed.
  val showLayers by rememberUpdatedState(onShowLayers)
  LaunchedEffect(groundLayer) {
    showLayers(layerUnion(panelToken, if (groundLayer == NO_GROUND_LAYER) emptyList() else listOf(groundLayer)))
  }
  DisposableEffect(panelToken) {
    onDispose { showLayers(layerUnion(panelToken, null)) }
  }

  // The selected layer's slim info (legend + version), or null when unselected / not offered by the
  // current data -- the persisted id simply draws nothing until it reappears (edge case in the plan).
  val activeLayerInfo = mapLayers?.layers?.find { it.id == groundLayer }
  // Keyed on THIS layer's version, so a sweep of some other plane no longer refetches the overlay on
  // screen. A null version means the mod hasn't swept this plane (nobody had it selected until now):
  // there is nothing to fetch yet, and the sweep our selection just triggered will bring one.
  val activeLayerVersion = activeLayerInfo?.version
  // The trail and the raster it runs ahead of have to be exactly one colour, or the seam between them
  // shows. The server owns that colour — it publishes it in the coverage legend — so read it back from
  // there instead of keeping a second copy here in step by hand. The constant covers the moment before
  // the catalogue arrives.
  val coverageTint =
    (if (groundLayer == COVERAGE_LAYER_ID) activeLayerInfo?.legend?.singleOrNull()?.color else null)
      ?.let { parseHexColor(it)?.copy(alpha = COVERAGE_TRAIL_ALPHA) } ?: COVERAGE_TINT
  val layerKey = activeLayerVersion?.let { "$mapLayerUrl/$groundLayer|$it" }
  // Held WITH the layer id it was rendered from, and NOT cleared when layerKey changes: a new sweep's
  // version must keep showing the previous bitmap until the new one has fetched, rather than flashing
  // blank in between. Pairing it with the id is what keeps that from spilling across a layer *switch* --
  // the legend swaps the instant groundLayer changes, so painting the previous layer's raster under it
  // would label crops pixels as growth. Rendering is gated on the id matching (see below), so a switch
  // shows nothing until its own PNG lands, while a same-layer refresh still holds the old image.
  var layerBitmap by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
  val shownLayerBitmap = layerBitmap?.takeIf { it.first == groundLayer }?.second
  LaunchedEffect(layerKey) {
    if (layerKey == null) {
      layerBitmap = null // deselected, or this layer id isn't present in the current data
      return@LaunchedEffect
    }
    // Capture the layer this fetch is FOR. groundLayer is mutable state, and selecting another layer
    // writes it immediately while this coroutine is only cancelled at the next recomposition -- an
    // in-flight fetch can therefore resume in between and would otherwise file the bitmap it just
    // decoded under whatever layer is selected by then.
    val requestedLayer = groundLayer
    // The version being fetched was announced before this ran, so the server's mask already holds
    // everything the live trail has swept up to now. Noted here rather than on arrival: the ground
    // worked *during* the fetch is not in these bytes and stays the trail's to draw.
    val sweptIntoRaster = trailClock.elapsedNow().inWholeMilliseconds
    layerImageCache?.let { (cachedKey, cachedBitmap) ->
      if (cachedKey == layerKey) {
        layerBitmap = requestedLayer to cachedBitmap
        if (requestedLayer == COVERAGE_LAYER_ID) coverageTrail.settle(sweptIntoRaster)
        return@LaunchedEffect
      }
    }
    val url = "$mapLayerUrl/$requestedLayer?v=$activeLayerVersion"
    // Two attempts, because a transient network failure would otherwise leave the layer stuck on the
    // previous raster until the mod's next sweep -- which, on an idle map, is an in-game day away.
    // A 409 is exempt: it means this version has already been superseded server-side, so re-asking
    // for the same URL can only fail again. The WebSocket is about to deliver the new version, which
    // re-keys this effect and fetches the URL that matches it.
    for (attempt in 0 until LAYER_FETCH_ATTEMPTS) {
      if (attempt > 0) delay(LAYER_FETCH_RETRY_MS)
      val outcome =
        runCatching {
          val response = mapImageClient.get(url)
          // The status is checked here rather than left to the decoder: an error body would otherwise
          // reach makeFromEncoded and fail as if the PNG itself were corrupt, which makes a genuine
          // decode bug indistinguishable from an HTTP one -- and hides the 409 this must not retry.
          if (!response.status.isSuccess()) throw LayerFetchFailed(response.status)
          Image.makeFromEncoded(response.readRawBytes()).toComposeImageBitmap()
        }
      outcome.onSuccess {
        layerImageCache = layerKey to it
        layerBitmap = requestedLayer to it
        // This raster is now what draws that stretch of ground; the trail stops drawing it a second
        // time on top, which is what keeps the two from compositing into a darker band.
        if (requestedLayer == COVERAGE_LAYER_ID) coverageTrail.settle(sweptIntoRaster)
        return@LaunchedEffect
      }
      val error = outcome.exceptionOrNull()
      // runCatching catches Throwable, so a cancellation (the layer was switched, or the panel left
      // composition mid-fetch) lands here as a plain failure. Retrying that would be wrong.
      if (error is CancellationException) throw error
      if ((error as? LayerFetchFailed)?.status == HttpStatusCode.Conflict) break
    }
    // On failure the previous layerBitmap is left in place -- no flicker to blank on a transient miss.
  }

  Panel(
    title = "Map",
    icon = Icons.Filled.Map,
    modifier = modifier,
    headerActions = {
      if (mapData != null || mapVehicles != null || mapLayers != null || gpsCourse != null) {
        Icon(
          Icons.Filled.Tune,
          "filters & search",
          tint = if (filterOpen) VdtColors.Green else VdtColors.DarkGray,
          modifier = Modifier.size(16.dp).clickableNoRipple { filterOpen = !filterOpen },
        )
      }
      Icon(
        Icons.Filled.Remove,
        "zoom out",
        tint = VdtColors.DarkGray,
        modifier =
        Modifier.size(16.dp).clickableNoRipple {
          zoomAround(1f / 1.25f, sidePx / 2f, sidePx / 2f)
        },
      )
      Icon(
        Icons.Filled.Add,
        "zoom in",
        tint = VdtColors.DarkGray,
        modifier =
        Modifier.size(16.dp).clickableNoRipple {
          zoomAround(
            1.25f,
            sidePx / 2f,
            sidePx / 2f,
          )
        },
      )
      // Orientation, beside the follow toggle it belongs with: both answer "what is this map doing
      // while I drive". Turning it on also resumes following — course-up means "point where I am
      // going", which says nothing at all about a map parked over some other corner of the estate.
      Icon(
        Icons.Filled.Explore,
        if (courseUp) "north up" else "course up",
        tint = if (courseUp) VdtColors.Green else VdtColors.DarkGray,
        modifier =
        Modifier.size(16.dp).clickableNoRipple {
          courseUp = !courseUp
          if (courseUp) autoCenter = true
        },
      )
      Icon(
        Icons.Filled.CenterFocusStrong,
        "auto-center",
        tint = if (autoCenter) VdtColors.Green else VdtColors.DarkGray,
        modifier =
        Modifier.size(16.dp).clickableNoRipple {
          autoCenter =
            true
        },
      )
    },
  ) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
      val density = LocalDensity.current
      val side = with(density) { minOf(maxWidth, maxHeight).toPx() }
      LaunchedEffect(side) { sidePx = side }

      // Smoothly interpolate the player's normalized position over one telemetry sample
      // interval. Only the position is animated (not the scale), so zooming stays wobble-free.
      val animNorm by animateOffsetAsState(
        targetValue = if (player != null) Offset(player.posX, player.posZ) else Offset.Zero,
        animationSpec = tween(durationMillis = sampleIntervalMs, easing = LinearEasing),
        label = "playerNorm",
      )
      // Current translation: while auto-centering it tracks the (smoothed) player at the live
      // scale, so zoom stays locked on the player; otherwise it is the free pan/zoom offset.
      val anchor = anchorFor(side)
      val applied =
        if (autoCenter && player != null) {
          MapProjection.anchoredAt(animNorm, anchor, side, scale)
        } else {
          dragOffset
        }
      // The one transform every overlay projects through (see MapProjection).
      val projection = MapProjection(side, scale, applied, rotationFor(), anchor)
      // Let the long-lived gestures read the current transform without restarting the pointer input.
      val currentProjection by rememberUpdatedState(projection)

      // Two nested layers, because graphicsLayer turns everything about ONE origin and these two
      // transforms need different ones: the outer turns the map about the vehicle, the inner scales
      // and pans about the box corner. Nested, they compose into exactly MapProjection.toScreen —
      // scale, translate, then rotate — so the raster and the vectors cannot drift apart.
      //
      // The gestures live on the outer box, OUTSIDE its own rotation layer, so they keep receiving
      // plain box coordinates and the unrotating above stays the only place rotation is undone.
      androidx.compose.foundation.layout.Box(
        Modifier
          .size(with(density) { side.toDp() })
          .align(Alignment.Center)
          .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
              // Continue from the current on-screen offset; a pan ends centering.
              val current = currentProjection
              val base = if (autoCenter) current.offset else dragOffset
              if (pan != Offset.Zero) autoCenter = false
              val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
              // Zoom around the gesture centroid so the point under the fingers stays put. Both the
              // centroid and the drag arrive in turned screen space; the offset they feed is not
              // turned, so both are taken back through the rotation first — otherwise a drag on a
              // course-up map slides the world off at an angle to the finger.
              dragOffset =
                zoomedOffset(base, current.unrotate(centroid), scale, newScale) + current.unrotateVector(pan)
              scale = newScale
            }
          }.pointerInput(Unit) {
            // Mouse-wheel / trackpad zoom around the cursor (stays player-centred when
            // auto-centering, same as pinch). scrollDelta.y < 0 is scroll-up = zoom in.
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val dy = change.scrollDelta.y
                if (dy != 0f) {
                  // Wheel deltas are large and OS/browser-dependent; cap to one step
                  // per event so a notch is a single increment (trackpads still get
                  // proportional sub-steps from their smaller fractional deltas).
                  val step = dy.coerceIn(-1f, 1f)
                  zoomAround(1.15f.pow(-step), change.position.x, change.position.y)
                  change.consume()
                }
              }
            }
          }.pointerInput(mapData, side) {
            // Tap a field label to open its info popup; a tap that hits none closes an open one.
            // detectTapGestures coexists with the transform/scroll handlers above: it cancels itself
            // when a drag starts, so panning is unaffected. Positions arrive in the *screen* space of
            // this side×side box — the same frame the transform gesture's centroid and `applied`
            // live in — so a label is projected through the same MapProjection the overlay draws
            // with, and matched within a constant on-screen radius.
            detectTapGestures { tap ->
              // Only fields that are actually drawn are tappable — with the overlay hidden there are
              // no labels on screen, so a tap must not open a popup for an invisible field.
              val fields = mapData?.fields
              if (!showFields || fields.isNullOrEmpty()) {
                selectedFieldId = null
                return@detectTapGestures
              }
              val hitProjection = currentProjection
              val radius = FIELD_TAP_RADIUS_DP.toPx()
              var bestId: Int? = null
              var bestDist = radius
              for (f in fields) {
                val label = hitProjection.toScreen(f.labelX, f.labelZ)
                val d = hypot(tap.x - label.x, tap.y - label.y)
                if (d <= bestDist) {
                  bestDist = d
                  bestId = f.id
                }
              }
              selectedFieldId = bestId
            }
          }.graphicsLayer {
            // Outer: course-up's rotation, about the vehicle anchor.
            rotationZ = projection.rotationDeg
            transformOrigin = TransformOrigin(projection.pivot.x / side, projection.pivot.y / side)
          },
      ) {
        androidx.compose.foundation.layout.Box(
          Modifier.fillMaxSize().graphicsLayer {
            // Inner: the same zoom and pan MapProjection applies to the vectors, handed to the GPU
            // for the raster layers below (base map + ground overlay).
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = projection.scale
            scaleY = projection.scale
            translationX = projection.offset.x
            translationY = projection.offset.y
          },
        ) {
          bitmap?.let {
            Image(
              it,
              contentDescription = "map",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.FillBounds,
            )
          }
          // Ground-layer raster: unlike MapDataOverlay's vectors, this IS pixel data that must scale
          // exactly with the base map image, so it belongs inside the same zoom-scaled layer rather
          // than outside it. FilterQuality.None keeps grid cells crisp instead of smearing colors
          // together at high zoom (the whole point of a legend-driven raster).
          shownLayerBitmap?.let {
            Image(
              it,
              contentDescription = "ground layer",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.FillBounds,
              filterQuality = FilterQuality.None,
            )
          }
        }
      }

      // The freshest strip of coverage, drawn from this dashboard's own telemetry so the swath follows
      // the machine instead of arriving with the next published raster. Directly over the raster it
      // continues, and under everything else for the same reason the raster is: it is ground, not
      // annotation. See CoverageTrail for why the durable mask still lives on the server.
      if (coverageTrail.areas.isNotEmpty()) {
        CoverageTrailOverlay(coverageTrail.areas, projection, coverageTint)
      }

      // Guidance course, under the map-data overlay: it is terrain-level information (where the
      // lines run, what is worked), so field labels, POI dots and vehicle markers stay on top of it.
      // An empty course is the mod saying the driver has left the field — nothing to draw.
      if (showCourse && gpsCourse != null && !gpsCourse.isEmpty) {
        CourseOverlay(
          gpsCourse,
          vehicle?.gps?.course,
          mapData?.terrainSize ?: 0f,
          projection,
          focus = player?.let { Offset(it.posX, it.posZ) },
          nearbySwaths = courseNearby,
        )
      }

      // The rig's own footprint, on top of the course: the course says where the lines are, this says
      // where the tool is and whether ground is going under it right now.
      if (vehicle != null) {
        WorkOverlay(vehicle, projection)
      }

      // Map-data overlay (field outlines/labels + POI markers): like the player marker it lives
      // OUTSIDE the zoom-scaled graphicsLayer — the layer rasterizes, so vectors inside it blur at
      // high zoom. The outlines are re-projected each draw through the same MapProjection the image
      // is transformed by, the labels/markers stay constant-size.
      if (mapData != null || mapVehicles != null) {
        MapDataOverlay(
          mapData,
          mapVehicles,
          player?.farmId,
          projection,
          showFields,
          poiCats,
          vehStates,
          highlight,
          if (showMissions) shownMissions(accepted, missionChoices) else emptyList(),
        )
      }

      // Player marker: drawn OUTSIDE the zoom-scaled layer so the vector stays crisp (no
      // rasterize-then-upscale) and keeps a constant on-screen size at any zoom. Positioned at
      // the marker's projected screen location = contentPos * scale + translation.
      if (player != null) {
        androidx.compose.foundation.layout.Box(
          Modifier.size(with(density) { side.toDp() }).align(Alignment.Center),
        ) {
          Icon(
            Icons.Filled.Navigation,
            contentDescription = "player",
            tint = VdtColors.Red,
            modifier =
            Modifier
              .size(24.dp)
              .offset {
                val pos = projection.toScreen(animNorm)
                val half = 12.dp.toPx()
                IntOffset((pos.x - half).roundToInt(), (pos.y - half).roundToInt())
                // In course-up these cancel to zero: the map turned instead, so the marker points
                // straight up the screen the way the machine points up the field.
              }.rotate(animHeading + projection.rotationDeg),
          )
        }
      }

      // Field-info popup: the tapped field's game FELDINFO (geometry from mapData + agronomy from
      // fieldInfo), joined by field id. Cleared when the field leaves the data or the user closes it.
      selectedFieldId?.let { id ->
        val field = mapData?.fields?.firstOrNull { it.id == id }
        if (field == null) {
          selectedFieldId = null
        } else {
          FieldInfoPopup(
            field = field,
            info = fieldInfo?.fields?.firstOrNull { it.id == id },
            farms = mapData.farms,
            playerFarmId = player?.farmId,
            onClose = { selectedFieldId = null },
          )
        }
      }

      // Ground-layer legend, only while a layer is actually selected and its own raster is showing.
      if (activeLayerInfo != null && shownLayerBitmap != null) {
        GroundLayerLegend(activeLayerInfo.legend, side, bottomInset = sectionStripHeight)
      }

      // Navigation as map chrome (issue #43): opt-in per placed tile, so a map used as an overview
      // stays uncovered while a map used as a run screen carries its heading and lamps. Above the
      // legend and the field popup in the stack — it is a fixed strip in a corner they don't use.
      if (showGuidance) {
        GuidanceStrip(heading, vehicle, onCommand = onCommand)
      }

      // The boom along the bottom edge (issue #43), where the reference terminals put it — and in
      // course-up, directly under the machine it belongs to, which sits two thirds down the screen.
      // Opt-in per placed tile like the navigation strip, and absent entirely on foot or on a rig with
      // nothing that works ground, rather than showing an empty bar.
      val boom = if (showSections) boomOf(vehicle) else null
      if (boom != null) {
        SectionStrip(boom, onHeight = { sectionStripHeight = it })
      } else {
        // Hand the legend its corner back when the tool is unhitched or the strip is switched off.
        LaunchedEffect(Unit) { sectionStripHeight = 0.dp }
      }

      // Filter & search popover, on top of everything map-related.
      if (filterOpen && (mapData != null || mapVehicles != null || mapLayers != null || gpsCourse != null)) {
        MapFilterPanel(
          mapData = mapData,
          mapVehicles = mapVehicles,
          mapLayers = mapLayers,
          hasCourse = gpsCourse != null && !gpsCourse.isEmpty,
          showCourse = showCourse,
          onShowCourse = { showCourse = it },
          courseNearby = courseNearby,
          onCourseNearby = { courseNearby = it },
          groundLayer = groundLayer,
          onGroundLayer = { groundLayer = it },
          showFields = showFields,
          onShowFields = { showFields = it },
          missions = accepted,
          missionChoices = missionChoices,
          showMissions = showMissions,
          onShowMissions = { showMissions = it },
          onShowMission = { id, on -> missionChoices = missionChoices + (id to on) },
          poiCats = poiCats,
          onPoiCats = { poiCats = it },
          vehStates = vehStates,
          onVehStates = { vehStates = it },
          query = searchQuery,
          onQuery = {
            searchQuery = it
            if (it.isBlank()) highlight = null
          },
          onFocus = ::focusOn,
          // The mask being cleared is the server's, so the POST is the whole operation and its status
          // is the only thing that knows whether it happened — a discarded failure would leave the row
          // reading "done" while the coverage stays exactly where it was. The redraw still isn't
          // awaited: the server publishes the cleared mask's version over the WebSocket, and the
          // ordinary layer-fetch path picks it up.
          onReset = {
            val cleared =
              coverageResetUrl.isNotBlank() &&
                mapImageClient
                  .post(coverageResetUrl) { timeout { requestTimeoutMillis = COVERAGE_RESET_TIMEOUT_MS } }
                  .status
                  .isSuccess()
            // The local trail is coverage too, and it is the part in front of the raster — leaving it
            // would redraw the last few seconds of a pass the driver just asked to be rid of. Only
            // once the raster behind it is actually gone, though.
            if (cleared) coverageTrail.clear()
            cleared
          },
        )
      }
    }
  }
}

// Points projected further than this (px) outside the canvas are culled before any text measuring.
private const val OVERLAY_CULL_MARGIN = 80f

/**
 * The clock the live coverage trail ages its polygons by. Monotonic and process-wide: it is only ever
 * read as a difference, and a wall clock can step.
 */
private val trailClock = TimeSource.Monotonic.markNow()

/** The alpha `MapLayerRenderer` gives every ground layer, so the trail composites like the raster. */
private const val COVERAGE_TRAIL_ALPHA = 0.6f

/**
 * The live trail's fill until the coverage legend arrives with the real one (see `coverageTint`).
 *
 * Magenta rather than green for the same reason the server publishes it that way: this layer is read
 * over grass, on a green map, under a course that shades its own worked lines green.
 */
private val COVERAGE_TINT = Color(0xFFC026D3).copy(alpha = COVERAGE_TRAIL_ALPHA)

/**
 * The worked ground the published raster does not have yet, drawn ahead of it.
 *
 * Filled, not stroked, and in [tint] — the raster's own colour, taken from the legend the server
 * publishes with it: this is not a separate thing being shown, it is the same layer arriving sooner.
 * See [CoverageTrail] for why it never overlaps the raster — where it did, the two translucent fills
 * composited into a visibly darker band.
 *
 * **One path for the whole trail, filled once.** A fill per polygon shows every seam between them:
 * consecutive sweeps abut exactly, and two anti-aliased edges meeting on the same line each cover the
 * boundary pixels partly, so the pass comes out finely striped. Merged into one path they are a single
 * region with no interior edges, filled at one alpha however long the trail is.
 */
@Composable
private fun BoxScope.CoverageTrailOverlay(areas: List<SweptArea>, projection: MapProjection, tint: Color) {
  val density = LocalDensity.current
  // Rebuilt when the trail changes, not on every pan, zoom or heading step: the path is in normalized
  // space and the transform below does the rest.
  val path = remember(areas) { sweptPath(areas) }
  Canvas(Modifier.size(with(density) { projection.side.toDp() }).align(Alignment.Center)) {
    val factor = projection.factor
    withTransform({
      rotate(projection.rotationDeg, pivot = projection.pivot)
      translate(projection.offset.x, projection.offset.y)
      scale(factor, factor, pivot = Offset.Zero)
    }) {
      drawPath(path, tint)
    }
  }
}

/** The whole trail as one path, in the normalized space every overlay is drawn in. */
private fun sweptPath(areas: List<SweptArea>): Path = Path().apply {
  for (area in areas) {
    moveTo(area.xs[0], area.zs[0])
    for (i in 1 until area.xs.size) lineTo(area.xs[i], area.zs[i])
    close()
  }
}

/**
 * The guidance course: every line the game's steering assist generated for the field being driven,
 * shaded by whether it is done, with the line currently being followed picked out.
 *
 * Drawn like the field polygons — paths built once per course in normalized space and re-projected
 * under [projection] — but with two stroke widths that mean different things. The centreline is a
 * constant on-screen hairline (its width is divided back out of the transform). The swath under it is
 * a real-world width: `implementWidth` meters over the terrain edge, so it grows with zoom exactly as
 * the ground it covers does, which is what turns a set of lines into a picture of what is worked.
 *
 * [state] is the live half from the telemetry tick. Its indices are honoured **only** when its
 * `courseId` matches the geometry's: the mod publishes a new id the instant the game replaces the
 * course, and this file follows a beat later, so for that beat the "current line" would otherwise
 * highlight whatever line happens to hold that index in the course being replaced.
 *
 * With [nearbySwaths] above zero only the lines within that many swaths of [focus] are drawn — the
 * line being driven and its neighbours, rather than a whole field of them. Measured as distance from
 * the machine, **not** as segment index ±N: the game assembles the list per line group with `pairs`
 * and then appends islands and headlands (`FieldCourseSegmentGenerator:238-272`), so index adjacency
 * is not ground adjacency on any field the generator splits into more than one group. The field
 * boundary and the islands are drawn whatever the window is: they are the shape of the field, not
 * lines to steer by.
 */
@Composable
private fun BoxScope.CourseOverlay(
  course: GpsCourseData,
  state: GpsCourseState?,
  terrainSize: Float,
  projection: MapProjection,
  focus: Offset? = null,
  nearbySwaths: Int = 0,
) {
  val density = LocalDensity.current

  val paths =
    remember(course) {
      course.segments.mapNotNull { segment ->
        val p = segment.p
        if (p.size < 4) return@mapNotNull null
        val path =
          Path().apply {
            moveTo(p[0], p[1])
            for (i in 2 until p.size - 1 step 2) lineTo(p[i], p[i + 1])
          }
        segment to path
      }
    }

  // Only the geometry this state actually describes (see the doc comment).
  val live = state?.takeIf { it.courseId == course.courseId && it.courseId.isNotBlank() }
  val swathWidth = if (terrainSize > 0f && course.implementWidth > 0f) course.implementWidth / terrainSize else 0f

  // Half a swath more than asked for, so the line being driven sits inside its own window rather than
  // exactly on its edge — a rig steering a side offset is up to half a swath off the line it is on.
  val window = if (nearbySwaths > 0 && swathWidth > 0f && focus != null) swathWidth * (nearbySwaths + 0.5f) else 0f
  // The filter walks every point of every line, and a headland ring is 256 of them, so it is not run
  // on every frame the marker animates through: the machine's position is quantized to a quarter of
  // the window first, which recomputes a few times per swath driven and never mid-swath.
  val step = window / 4f
  val near =
    if (window > 0f &&
      focus != null
    ) {
      Offset((focus.x / step).roundToInt() * step, (focus.y / step).roundToInt() * step)
    } else {
      null
    }
  val shown =
    remember(paths, window, near) {
      if (near == null) paths else paths.filter { (segment, _) -> polylineWithin(segment.p, near, window) }
    }

  Canvas(Modifier.size(with(density) { projection.side.toDp() }).align(Alignment.Center)) {
    val factor = projection.factor
    withTransform({
      // Outermost first — scale, then translate, then rotate; see MapDataOverlay.
      rotate(projection.rotationDeg, pivot = projection.pivot)
      translate(projection.offset.x, projection.offset.y)
      scale(factor, factor, pivot = Offset.Zero)
    }) {
      // Divided back out of the transform: the geometry scales, these lines keep their screen width.
      val hairline = 1.dp.toPx() / factor
      val currentLine = 2.5.dp.toPx() / factor

      // The field the course was generated against — the detected boundary, so it hugs the crop edge
      // rather than the farmland square map.json draws.
      if (course.boundary.size >= 6) {
        drawPath(flatPath(course.boundary, close = true), VdtColors.Accent.copy(alpha = 0.5f), style = Stroke(hairline))
      }
      for (island in course.islands) {
        if (island.size >= 6) {
          drawPath(flatPath(island, close = true), VdtColors.Amber.copy(alpha = 0.5f), style = Stroke(hairline))
        }
      }

      for ((segment, path) in shown) {
        val worked = live?.isWorked(segment.i) == true
        val current = live != null && segment.i == live.segmentIndex
        if (swathWidth > 0f) {
          // The swath: what this line covers on the ground. Worked lines read as a filled band, the
          // rest as a faint one — the same "where have I been" the reference terminals paint.
          drawPath(
            path,
            if (worked) VdtColors.Green.copy(alpha = 0.35f) else VdtColors.White.copy(alpha = 0.12f),
            style = Stroke(width = swathWidth),
          )
        }
        val tint =
          when {
            current -> VdtColors.Red
            segment.kind == "headland" -> VdtColors.ProgressBlue
            segment.kind == "island" -> VdtColors.Amber
            worked -> VdtColors.Green
            else -> VdtColors.White
          }
        drawPath(path, tint, style = Stroke(width = if (current) currentLine else hairline))
      }
    }
  }
}

/**
 * The working footprint: every work area of the rig that is able to work, drawn where it is.
 *
 * The mod exports three corners of each area — the engine's own start / width / height nodes — and the
 * fourth is `width + height - start`, so a swath is a parallelogram rather than a rectangle whenever
 * the tool is at an angle to the ground it covers. They are normalized map coordinates like everything
 * else on this map, so they go through [projection] unchanged.
 *
 * Filled where the area is *processing* (it touched ground within the last 200 ms) and outlined where
 * it is merely active — the difference between a tool that is working and one that is lowered over a
 * finished patch. Areas that are off entirely are not drawn: a raised implement covers nothing, and
 * painting its resting footprint would read as coverage.
 *
 * Not remembered on the vehicle: the whole point is that it moves every tick.
 */
@Composable
private fun BoxScope.WorkOverlay(vehicle: Vehicle, projection: MapProjection) {
  val density = LocalDensity.current
  val areas = workFootprints(vehicle)
  if (areas.isEmpty()) return

  Canvas(Modifier.size(with(density) { projection.side.toDp() }).align(Alignment.Center)) {
    val factor = projection.factor
    withTransform({
      rotate(projection.rotationDeg, pivot = projection.pivot)
      translate(projection.offset.x, projection.offset.y)
      scale(factor, factor, pivot = Offset.Zero)
    }) {
      val hairline = 1.dp.toPx() / factor
      for (area in areas) {
        val path = quadPath(area.shape)
        if (area.processing) {
          drawPath(path, VdtColors.Accent.copy(alpha = 0.45f))
        }
        drawPath(path, VdtColors.Accent, style = Stroke(hairline))
      }
    }
  }
}

/** Every work area of the rig that can currently work and knows where it is, tractor and tools alike. */
private fun workFootprints(vehicle: Vehicle): List<WorkArea> = vehicle.activeWorkAreas().filter { it.shape.size >= 6 }

/** The work area's parallelogram, from the three corners the engine describes it by. */
private fun quadPath(shape: List<Float>): Path = Path().apply {
  val (sx, sz) = shape[0] to shape[1]
  val (wx, wz) = shape[2] to shape[3]
  val (hx, hz) = shape[4] to shape[5]
  moveTo(sx, sz)
  lineTo(wx, wz)
  // The corner opposite the start, which the mod leaves to us rather than sending a fourth point.
  lineTo(wx + hx - sx, wz + hz - sz)
  lineTo(hx, hz)
  close()
}

/**
 * Whether any part of the flat `[x1, z1, x2, z2, …]` polyline comes within [radius] of [point].
 *
 * The distance is to the line, not to its vertices: a guidance line is two points a field apart, so
 * measuring to the ends alone would hide the very line the machine is standing in the middle of.
 * Returns on the first hit — the line being driven is found in its first segment or two.
 */
internal fun polylineWithin(points: List<Float>, point: Offset, radius: Float): Boolean {
  if (points.size < 4) return false
  val radiusSq = radius * radius
  for (i in 0 until points.size - 3 step 2) {
    val ax = points[i]
    val az = points[i + 1]
    val dx = points[i + 2] - ax
    val dz = points[i + 3] - az
    val lengthSq = dx * dx + dz * dz
    // Where the foot of the perpendicular falls along this piece, clamped to its ends: past them the
    // nearest point of the piece IS an end. A zero-length piece degenerates to its own start.
    val t = if (lengthSq <= 0f) 0f else (((point.x - ax) * dx + (point.y - az) * dz) / lengthSq).coerceIn(0f, 1f)
    val offX = point.x - (ax + t * dx)
    val offZ = point.y - (az + t * dz)
    if (offX * offX + offZ * offZ <= radiusSq) return true
  }
  return false
}

/** Path from a flat `[x1, z1, x2, z2, …]` polyline, in whatever space the coordinates are in. */
private fun flatPath(points: List<Float>, close: Boolean): Path = Path().apply {
  moveTo(points[0], points[1])
  for (i in 2 until points.size - 1 step 2) lineTo(points[i], points[i + 1])
  if (close) close()
}

// VehicleHotspot.TYPE tokens that are driven and get a heading arrow; everything else (trailer,
// tool, toolTrailed, cutter, other, and unknown future types) is equipment and gets a plain square.
private val DrivableVehicleTypes =
  setOf("tractor", "truck", "car", "harvester", "wheelloader", "horse", "train", "motorbike", "woodHarvester", "boat")

/**
 * The map-data overlay: field polygons + number labels, POI dots, and vehicle markers, drawn into
 * the same side×side box as the map image. The polygons are vector paths in normalized [0,1]
 * space, re-projected each draw through the image's exact transform ([projection]) with a
 * zoom-compensated stroke — so they hug the map at any zoom but keep a constant on-screen line
 * width. Labels, POI dots and vehicle arrows are constant-size like the player marker; secondary
 * text (field area, POI/vehicle names) only appears above [DETAIL_ZOOM].
 */
@Composable
private fun BoxScope.MapDataOverlay(
  mapData: MapData?,
  mapVehicles: MapVehiclesData?,
  playerFarmId: Int?,
  projection: MapProjection,
  showFields: Boolean,
  poiCats: Set<String>,
  vehStates: Set<String>,
  highlight: Offset?,
  /** The contracts to draw — already filtered by the panel; see [shownMissions]. */
  accepted: List<Mission>,
) {
  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()

  // farmId -> the farm's in-game map color, parsed once per channel update. Also colors the
  // vehicle markers, so ownership reads the same for land and machines.
  val farmColors =
    remember(mapData) {
      (mapData?.farms ?: emptyList())
        .mapNotNull { farm -> parseHexColor(farm.color)?.let { farm.id to it } }
        .toMap()
    }

  // farmlandId -> the colour of the contract on it, so a field under contract reads as one at a
  // glance instead of only through its marker. Every mission type carries the farmland it sits on,
  // so the forestry and rock contracts tint their land too. Recomputed with the channel, not per
  // frame.
  val missionFieldTints =
    remember(accepted) {
      accepted.mapNotNull { mission -> mission.fieldId?.let { it to missionColor(mission) } }.toMap()
    }

  // The game marks a contract with a blinking circle, so this one blinks too — one transition for
  // every marker, so they pulse together rather than each on its own phase.
  val blink by
    rememberInfiniteTransition(label = "contract").animateFloat(
      initialValue = 0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
      label = "contractAlpha",
    )

  // One Path per field, rebuilt only when the channel updates (never on pan/zoom).
  val fieldPaths =
    remember(mapData) {
      (mapData?.fields ?: emptyList()).mapNotNull { field ->
        val poly = field.polygon
        if (poly.size < 6) return@mapNotNull null
        val path =
          Path().apply {
            moveTo(poly[0], poly[1])
            for (i in 2 until poly.size - 1 step 2) lineTo(poly[i], poly[i + 1])
            close()
          }
        field to path
      }
    }

  // Navigation-style arrow pointing north (heading 0), in px around the origin; rotated per
  // vehicle at draw time. Built once — px sizes only change with density.
  val vehicleArrow =
    remember(density) {
      val u = with(density) { 1.dp.toPx() }
      Path().apply {
        moveTo(0f, -7f * u)
        lineTo(5f * u, 6f * u)
        lineTo(0f, 3f * u)
        lineTo(-5f * u, 6f * u)
        close()
      }
    }

  val labelStyle =
    remember {
      TextStyle(
        color = VdtColors.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        shadow = Shadow(color = VdtColors.Black, blurRadius = 3f),
      )
    }
  val detailStyle = remember { labelStyle.copy(fontSize = 9.sp, fontWeight = FontWeight.Normal) }

  Canvas(Modifier.size(with(density) { projection.side.toDp() }).align(Alignment.Center)) {
    val scale = projection.scale
    val factor = projection.factor

    fun toScreen(normX: Float, normZ: Float) = projection.toScreen(normX, normZ)

    fun onCanvas(pos: Offset) = projection.isVisible(pos, OVERLAY_CULL_MARGIN)

    // Course-up and text: the labels below need NO counter-rotation, and adding one is a bug — it
    // shipped as one. This canvas is never rotated; only the *positions* pass through the rotation,
    // inside toScreen, so a label drawn at a projected point is upright to begin with. Turning it
    // back "to keep it upright" tilts it by the heading instead, which is what a driver sees.
    //
    // The counter-rotation belongs to the other way of building this, where the whole canvas turns
    // and every label has to be undone. Here rotation reaches the canvas only inside the withTransform
    // blocks below, which wrap geometry — field outlines, the course — and never text.
    if (showFields && mapData != null) {
      withTransform({
        // Listed outermost-first, so this composes as scale -> translate -> rotate: the same
        // pipeline MapProjection.toScreen runs the markers through.
        rotate(projection.rotationDeg, pivot = projection.pivot)
        translate(projection.offset.x, projection.offset.y)
        scale(factor, factor, pivot = Offset.Zero)
      }) {
        // Stroke width divided back out of the transform: geometry scales, the line doesn't.
        val strokeWidth = 1.5.dp.toPx() / factor
        for ((field, path) in fieldPaths) {
          // A contract's colour wins over the ownership tint: a field on offer is unowned, so the
          // ownership tint has nothing to say about it, and the contract does.
          val contractTint = missionFieldTints[field.id]
          val tint = contractTint ?: fieldTint(field, playerFarmId, farmColors)
          drawPath(path, tint.copy(alpha = if (contractTint != null) 0.22f else 0.10f))
          drawPath(path, tint, style = Stroke(width = if (contractTint != null) strokeWidth * 2f else strokeWidth))
        }
      }
      for (field in mapData.fields) {
        val pos = toScreen(field.labelX, field.labelZ)
        if (!onCanvas(pos)) continue
        drawCenteredText(textMeasurer, field.name.ifBlank { field.id.toString() }, pos, labelStyle)
        if (scale >= DETAIL_ZOOM && field.areaHa > 0f) {
          drawCenteredText(textMeasurer, "${field.areaHa} ha", pos + Offset(0f, 12.dp.toPx()), detailStyle)
        }
      }
    }

    if (mapData != null && poiCats.isNotEmpty()) {
      for (poi in mapData.pois) {
        val category = poiCategory(poi.type)
        if (category !in poiCats) continue
        val pos = toScreen(poi.posX, poi.posZ)
        if (!onCanvas(pos)) continue
        drawCircle(VdtColors.White, radius = 4.dp.toPx(), center = pos)
        drawCircle(poiCategoryColor(category), radius = 3.dp.toPx(), center = pos)
        if (scale >= DETAIL_ZOOM && poi.name.isNotBlank()) {
          drawCenteredText(textMeasurer, poi.name, pos + Offset(0f, 12.dp.toPx()), detailStyle)
        }
      }
    }

    // Contract markers, the way the game draws them: a blinking circle, sized in world meters so it
    // grows with the zoom like the game's own (AbstractFieldMissionHotspot uses a 50 m radius).
    // Above the POIs so a contract on a farmyard is not buried by one, below the vehicles, which are
    // the live thing on the map.
    //
    // CLIPPED, unlike everything else on this canvas. The rest of the overlay gets away with culling
    // alone because every one of its markers is a few dp across, so a cull margin covers the overhang
    // -- but this circle is sized in world meters and grows without bound as you zoom in, and a
    // delivery run is a line between two points that need not both be on screen. A Compose Canvas
    // does not clip to its own bounds, so unclipped either one paints over the page around the map.
    // Sized off the ground it covers, so it reacts to the zoom the way the game's own marker does.
    // `factor` is already side * scale -- multiplying by it *and* by side is what pinned this at its
    // cap at every zoom, which is the bug that made the circle look like a fixed-size overlay.
    //
    // The growth is damped by √zoom rather than left linear: linear is faithful to a 50 m circle but
    // reaches ~40% of the map at 16x, so it would spend the top third of the zoom range pinned at a
    // cap and stop reacting again. √ keeps it a marker across the whole 0.25x-16x range.
    val terrainSize = mapData?.terrainSize ?: 0f
    val worldRadiusPx =
      if (terrainSize > 0f) {
        (MISSION_MARKER_RADIUS_M / terrainSize * projection.side * sqrt(scale))
          .coerceIn(6.dp.toPx(), 72.dp.toPx())
      } else {
        7.dp.toPx()
      }

    clipRect {
      for (mission in accepted) {
        val x = mission.posX ?: continue
        val z = mission.posZ ?: continue
        val pos = toScreen(x, z)
        val station = mission.sellingStation?.takeIf { it.hasPosition }
        val stationPos = station?.let { toScreen(it.posX ?: 0f, it.posZ ?: 0f) }

        // Where the load goes, and the run between the two. Drawn under the marker so the circle
        // stays readable on top of it, and kept when either end is on screen: a contract whose field
        // is off-canvas may still be delivering to a station that is on it.
        if (stationPos != null && (onCanvas(pos) || onCanvas(stationPos))) {
          val tint = missionColor(mission)
          // Dashed, so it reads as a run between two places rather than another field border, and at
          // a steady alpha: on the blink it bottomed out near 0.1 and all but vanished twice a
          // second. The pulse belongs to the two ends, which are what you are looking for.
          drawLine(
            tint.copy(alpha = 0.65f),
            pos,
            stationPos,
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
          )
          if (onCanvas(stationPos)) {
            // The delivery point blinks like the work does — same language, smaller ring, because it
            // is the other half of one contract rather than a marker of its own. Deliberately
            // unlabelled: the station is a POI, and the POI layer has already written its name there.
            val stationRadius = (worldRadiusPx * 0.55f).coerceAtLeast(5.dp.toPx())
            drawCircle(
              tint.copy(alpha = blink),
              radius = stationRadius,
              center = stationPos,
              style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(tint, radius = 2.5.dp.toPx(), center = stationPos)
          }
        }

        // The circle's own cull has to allow for its radius, or a contract just off screen loses the
        // arc that should still be reaching onto it.
        if (!projection.isVisible(pos, worldRadiusPx + OVERLAY_CULL_MARGIN)) continue
        val tint = missionColor(mission)
        drawCircle(tint.copy(alpha = 0.18f * blink), radius = worldRadiusPx, center = pos)
        drawCircle(
          tint.copy(alpha = blink),
          radius = worldRadiusPx,
          center = pos,
          style = Stroke(width = 2.dp.toPx()),
        )
        // A solid centre, so a contract is still findable when the map is zoomed far enough out that
        // its circle is down to the minimum.
        drawCircle(tint, radius = 2.5.dp.toPx(), center = pos)
        if (scale >= DETAIL_ZOOM && mission.title.isNotBlank() && onCanvas(pos)) {
          drawCenteredText(textMeasurer, mission.title, pos + Offset(0f, worldRadiusPx + 8.dp.toPx()), detailStyle)
        }
      }
    }

    if (mapVehicles != null && vehStates.isNotEmpty()) {
      for (v in mapVehicles.vehicles) {
        // The locally driven vehicle already has the (animated) player marker on it.
        if (v.isEntered) continue
        if (vehicleStateOf(v) !in vehStates) continue
        val pos = toScreen(v.posX, v.posZ)
        if (!onCanvas(pos)) continue
        val tint = vehicleTint(v, playerFarmId, farmColors)
        if (v.type in DrivableVehicleTypes) {
          // Drivables: a heading arrow.
          withTransform({
            translate(pos.x, pos.y)
            // Plus the map's own rotation: a heading is relative to north, and in course-up north
            // is no longer up the screen.
            rotate(degrees = v.heading + projection.rotationDeg, pivot = Offset.Zero)
          }) {
            drawPath(vehicleArrow, tint)
            drawPath(vehicleArrow, VdtColors.White, style = Stroke(width = 1.dp.toPx()))
          }
        } else {
          // Implements/trailers: a plain square — no arrow, they don't "head" anywhere.
          val half = 3.5.dp.toPx()
          val topLeft = pos - Offset(half, half)
          val size = Size(half * 2, half * 2)
          drawRect(tint, topLeft = topLeft, size = size)
          drawRect(VdtColors.White, topLeft = topLeft, size = size, style = Stroke(width = 1.dp.toPx()))
        }
        // AI helper: a small badge dot at the marker's center.
        if (v.isAI) {
          drawCircle(VdtColors.White, radius = 1.5.dp.toPx(), center = pos)
        }
        if (scale >= DETAIL_ZOOM && v.name.isNotBlank()) {
          drawCenteredText(textMeasurer, v.name, pos + Offset(0f, 14.dp.toPx()), detailStyle)
        }
      }
    }

    // Search-hit ring, always on top of the markers it points at.
    highlight?.let {
      drawCircle(
        VdtColors.Red,
        radius = 11.dp.toPx(),
        center = toScreen(it.x, it.y),
        style = Stroke(width = 2.dp.toPx()),
      )
    }
  }
}

/**
 * Legend for the active ground layer: one row per legend entry, deduped by label (the growth
 * gradient's 8 steps all share the "Growing" label, so this collapses them to a single swatch).
 * Capped at ~40% of the map's side so a long soil/crop legend doesn't dominate the panel.
 *
 * [bottomInset] is the room the section strip has claimed along the same edge — the legend stacks on
 * top of it rather than under it, since the strip is the thing being read while driving.
 */
@Composable
private fun BoxScope.GroundLayerLegend(legend: List<MapLayerLegendEntry>, side: Float, bottomInset: Dp = 0.dp) {
  val density = LocalDensity.current
  Column(
    Modifier
      .align(Alignment.BottomStart)
      .padding(bottom = bottomInset)
      .padding(6.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .heightIn(max = with(density) { (side * 0.4f).toDp() })
      .verticalScroll(rememberScrollState())
      .padding(6.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    for (entry in legend.distinctBy { it.label }) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        parseHexColor(entry.color)?.let { color -> Box(Modifier.size(8.dp).clip(CircleShape).background(color)) }
        Text(entry.label, fontSize = 11.sp, color = VdtColors.TextDark)
      }
    }
  }
}

/**
 * Vehicle marker tint: the owning farm's in-game map color, same lookup as [fieldTint] so land and
 * machines read consistently; the own/other fallback applies when the color table is missing, and
 * an unowned vehicle stays gray.
 */
private fun vehicleTint(vehicle: MapVehicle, playerFarmId: Int?, farmColors: Map<Int, Color>): Color {
  val owner = vehicle.farmId ?: return VdtColors.DarkGray
  farmColors[owner]?.let { return it }
  return if (playerFarmId == null || owner == playerFarmId) VdtColors.Green else VdtColors.Red
}

private fun DrawScope.drawCenteredText(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle) {
  val layout = measurer.measure(AnnotatedString(text), style)
  drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/**
 * Field tint by ownership: an owned field uses **the owning farm's in-game map color** (from the
 * channel's `farms` table — the exact color the game's own farmlands overlay paints), unowned gray.
 * When the owner's color isn't in the table (telemetry from a mod version without `farms`), fall
 * back to own-green / other-red, and to green for every owner when the player's farm is unknown too.
 */
private fun fieldTint(field: MapField, playerFarmId: Int?, farmColors: Map<Int, Color>): Color {
  val owner = field.ownerFarmId ?: return VdtColors.DarkGray
  farmColors[owner]?.let { return it }
  return if (playerFarmId == null || owner == playerFarmId) VdtColors.Green else VdtColors.Red
}

/**
 * What a contract's marker (and its field) is coloured by: what you can do about it. Amber is an
 * offer that is still open, green is money waiting to be collected, blue is work under way. Keyed on
 * status rather than mission type — the type set is open-ended.
 */
internal fun missionColor(mission: Mission): Color = when {
  mission.isFinished -> VdtColors.Green
  mission.isActive -> VdtColors.ProgressBlue
  else -> VdtColors.Amber
}

/**
 * Whether a contract is drawn when nobody has said otherwise: yes while it is being worked, no once
 * it is done.
 *
 * A finished contract sits on the board until someone walks to the NPC, and its marker covers ground
 * that no longer needs driving to — on a map showing three running jobs it is the one circle that
 * means "nothing to do here". Collecting is the app's business, not the map's, so it comes off by
 * default and goes back on per contract.
 */
internal fun missionShownByDefault(mission: Mission): Boolean = !mission.isFinished

/**
 * Whether one contract is drawn: the user's own answer where they gave one, the default otherwise.
 * [choices] holds only explicit answers, which is what lets a contract that finishes while shown drop
 * off by itself while one that was asked for stays.
 */
internal fun isMissionShown(mission: Mission, choices: Map<Int, Boolean>): Boolean =
  choices[mission.id] ?: missionShownByDefault(mission)

/** The accepted contracts the map draws, in board order. */
internal fun shownMissions(accepted: List<Mission>, choices: Map<Int, Boolean>): List<Mission> =
  accepted.filter { isMissionShown(it, choices) }

/**
 * How a contract reads in the filter list: the job, then where it is. The location is the
 * discriminator on a board carrying six harvest contracts, so it wins the room over the subtitle —
 * and a contract the mod could not place falls back to naming what it is for.
 */
internal fun missionFilterLabel(mission: Mission): String {
  val where = mission.location.ifBlank { mission.subtitle }
  val job = mission.title.ifBlank { mission.type }
  return if (where.isBlank()) job else "$job · $where"
}

/** "#rrggbb" -> [Color]; null for anything else (missing, malformed, unexpected length). */
private fun parseHexColor(hex: String?): Color? {
  if (hex == null || hex.length != 7 || !hex.startsWith("#")) return null
  val rgb = hex.substring(1).toIntOrNull(16) ?: return null
  return Color(
    red = ((rgb shr 16) and 0xFF) / 255f,
    green = ((rgb shr 8) and 0xFF) / 255f,
    blue = (rgb and 0xFF) / 255f,
  )
}

// The in-game map legend's categories: the filter vocabulary, in legend order. Vehicle "states"
// are the filter vocabulary on the vehicles side (a vehicle is exactly one of these).
private val PoiCategories = listOf("unloading", "loading", "production", "animal", "other")
private val VehicleStates = listOf("ai", "player", "parked")

private fun poiCategoryLabel(category: String): String = when (category) {
  "unloading" -> "Unloading"
  "loading" -> "Loading"
  "production" -> "Production"
  "animal" -> "Animals"
  else -> "Other"
}

private fun vehicleStateLabel(state: String): String = when (state) {
  "ai" -> "AI active"
  "player" -> "Player-driven"
  else -> "Parked"
}

/** Fallback name for a plane the mod didn't label (an older mod version). */
private fun groundLayerLabel(id: String): String = when (id) {
  "crops" -> "Crops"
  "growth" -> "Growth"
  "soil" -> "Soil"
  else -> id
}

/**
 * Legend category per POI type token (the mod's camelCased `PlaceableHotspot.TYPE` key), grouped
 * the way the in-game map legend groups them. Shops render under "Sonstiges" in the game (despite
 * CATEGORY_SHOP existing in code), and an unknown token from a newer mod lands there too.
 */
private fun poiCategory(type: String): String = when (type) {
  "unloading", "unloadingTrain", "unloadingPallet" -> "unloading"
  "loading", "fuel", "electricity" -> "loading"
  "productionPoint", "fishpond", "fishbreeding" -> "production"
  "chicken", "pig", "sheep", "cow", "horse", "bee", "wildlife" -> "animal"
  else -> "other"
}

// The in-game map legend's category colors (sampled from the game's building filter tiles) — a POI
// dot shows the color the player already knows from the game's own map.
private fun poiCategoryColor(category: String): Color = when (category) {
  "unloading" -> Color(0xFF8D0D5F)

  // Abladestationen
  "loading" -> Color(0xFF264BB0)

  // Ladestationen
  "production" -> Color(0xFF16C6C8)

  // Produktionen
  "animal" -> Color(0xFF166A5F)

  // Tiere
  else -> Color(0xFFCDC60D) // Sonstiges
}

/** One filter state per vehicle: an AI helper, a human driver, or neither. */
private fun vehicleStateOf(vehicle: MapVehicle): String = when {
  vehicle.isAI -> "ai"
  vehicle.isControlled -> "player"
  else -> "parked"
}

/** Loads a persisted filter set; an unset key defaults to everything enabled. */
private fun loadFilterSet(settings: WidgetSettings, key: String, all: List<String>): Set<String> =
  settings.getString(key, all.joinToString(",")).split(",").filter { it.isNotEmpty() }.toSet()

/** A search hit: display label + normalized map position to focus. */
private data class SearchHit(val label: String, val pos: Offset)

/**
 * The filter & search popover: a search box over fields/POIs (a hit pans+zooms the map onto it),
 * then per-section filters — fields on/off, POIs per legend category (with the category's color
 * dot), vehicles per state, and a single-select ground layer (None/Crops/Growth/Soil). Sections
 * only show while their channel is live. Anchored top-end over the map; the root tap handler keeps
 * clicks from falling through to the map gestures.
 */
@Composable
private fun BoxScope.MapFilterPanel(
  mapData: MapData?,
  mapVehicles: MapVehiclesData?,
  mapLayers: MapLayersInfo?,
  hasCourse: Boolean,
  showCourse: Boolean,
  onShowCourse: (Boolean) -> Unit,
  courseNearby: Int,
  onCourseNearby: (Int) -> Unit,
  groundLayer: String,
  onGroundLayer: (String) -> Unit,
  showFields: Boolean,
  onShowFields: (Boolean) -> Unit,
  missions: List<Mission>,
  missionChoices: Map<Int, Boolean>,
  showMissions: Boolean,
  onShowMissions: (Boolean) -> Unit,
  onShowMission: (Int, Boolean) -> Unit,
  poiCats: Set<String>,
  onPoiCats: (Set<String>) -> Unit,
  vehStates: Set<String>,
  onVehStates: (Set<String>) -> Unit,
  query: String,
  onQuery: (String) -> Unit,
  onFocus: (Offset) -> Unit,
  onReset: suspend () -> Boolean,
) {
  Column(
    Modifier
      .align(Alignment.TopEnd)
      .padding(6.dp)
      .width(210.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .pointerInput(Unit) { detectTapGestures {} }
      .padding(8.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    BasicTextField(
      value = query,
      onValueChange = onQuery,
      singleLine = true,
      textStyle = TextStyle(fontSize = 13.sp, color = VdtColors.TextDark),
      modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .background(VdtColors.White)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
        .padding(horizontal = 8.dp, vertical = 6.dp),
      decorationBox = { inner ->
        Box {
          if (query.isEmpty()) {
            Text("Search field / POI…", fontSize = 13.sp, color = VdtColors.DarkGray)
          }
          inner()
        }
      },
    )

    if (query.isNotBlank()) {
      val hits =
        remember(query, mapData) {
          val q = query.trim().lowercase()
          buildList {
            for (field in mapData?.fields ?: emptyList()) {
              val label = field.name.ifBlank { field.id.toString() }
              if (label.lowercase().contains(q)) add(SearchHit("Field $label", Offset(field.labelX, field.labelZ)))
            }
            for (poi in mapData?.pois ?: emptyList()) {
              if (poi.name.lowercase().contains(q)) add(SearchHit(poi.name, Offset(poi.posX, poi.posZ)))
            }
          }.take(6)
        }
      if (hits.isEmpty()) {
        Text("No matches", fontSize = 12.sp, color = VdtColors.DarkGray)
      }
      for (hit in hits) {
        Text(
          hit.label,
          fontSize = 13.sp,
          color = VdtColors.TextDark,
          modifier = Modifier.fillMaxWidth().clickableNoRipple { onFocus(hit.pos) }.padding(vertical = 2.dp),
        )
      }
    }

    if (mapData != null) {
      FilterRow("Fields", checked = showFields) { onShowFields(it) }
      FilterSectionHeader("POIs", PoiCategories, poiCats, onPoiCats)
      for (category in PoiCategories) {
        FilterRow(
          poiCategoryLabel(category),
          checked = category in poiCats,
          dot = poiCategoryColor(category),
        ) { on -> onPoiCats(if (on) poiCats + category else poiCats - category) }
      }
    }

    // Only while this farm has contracts of its own: with none, the section would switch nothing. In
    // multiplayer it is what gets a colleague's contract markers off your map. A collected contract
    // keeps its row even though it is off the map by default — that row is how it is put back.
    if (missions.isNotEmpty()) {
      FilterRow("Contracts", checked = showMissions, dot = VdtColors.ProgressBlue) { onShowMissions(it) }
      // One row per contract under the switch that draws them all, the same way the course range sits
      // under the course. Each carries its marker's colour, so the row and the circle on the map are
      // obviously the same thing — which is the whole point of picking one out of three.
      if (showMissions) {
        for (mission in missions) {
          FilterRow(
            missionFilterLabel(mission),
            checked = isMissionShown(mission, missionChoices),
            dot = missionColor(mission),
            indent = 22.dp,
          ) { on -> onShowMission(mission.id, on) }
        }
      }
    }

    // Only while there is a course to hide: off a field the row would be a switch for nothing.
    if (hasCourse) {
      FilterRow("Guidance course", checked = showCourse, dot = VdtColors.Red) { onShowCourse(it) }
      // Under the row it narrows, and only while the course is on — it is a property of what that
      // switch draws, not a filter of its own.
      if (showCourse) CourseRangeRow(courseNearby, onCourseNearby)
    }

    if (mapVehicles != null) {
      FilterSectionHeader("Vehicles", VehicleStates, vehStates, onVehStates)
      for (state in VehicleStates) {
        FilterRow(vehicleStateLabel(state), checked = state in vehStates) { on ->
          onVehStates(if (on) vehStates + state else vehStates - state)
        }
      }
    }

    // Single-select, unlike the sections above: FilterRow's checkbox is reused purely as a
    // "selected" indicator, and each row's tap sets groundLayer directly rather than toggling.
    if (mapLayers != null) {
      Text("Ground layer", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
      FilterRow("None", checked = groundLayer == NO_GROUND_LAYER) { onGroundLayer(NO_GROUND_LAYER) }
      for (layer in mapLayers.layers) {
        // The mod labels each plane from the game's own overlay selector, so a plane this app has
        // never heard of (Precision Farming's) still gets a proper, localized name.
        FilterRow(layer.label.ifBlank { groundLayerLabel(layer.id) }, checked = groundLayer == layer.id) {
          onGroundLayer(layer.id)
        }
        // Coverage is the one layer with something to clear, and the control belongs under the layer
        // it clears rather than in the panel header — it is destructive, and it means nothing while
        // some other plane is on screen. Shown only while coverage is selected, for the same reason.
        if (layer.id == COVERAGE_LAYER_ID && groundLayer == layer.id) {
          ResetCoverageRow(onReset)
        }
      }
    }
  }
}

/**
 * Clear the worked-coverage trail. Two taps, not one: it throws away a day's driving, there is no
 * undo anywhere in the pipeline, and it sits one row below the layer list where a stray tap lands.
 *
 * The second tap awaits [onReset] rather than assuming it worked. A reset that never reached the
 * server is indistinguishable from one that did — the coverage is simply still there — so the row
 * stays armed and says so, which is also what makes the retry one tap. Staying armed is why the
 * in-flight tap has to be swallowed: otherwise waiting for an answer is what invites a second one.
 */
@Composable
private fun ResetCoverageRow(onReset: suspend () -> Boolean) {
  var confirming by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  var clearing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  Row(
    Modifier.fillMaxWidth().padding(start = 22.dp).clickableNoRipple {
      if (!confirming) {
        confirming = true
      } else if (!clearing) {
        // Taps while one is in the air do nothing rather than posting a second reset: the row stays
        // armed until an answer comes back, so without this every impatient tap starts another
        // request whose result races the first. Bounded by the request timeout, so it always reopens,
        // and the row reads "Clearing…" meanwhile so an ignored tap is not a dead control.
        clearing = true
        failed = false
        scope.launch {
          val outcome = runCatching { onReset() }
          clearing = false
          // runCatching catches Throwable, so closing the popover mid-request lands here; reporting
          // that as a failed reset would be wrong.
          (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
          if (outcome.getOrDefault(false)) confirming = false else failed = true
        }
      }
    },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    val tint =
      when {
        failed -> VdtColors.Red
        confirming -> VdtColors.Amber
        else -> VdtColors.DarkGray
      }
    Icon(Icons.Filled.DeleteSweep, null, tint = tint, modifier = Modifier.size(14.dp))
    Text(
      when {
        failed -> "Reset failed — tap to retry"
        clearing -> "Clearing…"
        confirming -> "Tap again to clear"
        else -> "Reset coverage"
      },
      fontSize = 12.sp,
      fontWeight = if (confirming) FontWeight.Bold else FontWeight.Normal,
      color = tint,
    )
  }
}

/** Section title with an all/none/partial tri-state box; clicking flips between all and none. */
@Composable
private fun FilterSectionHeader(
  title: String,
  all: List<String>,
  selected: Set<String>,
  onChange: (Set<String>) -> Unit,
) {
  val allOn = all.all { it in selected }
  val icon =
    when {
      allOn -> Icons.Filled.CheckBox
      all.none { it in selected } -> Icons.Filled.CheckBoxOutlineBlank
      else -> Icons.Filled.IndeterminateCheckBox
    }
  Row(
    Modifier.fillMaxWidth().clickableNoRipple { onChange(if (allOn) emptySet() else all.toSet()) }.padding(top = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(icon, null, tint = if (allOn) VdtColors.Green else VdtColors.DarkGray, modifier = Modifier.size(16.dp))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
  }
}

/** How much of the course to draw, as swaths either side of the machine — 0 being the whole field. */
private val CourseRanges = listOf(0 to "All", 1 to "±1", 2 to "±2", 3 to "±3")

/**
 * Narrow the course to the lines around the machine.
 *
 * A field course is dozens of lines, and on a worked field under a coverage layer the far ones are
 * clutter over the one thing being steered by. This is the terminal's answer to that: the line you
 * are on and the neighbours you will turn onto, and nothing else. See [CourseOverlay] for why the
 * window is measured on the ground rather than in segment indices.
 */
@Composable
private fun CourseRangeRow(nearby: Int, onNearby: (Int) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(start = 22.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("Lines", fontSize = 12.sp, color = VdtColors.DarkGray)
    for ((value, label) in CourseRanges) {
      val on = value == nearby
      Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) VdtColors.Accent else VdtColors.DarkGray,
        modifier = Modifier.clickableNoRipple { onNearby(value) },
      )
    }
  }
}

/** One filter line: checkbox icon, optional legend color dot, label. */
@Composable
private fun FilterRow(
  label: String,
  checked: Boolean,
  dot: Color? = null,
  indent: Dp = 0.dp,
  onToggle: (Boolean) -> Unit,
) {
  Row(
    // Indent inside the click handler, not outside it: an indented row is still tappable across the
    // popover's full width, which is what a checkbox list on a touchscreen has to be.
    Modifier.fillMaxWidth().clickableNoRipple { onToggle(!checked) }.padding(start = indent),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(
      if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
      null,
      tint = if (checked) VdtColors.Green else VdtColors.DarkGray,
      modifier = Modifier.size(16.dp),
    )
    if (dot != null) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
    }
    // A contract's label carries a name the game wrote, so it can outrun the popover's width where a
    // fixed category label never does: clip it rather than let the row wrap under its own checkbox.
    Text(label, fontSize = 13.sp, color = VdtColors.TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/**
 * The field-info popup: the game's FELDINFO panel for a tapped field. Geometry rows (farmland id,
 * owner, area) come from the [MapField]; the agronomy rows (crop, growth, fertilized, warnings) and
 * the FS25_CropRotation rows come from [FieldInfoEntry] when the interval-driven `fieldInfo` channel
 * is live — [info] is null when it isn't, and the popup then shows the geometry rows alone. Anchored
 * bottom-start over the map; its own tap handler swallows clicks so they don't fall through to the
 * map gestures (and so a tap inside it doesn't close it).
 */
@Composable
private fun BoxScope.FieldInfoPopup(
  field: MapField,
  info: FieldInfoEntry?,
  farms: List<MapFarm>,
  playerFarmId: Int?,
  onClose: () -> Unit,
) {
  Column(
    Modifier
      .align(Alignment.BottomStart)
      .padding(6.dp)
      .width(230.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .pointerInput(Unit) { detectTapGestures {} }
      .heightIn(max = 320.dp)
      .padding(8.dp),
  ) {
    // Fixed header: stays pinned while the rows below scroll.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Field ${field.name.ifBlank { field.id.toString() }}",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = VdtColors.TextDark,
        modifier = Modifier.weight(1f),
      )
      Icon(
        Icons.Filled.Close,
        "close",
        tint = VdtColors.DarkGray,
        modifier = Modifier.size(16.dp).clickableNoRipple(onClose),
      )
    }

    // Scrollable body: the value rows overflow into this region, the header stays put.
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 3.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      InfoRow("Farmland", (field.farmlandId ?: field.id).toString())
      InfoRow("Owner", ownerLabel(field.ownerFarmId, playerFarmId, farms))
      if (field.areaHa > 0f) InfoRow("Area", "${field.areaHa} ha")

      if (info != null) {
        if (info.crop.isNotBlank()) InfoRow("Crop", info.crop)
        val growth = growthLabel(info.growth)
        if (growth.isNotBlank()) InfoRow("Growth", growth)
        if (info.maxGrowthState > 0) InfoRow("Stage", "${info.growthState} / ${info.maxGrowthState}")
        info.yieldBonusPercent?.let { InfoRow("Yield bonus", "+ $it %") }
        info.sprayLevelPercent?.let { InfoRow("Fertilized", "$it %") }
        if (info.weed.isNotBlank()) InfoRow("Weeds", info.weed)
        if (info.needsPlowing) InfoRow("Needs plowing", "", warning = true)
        if (info.needsLime) InfoRow("Needs lime", "", warning = true)
        if (info.needsRolling) InfoRow("Needs rolling", "", warning = true)
        info.cropRotation?.let { CropRotationRows(it) }
      }
    }
  }
}

/** The FS25_CropRotation section of the popup: a header plus the mod's per-field history rows. */
@Composable
private fun CropRotationRows(cr: FieldCropRotation) {
  Column(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text("Crop Rotation", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
    if (cr.lastCrop.isNotBlank()) InfoRow("Last crop", cr.lastCrop)
    if (cr.prevCrop.isNotBlank()) InfoRow("Previous crop", cr.prevCrop)
    cr.yieldPercent?.let { InfoRow("Rotation yield", "$it %") }
    InfoRow("Catch crop", cr.catchCrop?.ifBlank { null } ?: "None")
  }
}

/** One label/value line in the field-info popup; a [warning] row is the label alone, highlighted. */
@Composable
private fun InfoRow(label: String, value: String, warning: Boolean = false) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      fontSize = 12.sp,
      color = if (warning) VdtColors.Red else VdtColors.DarkGray,
      fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal,
      modifier = Modifier.weight(1f),
    )
    if (value.isNotBlank()) {
      Text(value, fontSize = 12.sp, color = VdtColors.TextDark, fontWeight = FontWeight.Medium)
    }
  }
}

/** Game growth-map token -> readable label (mirrors `PlayerHUDUpdater`'s growth text ladder). */
private fun growthLabel(token: String): String = when (token) {
  "growing" -> "Growing"
  "readyToPrepare" -> "Ready to prepare"
  "readyToHarvest" -> "Ready to harvest"
  "cut" -> "Cut"
  "withered" -> "Withered"
  else -> ""
}

/** Owner display: "You" for the player's farm, the farm name for another, "Not owned" for none. */
private fun ownerLabel(ownerFarmId: Int?, playerFarmId: Int?, farms: List<MapFarm>): String {
  if (ownerFarmId == null) return "Not owned"
  if (playerFarmId != null && ownerFarmId == playerFarmId) return "You"
  val name = farms.firstOrNull { it.id == ownerFarmId }?.name
  return if (!name.isNullOrBlank()) name else "Farm $ownerFarmId"
}

/**
 * Translation that places the player at [anchor] for the given side length and scale — the box centre
 * north-up, lower down the screen in course-up.
 */
private fun anchorOffset(side: Float, player: Player?, scale: Float, anchor: Offset): Offset = if (player != null) {
  MapProjection.anchoredAt(Offset(player.posX, player.posZ), anchor, side, scale)
} else {
  Offset.Zero
}

/**
 * Click handler without the material ripple (icons act as buttons here). Uses the semantic
 * [clickable] modifier — not a raw `pointerInput` — so header actions, search results, and filter
 * rows stay keyboard- and screen-reader-activatable. `indication = null` drops the ripple; the null
 * [interactionSource] lets `clickable` lazily manage its own, so there is nothing to key on.
 *
 * Internal rather than private so the guidance strip in `Navigation.kt` — chrome that sits on the
 * map and has to look and behave like the header icons around it — shares this one implementation.
 */
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
  this.clickable(interactionSource = null, indication = null, onClick = onClick)
