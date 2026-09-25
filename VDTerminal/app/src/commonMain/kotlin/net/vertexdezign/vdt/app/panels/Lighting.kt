package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** The largest a lamp button gets — [StatusIconButton]'s own default. */
private val MAX_BUTTON = 48.dp

/**
 * Below this the buttons stop fitting over the schematic without touching, and the panel switches to
 * a plain grid ([LampGrid]): a tractor picture with its buttons shrunk to specks is worse than no
 * picture.
 */
private val MIN_BUTTON = 32.dp

/** Aspect ratio (w/h) of the trimmed `mb_trac.png` (1429×1259, padding cropped off). */
private const val IMAGE_ASPECT = 1429f / 1259f

/**
 * The tightest pair on the schematic, as a share of the art's width: high and low beam, one above the
 * other, 0.158 of the height apart — 0.139 of the width. A button this share of the width, less a gap,
 * is the biggest that keeps every pair apart.
 */
private const val TIGHTEST_PAIR = 0.158f / IMAGE_ASPECT

/** One switch: where it sits on the schematic (fractions of the art's box), what it shows, what it does. */
private class Lamp(
  val fx: Float,
  val fy: Float,
  val icon: ImageVector,
  val description: String,
  val active: Boolean,
  val onClick: () -> Unit,
)

/**
 * Lighting panel. Toggles are overlaid on a semi-transparent tractor schematic. The schematic image
 * has been trimmed to the tractor art (the source PNG had ~17% transparent padding top and bottom),
 * so the container is sized to the art's aspect ratio and fitted to the panel — filling it far
 * better than a padded square did. Button positions are expressed as fractions of that box; they
 * were re-mapped from the original React panel's percentages into the cropped image's coordinates.
 *
 * The buttons are sized from the art, not fixed: at a fixed 48dp the three indicators overlapped once
 * the art was narrower than ~300dp, which is most tiles on a phone. When even [MIN_BUTTON] no longer
 * fits between the tightest pair, the picture goes and the eight switches are laid out as a grid.
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
  val beaconOn = lights?.beaconLight == true
  val workFront = lights?.workLight?.front == true
  val workBack = lights?.workLight?.back == true
  val highBeam = lights?.light?.highBeam == true
  val lowBeam = lights?.light?.lowBeam == true

  // The cluster's own glyphs rather than Material's: every one of these buttons is a lamp the telltale
  // band also draws, and a panel where "work lights" is a lightbulb and the band shows a tractor
  // throwing a beam is two names for one switch. In reading order, which is also the grid's order.
  val lamps =
    listOf(
      // Beacon — top of cabin.
      Lamp(0.591f, 0.085f, ClusterIcons.Beacon, "beacon", beaconOn) { setLight(LightTarget.BEACON, !beaconOn) },
      // Work lights — front / back of cabin.
      Lamp(0.419f, 0.207f, ClusterIcons.WorkFront, "front work lights", workFront) {
        setLight(LightTarget.WORK_FRONT, !workFront)
      },
      Lamp(0.763f, 0.207f, ClusterIcons.WorkRear, "rear work lights", workBack) {
        setLight(LightTarget.WORK_BACK, !workBack)
      },
      // Head lights — upper / lower front.
      Lamp(0.075f, 0.415f, ClusterIcons.HighBeam, "high beam", highBeam) { setLight(LightTarget.HIGH_BEAM, !highBeam) },
      Lamp(0.075f, 0.573f, ClusterIcons.LowBeam, "low beam", lowBeam) { setLight(LightTarget.LOW_BEAM, !lowBeam) },
      // Indicators — bottom row. One enum state; tapping the active signal clears it.
      Lamp(0.344f, 0.915f, ClusterIcons.TurnLeft, "left indicator", leftActive) {
        setTurn(if (leftActive) TurnLightState.OFF else TurnLightState.LEFT)
      },
      Lamp(0.505f, 0.915f, ClusterIcons.Hazard, "hazard lights", hazardActive) {
        setTurn(if (hazardActive) TurnLightState.OFF else TurnLightState.HAZARD)
      },
      Lamp(0.667f, 0.915f, ClusterIcons.TurnRight, "right indicator", rightActive) {
        setTurn(if (rightActive) TurnLightState.OFF else TurnLightState.RIGHT)
      },
    )

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
      val button = minOf(boxW * TIGHTEST_PAIR - 4.dp, MAX_BUTTON)

      if (button < MIN_BUTTON) {
        LampGrid(lamps, maxWidth, maxHeight)
      } else {
        Box(Modifier.size(boxW, boxH).align(Alignment.Center)) {
          Image(
            painter = painterResource(Res.drawable.mb_trac),
            contentDescription = "Tractor Schematic",
            modifier = Modifier.fillMaxSize().alpha(0.5f),
            contentScale = ContentScale.Fit,
          )
          for (lamp in lamps) {
            // Centred on its lamp, but kept inside the art's box: the headlights sit 7% in from the
            // edge and the indicators 8% up from the bottom, so a big button would hang off the panel.
            val x = (boxW * lamp.fx - button / 2).coerceIn(0.dp, boxW - button)
            val y = (boxH * lamp.fy - button / 2).coerceIn(0.dp, boxH - button)
            LampButton(lamp, button, Modifier.align(Alignment.TopStart).offset(x, y))
          }
        }
      }
    }
  }
}

/**
 * The eight switches without the picture, for a tile too small to carry it: four across and two down
 * in a wide tile, two across and four down in a tall one, each as big as its cell allows.
 */
@Composable
private fun BoxScope.LampGrid(lamps: List<Lamp>, width: Dp, height: Dp) {
  val wide = width >= height
  val columns = if (wide) 4 else 2
  val rows = lamps.size / columns
  val gap = 6.dp
  val button = minOf((width - gap * (columns - 1)) / columns, (height - gap * (rows - 1)) / rows, MAX_BUTTON)
  Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(gap)) {
    for (row in lamps.chunked(columns)) {
      Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (lamp in row) LampButton(lamp, button)
      }
    }
  }
}

@Composable
private fun LampButton(lamp: Lamp, diameter: Dp, modifier: Modifier = Modifier) {
  StatusIconButton(
    lamp.icon,
    modifier = modifier.semantics { contentDescription = lamp.description },
    active = lamp.active,
    color = StatusColor.Green,
    round = true,
    diameter = diameter,
    onClick = lamp.onClick,
  )
}
