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
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.LightTarget
import net.vertexdezign.vdt.TurnLightState
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.resources.Res
import net.vertexdezign.vdt.app.resources.mb_trac
import net.vertexdezign.vdt.model.Vehicle
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
fun Lighting(vehicle: Vehicle, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  val lights = vehicle.lights

  // Each tap sends an ABSOLUTE target computed from the state we're rendering (not a toggle), so the
  // command is idempotent over the lossy file channel. See ClientMessage.
  fun setLight(light: LightTarget, on: Boolean) = onCommand(ClientMessage.SetLight(light, on))
  fun setTurn(state: TurnLightState) = onCommand(ClientMessage.SetTurnLight(state))

  val ind = lights?.indicator
  // "Pure" left/right = the signal without hazard (hazard lights both indicators). Tapping a signal
  // that's already the sole active one turns signalling off; otherwise it selects that signal.
  val leftActive = ind?.left == true && ind.hazard != true
  val rightActive = ind?.right == true && ind.hazard != true
  val hazardActive = ind?.hazard == true

  Panel(title = "Lighting", icon = Icons.Filled.Lightbulb, modifier = modifier) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      // Largest box with the image's aspect that fits the panel (letterbox on the long axis).
      val boxW: Dp
      val boxH: Dp
      if (maxWidth / maxHeight >= IMAGE_ASPECT) {
        boxH = maxHeight
        boxW = maxHeight * IMAGE_ASPECT
      } else {
        boxW = maxWidth
        boxH = maxWidth / IMAGE_ASPECT
      }

      Box(Modifier.size(boxW, boxH).align(Alignment.Center)) {
        Image(
          painter = painterResource(Res.drawable.mb_trac),
          contentDescription = "Tractor Schematic",
          modifier = Modifier.fillMaxSize().alpha(0.5f),
          contentScale = ContentScale.Fit,
        )

        // Beacon — top of cabin.
        val beaconOn = lights?.beaconLight == true
        light(boxW, boxH, 0.591f, 0.085f, Icons.Filled.Warning, beaconOn) {
          setLight(LightTarget.BEACON, !beaconOn)
        }
        // Work lights — front / back of cabin.
        val workFront = lights?.workLight?.front == true
        val workBack = lights?.workLight?.back == true
        light(boxW, boxH, 0.419f, 0.207f, Icons.Filled.Lightbulb, workFront) {
          setLight(LightTarget.WORK_FRONT, !workFront)
        }
        light(boxW, boxH, 0.763f, 0.207f, Icons.Filled.Lightbulb, workBack) {
          setLight(LightTarget.WORK_BACK, !workBack)
        }
        // Head lights — upper / lower front.
        val highBeam = lights?.light?.highBeam == true
        val lowBeam = lights?.light?.lowBeam == true
        light(boxW, boxH, 0.075f, 0.415f, Icons.Filled.Lightbulb, highBeam) {
          setLight(LightTarget.HIGH_BEAM, !highBeam)
        }
        light(boxW, boxH, 0.075f, 0.573f, Icons.Filled.Lightbulb, lowBeam) {
          setLight(LightTarget.LOW_BEAM, !lowBeam)
        }
        // Indicators — bottom row. One enum state; tapping the active signal clears it.
        light(boxW, boxH, 0.344f, 0.915f, Icons.AutoMirrored.Filled.ArrowBack, leftActive) {
          setTurn(if (leftActive) TurnLightState.OFF else TurnLightState.LEFT)
        }
        light(boxW, boxH, 0.505f, 0.915f, Icons.Filled.ChangeHistory, hazardActive) {
          setTurn(if (hazardActive) TurnLightState.OFF else TurnLightState.HAZARD)
        }
        light(boxW, boxH, 0.667f, 0.915f, Icons.AutoMirrored.Filled.ArrowForward, rightActive) {
          setTurn(if (rightActive) TurnLightState.OFF else TurnLightState.RIGHT)
        }
      }
    }
  }
}

/** Places a round status button centred on the fractional point ([fx], [fy]) of the box. */
@Composable
private fun BoxScope.light(
  boxW: Dp,
  boxH: Dp,
  fx: Float,
  fy: Float,
  icon: ImageVector,
  active: Boolean,
  onClick: (() -> Unit)? = null,
) {
  Box(
    Modifier
      .align(Alignment.TopStart)
      .offset(x = boxW * fx - BUTTON_SIZE / 2, y = boxH * fy - BUTTON_SIZE / 2),
  ) {
    StatusIconButton(icon, active = active, color = StatusColor.Green, round = true, onClick = onClick)
  }
}
