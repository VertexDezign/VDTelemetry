package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import net.vertexdezign.vdt.Pda
import net.vertexdezign.vdt.Player
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import org.jetbrains.skia.Image
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 16f

/**
 * Map panel: loads the PDA map image from the server, supports pan/zoom, draws the player marker
 * (position + heading), and auto-centers on the player until the user pans. Zoom and auto-center
 * are persisted. Port of the React `MapPanel` (no map library — a single custom composable).
 */
@Composable
fun MapPanel(mapUrl: String, pda: Pda?, heading: Int, settings: Settings) {
    var scale by remember { mutableStateOf(settings.getFloat("zoom", 1f)) }
    var autoCenter by remember { mutableStateOf(settings.getBoolean("autoCenter", true)) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var sidePx by remember { mutableStateOf(0f) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val client = remember { HttpClient() }
    val player = pda?.player

    // Zoom by [factor] while keeping the given focal point (screen coords relative to the map's
    // top-left) pinned on screen. Used by the header +/- buttons with focal = viewport centre.
    fun zoomAround(factor: Float, focalX: Float, focalY: Float) {
        val base = if (autoCenter) centeredOffset(sidePx, player, scale) else dragOffset
        val newScale = (scale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newScale / scale
        dragOffset = Offset(focalX - (focalX - base.x) * f, focalY - (focalY - base.y) * f)
        scale = newScale
    }

    LaunchedEffect(mapUrl, pda?.filename) {
        if (!pda?.filename.isNullOrBlank()) {
            runCatching {
                val bytes = client.get(mapUrl).readRawBytes()
                bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }
        }
    }
    LaunchedEffect(scale) { settings.putFloat("zoom", scale) }
    LaunchedEffect(autoCenter) { settings.putBoolean("autoCenter", autoCenter) }

    Panel(
        title = "Map",
        icon = Icons.Filled.Map,
        headerActions = {
            Icon(Icons.Filled.Remove, "zoom out", tint = VdtColors.DarkGray, modifier = Modifier.size(16.dp).clickableNoRipple { zoomAround(1f / 1.25f, sidePx / 2f, sidePx / 2f) })
            Icon(Icons.Filled.Add, "zoom in", tint = VdtColors.DarkGray, modifier = Modifier.size(16.dp).clickableNoRipple { zoomAround(1.25f, sidePx / 2f, sidePx / 2f) })
            Icon(Icons.Filled.CenterFocusStrong, "auto-center", tint = if (autoCenter) VdtColors.Green else VdtColors.DarkGray, modifier = Modifier.size(16.dp).clickableNoRipple { autoCenter = true })
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val side = with(density) { minOf(maxWidth, maxHeight).toPx() }
            LaunchedEffect(side) { sidePx = side }

            // Smoothly interpolate the player's normalized position between the ~500ms telemetry
            // updates. Only the position is animated (not the scale), so zooming stays wobble-free.
            val animNorm by animateOffsetAsState(
                targetValue = if (player != null) Offset(player.posX, player.posZ) else Offset.Zero,
                animationSpec = tween(durationMillis = 500, easing = LinearEasing),
                label = "playerNorm",
            )
            // Current translation: while auto-centering it tracks the (smoothed) player at the live
            // scale, so zoom stays locked on the player; otherwise it is the free pan/zoom offset.
            val applied = if (autoCenter && player != null) {
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
                            dragOffset = Offset(
                                centroid.x - (centroid.x - base.x) * f + pan.x,
                                centroid.y - (centroid.y - base.y) * f + pan.y,
                            )
                            scale = newScale
                        }
                    }
                    .pointerInput(Unit) {
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
                    }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = applied.x
                        translationY = applied.y
                    },
            ) {
                bitmap?.let { Image(it, contentDescription = "map", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
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
                        modifier = Modifier
                            .size(24.dp)
                            .offset {
                                IntOffset(
                                    (animNorm.x * side * scale + applied.x - 12.dp.toPx()).roundToInt(),
                                    (animNorm.y * side * scale + applied.y - 12.dp.toPx()).roundToInt(),
                                )
                            }
                            .rotate(heading.toFloat()),
                    )
                }
            }
        }
    }
}

/** Translation that places the player at the box centre for the given side length and scale. */
private fun centeredOffset(side: Float, player: Player?, scale: Float): Offset =
    if (player != null) {
        Offset(side / 2f - player.posX * side * scale, side / 2f - player.posZ * side * scale)
    } else {
        Offset.Zero
    }

/** Tap handler without the material ripple (icons act as buttons here). */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
