package net.vertexdezign.vdt.app.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * Says that a vertically scrolling box holds more than it shows. A tablet has no scrollbar and nothing
 * else on the page invites a drag, so a list cut off at a row boundary simply looks complete.
 *
 * On each edge with content beyond it: the content fades into [background], and a chevron points the
 * way there is more to see. Shape and brightness, not hue, carry it. Tapping the hint scrolls most of
 * a page that way, for whoever does not think to drag.
 *
 * Call it inside the [Box] that hosts the scrolling content, after that content, so it draws on top.
 * It sizes to the box ([matchParentSize]), so a hint never widens a box that wraps its content.
 */
@Composable
fun BoxScope.ScrollHints(
  state: ScrollState,
  modifier: Modifier = Modifier,
  background: Color = VdtColors.Panel,
  edge: Dp = 18.dp,
) {
  Box(modifier.matchParentSize()) {
    ScrollHint(state, up = true, visible = state.canScrollBackward, background = background, edge = edge)
    ScrollHint(state, up = false, visible = state.canScrollForward, background = background, edge = edge)
  }
}

@Composable
private fun BoxScope.ScrollHint(state: ScrollState, up: Boolean, visible: Boolean, background: Color, edge: Dp) {
  // Faded rather than switched, so reaching the end reads as the hint going away, not a flicker.
  val alpha by animateFloatAsState(if (visible) 1f else 0f)
  if (alpha == 0f) return
  val scope = rememberCoroutineScope()
  val fade = if (up) {
    listOf(
      background,
      background.copy(alpha = 0f),
    )
  } else {
    listOf(background.copy(alpha = 0f), background)
  }
  Box(
    Modifier
      .align(if (up) Alignment.TopCenter else Alignment.BottomCenter)
      .fillMaxWidth()
      .height(edge)
      .alpha(alpha)
      .background(Brush.verticalGradient(fade))
      .then(
        if (visible) {
          Modifier.clickable {
            // Most of a page, so the row the reader was on stays in view as the anchor.
            val step = state.viewportSize * 0.8f
            scope.launch { state.animateScrollBy(if (up) -step else step) }
          }
        } else {
          Modifier
        },
      ),
    contentAlignment = if (up) Alignment.TopCenter else Alignment.BottomCenter,
  ) {
    Icon(
      if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
      contentDescription = if (up) LocalStrings.current.widgets.moreAbove else LocalStrings.current.widgets.moreBelow,
      tint = VdtColors.TextDark,
      modifier = Modifier.size(14.dp),
    )
  }
}
