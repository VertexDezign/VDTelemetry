package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
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
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FieldCropRotation
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldInfoEntry
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapFarm
import net.vertexdezign.vdt.model.MapField
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.MapLayersInfo
import net.vertexdezign.vdt.model.MapVehicle
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.Pda
import net.vertexdezign.vdt.model.Player
import org.jetbrains.skia.Image
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 16f

// Above this zoom the overlay shows secondary text (field area, POI names) — below it only the
// always-on field numbers and POI dots, so a zoomed-out map doesn't drown in labels.
private const val DETAIL_ZOOM = 2f

// Max on-screen distance from a field's number label at which a tap opens that field's info popup.
private val FIELD_TAP_RADIUS_DP = 20.dp

// Persistence keys. The `vdt.` prefix namespaces them within the origin's storage, which the whole
// page is sharing; each is read and written in separate places, so name them once.
private const val KEY_ZOOM = "vdt.zoom"
private const val KEY_AUTO_CENTER = "vdt.autoCenter"
private const val KEY_SHOW_FIELDS = "vdt.showFields"
private const val KEY_POI_CATS = "vdt.poiCats"
private const val KEY_VEH_STATES = "vdt.vehStates"
private const val KEY_GROUND_LAYER = "vdt.groundLayer"

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
 * churns every sweep (a new [MapLayersInfo.version]), so folding it into the same map would leak
 * unboundedly across a session instead of just holding the one most-recently-shown layer.
 */
private var layerImageCache: Pair<String, ImageBitmap>? = null

/** Shared with the caches above: outliving the panel is the whole point, so it can't be `remember`ed. */
private val mapImageClient by lazy { HttpClient() }

/**
 * Map panel: loads the PDA map image from the server, supports pan/zoom, draws the player marker
 * (position + heading), and auto-centers on the player until the user pans. On top of the image it
 * overlays the map channels' data: field outlines + number labels ([MapData]), POI dots, and
 * vehicle markers ([MapVehiclesData]), filtered per category/state through the filter popover
 * (Tune button), which also hosts a field/POI search that pans the map to a hit, plus an optional
 * ground-layer raster ([MapLayersInfo]: crops/growth/soil, single-select, with its own legend).
 * Zoom, auto-center, the filter selections, and the selected ground layer are persisted. Port of
 * the React `MapPanel` (no map library — a single custom composable).
 */
