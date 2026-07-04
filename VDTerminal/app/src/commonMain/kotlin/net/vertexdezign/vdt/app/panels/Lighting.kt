package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.Vehicle
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.resources.Res
import net.vertexdezign.vdt.app.resources.mb_trac
import org.jetbrains.compose.resources.painterResource

/** Round status button size — kept in sync with [StatusIconButton]'s round dimensions. */
private val BUTTON_SIZE = 48.dp

/** Aspect ratio (w/h) of the trimmed `mb_trac.png` (1429×1259, padding cropped off). */
private const val IMAGE_ASPECT = 1429f / 1259f

/**
 * Lighting panel. Toggles are overlaid on a semi-transparent tractor schematic. The schematic image
 * has been trimmed to the tractor art (the source PNG had ~17% transparent padding top and bottom),
 * so the container is sized to the art's aspect ratio and fitted to the panel — filling it far
 * better than a padded square did. Button positions are expressed as fractions of that box; they
 * were re-mapped from the original React panel's percentages into the cropped image's coordinates.
 */
@Composable
fun Lighting(vehicle: Vehicle) {
    val lights = vehicle.lights
    Panel(title = "Lighting", icon = Icons.Filled.Lightbulb) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Largest box with the image's aspect that fits the panel (letterbox on the long axis).
            val boxW: Dp
            val boxH: Dp
            if (maxWidth / maxHeight >= IMAGE_ASPECT) {
                boxH = maxHeight; boxW = maxHeight * IMAGE_ASPECT
            } else {
                boxW = maxWidth; boxH = maxWidth / IMAGE_ASPECT
            }

            Box(Modifier.size(boxW, boxH).align(Alignment.Center)) {
                Image(
                    painter = painterResource(Res.drawable.mb_trac),
                    contentDescription = "Tractor Schematic",
                    modifier = Modifier.fillMaxSize().alpha(0.5f),
                    contentScale = ContentScale.Fit,
                )

                // Beacon — top of cabin.
                light(boxW, boxH, 0.591f, 0.085f, Icons.Filled.Warning, lights?.beaconLight == true)
                // Work lights — front / back of cabin.
                light(boxW, boxH, 0.419f, 0.207f, Icons.Filled.Lightbulb, lights?.workLight?.front == true)
                light(boxW, boxH, 0.763f, 0.207f, Icons.Filled.Lightbulb, lights?.workLight?.back == true)
                // Head lights — upper / lower front.
                light(boxW, boxH, 0.075f, 0.415f, Icons.Filled.Lightbulb, lights?.light?.highBeam == true)
                light(boxW, boxH, 0.075f, 0.573f, Icons.Filled.Lightbulb, lights?.light?.lowBeam == true)
                // Indicators — bottom row.
                light(boxW, boxH, 0.344f, 0.915f, Icons.AutoMirrored.Filled.ArrowBack, lights?.indicator?.left == true)
                light(boxW, boxH, 0.505f, 0.915f, Icons.Filled.ChangeHistory, lights?.indicator?.hazard == true)
                light(boxW, boxH, 0.667f, 0.915f, Icons.AutoMirrored.Filled.ArrowForward, lights?.indicator?.right == true)
            }
        }
    }
}

/** Places a round status button centred on the fractional point ([fx], [fy]) of the box. */
@Composable
private fun BoxScope.light(boxW: Dp, boxH: Dp, fx: Float, fy: Float, icon: ImageVector, active: Boolean) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = boxW * fx - BUTTON_SIZE / 2, y = boxH * fy - BUTTON_SIZE / 2),
    ) {
        StatusIconButton(icon, active = active, color = StatusColor.Green, round = true)
    }
}