@Composable
fun MapPanel(
  mapUrl: String,
  pda: Pda?,
  heading: Int,
  sampleIntervalMs: Int,
  settings: Settings,
  modifier: Modifier = Modifier,
  mapData: MapData? = null,
  mapVehicles: MapVehiclesData? = null,
  fieldInfo: FieldInfoData? = null,
  mapLayerUrl: String = "",
  mapLayers: MapLayersInfo? = null,
) {
  var scale by remember { mutableStateOf(settings.getFloat(KEY_ZOOM, 1f)) }
  var autoCenter by remember { mutableStateOf(settings.getBoolean(KEY_AUTO_CENTER, true)) }
  var showFields by remember { mutableStateOf(settings.getBoolean(KEY_SHOW_FIELDS, true)) }
  var poiCats by remember { mutableStateOf(loadFilterSet(settings, KEY_POI_CATS, PoiCategories)) }
  var vehStates by remember { mutableStateOf(loadFilterSet(settings, KEY_VEH_STATES, VehicleStates)) }
  var groundLayer by remember { mutableStateOf(settings.getString(KEY_GROUND_LAYER, "none")) }
  var filterOpen by remember { mutableStateOf(false) }
  // The field whose info popup is open (its id / farmland number), or null when none. Set by tapping
  // a field label, cleared by tapping empty map or the popup's close button.
  var selectedFieldId by remember { mutableStateOf<Int?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  // Normalized position of the last search hit; drawn as a ring until the query is cleared.
  var highlight by remember { mutableStateOf<Offset?>(null) }
  var dragOffset by remember { mutableStateOf(Offset.Zero) }
  var sidePx by remember { mutableFloatStateOf(0f) }
  val player = pda?.player

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

  // Zoom by [factor] while keeping the given focal point (screen coords relative to the map's
  // top-left) pinned on screen. Used by the header +/- buttons with focal = viewport centre.
  fun zoomAround(factor: Float, focalX: Float, focalY: Float) {
    val base = if (autoCenter) centeredOffset(sidePx, player, scale) else dragOffset
    val newScale = (scale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val f = newScale / scale
    dragOffset = Offset(focalX - (focalX - base.x) * f, focalY - (focalY - base.y) * f)
    scale = newScale
  }

  // Center the map on a search hit (normalized coords), zoomed in far enough that its label shows,
  // and ring-highlight it. Panning to a target naturally ends auto-centering.
  fun focusOn(norm: Offset) {
    val newScale = scale.coerceAtLeast(DETAIL_ZOOM)
    scale = newScale
    autoCenter = false
    dragOffset = Offset(sidePx / 2f - norm.x * sidePx * newScale, sidePx / 2f - norm.y * sidePx * newScale)
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
  LaunchedEffect(scale) { settings.putFloat(KEY_ZOOM, scale) }
  LaunchedEffect(autoCenter) { settings.putBoolean(KEY_AUTO_CENTER, autoCenter) }
  LaunchedEffect(showFields) { settings.putBoolean(KEY_SHOW_FIELDS, showFields) }
  LaunchedEffect(poiCats) { settings.putString(KEY_POI_CATS, poiCats.joinToString(",")) }
  LaunchedEffect(vehStates) { settings.putString(KEY_VEH_STATES, vehStates.joinToString(",")) }
  LaunchedEffect(groundLayer) { settings.putString(KEY_GROUND_LAYER, groundLayer) }

  // The selected layer's slim info (legend + version), or null when unselected / not offered by the
  // current data -- the persisted id simply draws nothing until it reappears (edge case in the plan).
  val activeLayerInfo = mapLayers?.layers?.find { it.id == groundLayer }
  val layerKey = activeLayerInfo?.let { "$mapLayerUrl/$groundLayer|${mapLayers.version}" }
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
    layerImageCache?.let { (cachedKey, cachedBitmap) ->
      if (cachedKey == layerKey) {
        layerBitmap = requestedLayer to cachedBitmap
        return@LaunchedEffect
      }
    }
    runCatching {
      val bytes = mapImageClient.get("$mapLayerUrl/$requestedLayer?v=${mapLayers.version}").readRawBytes()
      Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.onSuccess {
      layerImageCache = layerKey to it
      layerBitmap = requestedLayer to it
    }
    // On failure the previous layerBitmap is left in place -- no flicker to blank on a transient miss.
  }

  Panel(
    title = "Map",
    icon = Icons.Filled.Map,
    modifier = modifier,
    headerActions = {
      if (mapData != null || mapVehicles != null || mapLayers != null) {
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
      val applied =
        if (autoCenter && player != null) {
          Offset(side / 2f - animNorm.x * side * scale, side / 2f - animNorm.y * side * scale)
        } else {
          dragOffset
        }
      // Let the long-lived gesture read the current offset without restarting the pointer input.
      val currentApplied by rememberUpdatedState(applied)

      androidx.compose.foundation.layout.Box(
        Modifier
          .size(with(density) { side.toDp() })
          .align(Alignment.Center)
          .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
              // Continue from the current on-screen offset; a pan ends centering.
              val base = if (autoCenter) currentApplied else dragOffset
              if (pan != Offset.Zero) autoCenter = false
              val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
              val f = newScale / scale
              // Zoom around the gesture centroid so the point under the fingers stays put.
              dragOffset =
                Offset(
                  centroid.x - (centroid.x - base.x) * f + pan.x,
                  centroid.y - (centroid.y - base.y) * f + pan.y,
                )
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
            // live in — so a label is projected with the overlay's exact toScreen math
            // (norm*side*scale + applied) and matched within a constant on-screen radius.
            detectTapGestures { tap ->
              // Only fields that are actually drawn are tappable — with the overlay hidden there are
              // no labels on screen, so a tap must not open a popup for an invisible field.
              val fields = mapData?.fields
              if (!showFields || fields.isNullOrEmpty()) {
                selectedFieldId = null
                return@detectTapGestures
              }
              val factor = side * scale
              val originX = currentApplied.x
              val originY = currentApplied.y
              val radius = FIELD_TAP_RADIUS_DP.toPx()
              var bestId: Int? = null
              var bestDist = radius
              for (f in fields) {
                val d = hypot(tap.x - (f.labelX * factor + originX), tap.y - (f.labelZ * factor + originY))
                if (d <= bestDist) {
                  bestDist = d
                  bestId = f.id
                }
              }
              selectedFieldId = bestId
            }
          }.graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
            translationX = applied.x
            translationY = applied.y
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

      // Map-data overlay (field outlines/labels + POI markers): like the player marker it lives
      // OUTSIDE the zoom-scaled graphicsLayer — the layer rasterizes, so vectors inside it blur at
      // high zoom. The outlines are re-projected each draw under the same transform as the image
      // (norm * side * scale + translation), the labels/markers stay constant-size.
      if (mapData != null || mapVehicles != null) {
        MapDataOverlay(
          mapData,
          mapVehicles,
          player?.farmId,
          side,
          scale,
          applied,
          showFields,
          poiCats,
          vehStates,
          highlight,
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
                IntOffset(
                  (animNorm.x * side * scale + applied.x - 12.dp.toPx()).roundToInt(),
                  (animNorm.y * side * scale + applied.y - 12.dp.toPx()).roundToInt(),
                )
              }.rotate(animHeading),
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
        GroundLayerLegend(activeLayerInfo.legend, side)
      }

      // Filter & search popover, on top of everything map-related.
      if (filterOpen && (mapData != null || mapVehicles != null || mapLayers != null)) {
        MapFilterPanel(
          mapData = mapData,
          mapVehicles = mapVehicles,
          mapLayers = mapLayers,
          groundLayer = groundLayer,
          onGroundLayer = { groundLayer = it },
          showFields = showFields,
          onShowFields = { showFields = it },
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
        )
      }
    }
  }
}

// Points projected further than this (px) outside the canvas are culled before any text measuring.
private const val OVERLAY_CULL_MARGIN = 80f

// VehicleHotspot.TYPE tokens that are driven and get a heading arrow; everything else (trailer,
// tool, toolTrailed, cutter, other, and unknown future types) is equipment and gets a plain square.
private val DrivableVehicleTypes =
  setOf("tractor", "truck", "car", "harvester", "wheelloader", "horse", "train", "motorbike", "woodHarvester", "boat")

/**
 * The map-data overlay: field polygons + number labels, POI dots, and vehicle markers, drawn into
 * the same side×side box as the map image. The polygons are vector paths in normalized [0,1]
 * space, re-projected each draw under the image's exact transform (screen = norm * side * scale +
 * translation) with a zoom-compensated stroke — so they hug the map at any zoom but keep a
 * constant on-screen line width. Labels, POI dots and vehicle arrows are constant-size like the
 * player marker; secondary text (field area, POI/vehicle names) only appears above [DETAIL_ZOOM].
 */
@Composable
private fun BoxScope.MapDataOverlay(
  mapData: MapData?,
  mapVehicles: MapVehiclesData?,
  playerFarmId: Int?,
  side: Float,
  scale: Float,
  applied: Offset,
  showFields: Boolean,
  poiCats: Set<String>,
  vehStates: Set<String>,
  highlight: Offset?,
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

  Canvas(Modifier.size(with(density) { side.toDp() }).align(Alignment.Center)) {
    val factor = side * scale

    fun toScreen(normX: Float, normZ: Float) = Offset(normX * factor + applied.x, normZ * factor + applied.y)

    fun onCanvas(pos: Offset) = pos.x in -OVERLAY_CULL_MARGIN..side + OVERLAY_CULL_MARGIN &&
      pos.y in -OVERLAY_CULL_MARGIN..side + OVERLAY_CULL_MARGIN

    if (showFields && mapData != null) {
      withTransform({
        translate(applied.x, applied.y)
        scale(factor, factor, pivot = Offset.Zero)
      }) {
        // Stroke width divided back out of the transform: geometry scales, the line doesn't.
        val strokeWidth = 1.5.dp.toPx() / factor
        for ((field, path) in fieldPaths) {
          val tint = fieldTint(field, playerFarmId, farmColors)
          drawPath(path, tint.copy(alpha = 0.10f))
          drawPath(path, tint, style = Stroke(width = strokeWidth))
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
            rotate(degrees = v.heading.toFloat(), pivot = Offset.Zero)
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
 */
@Composable
private fun BoxScope.GroundLayerLegend(legend: List<MapLayerLegendEntry>, side: Float) {
  val density = LocalDensity.current
  Column(
    Modifier
      .align(Alignment.BottomStart)
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
private fun loadFilterSet(settings: Settings, key: String, all: List<String>): Set<String> =
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
  groundLayer: String,
  onGroundLayer: (String) -> Unit,
  showFields: Boolean,
  onShowFields: (Boolean) -> Unit,
  poiCats: Set<String>,
  onPoiCats: (Set<String>) -> Unit,
  vehStates: Set<String>,
  onVehStates: (Set<String>) -> Unit,
  query: String,
  onQuery: (String) -> Unit,
  onFocus: (Offset) -> Unit,
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
      FilterRow("None", checked = groundLayer == "none") { onGroundLayer("none") }
      for (layer in mapLayers.layers) {
        FilterRow(groundLayerLabel(layer.id), checked = groundLayer == layer.id) { onGroundLayer(layer.id) }
      }
    }
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

/** One filter line: checkbox icon, optional legend color dot, label. */
@Composable
private fun FilterRow(label: String, checked: Boolean, dot: Color? = null, onToggle: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickableNoRipple { onToggle(!checked) },
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
    Text(label, fontSize = 13.sp, color = VdtColors.TextDark)
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

/** Translation that places the player at the box centre for the given side length and scale. */
private fun centeredOffset(side: Float, player: Player?, scale: Float): Offset = if (player != null) {
  Offset(side / 2f - player.posX * side * scale, side / 2f - player.posZ * side * scale)
} else {
  Offset.Zero
}

/**
 * Click handler without the material ripple (icons act as buttons here). Uses the semantic
 * [clickable] modifier — not a raw `pointerInput` — so header actions, search results, and filter
 * rows stay keyboard- and screen-reader-activatable. `indication = null` drops the ripple; the null
 * [interactionSource] lets `clickable` lazily manage its own, so there is nothing to key on.
 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
  this.clickable(interactionSource = null, indication = null, onClick = onClick)
