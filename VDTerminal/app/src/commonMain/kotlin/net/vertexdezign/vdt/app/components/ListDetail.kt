package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/** Below this the detail beside a list is too narrow to read, and the two take turns instead. */
private val MIN_DETAIL_WIDTH = 320.dp

/**
 * A list of things beside the one picked — pens, stores, machines, fields, contracts, commodities.
 *
 * With room for both, the list is [listWidth] wide on the left and the detail takes the rest, as it
 * always has. Without — a phone, or a narrow tile — the two take turns: the list alone, full width,
 * until an entry is tapped ([detailOpen]), then the detail alone under a bar that leads back to the
 * list ([onBack], named by [backLabel]). A fixed-width list beside a detail squeezed to 60dp was what a
 * phone used to get.
 *
 * [list] is handed the modifier that sizes it; it applies that to its own outermost layout and adds
 * its scrolling after. Which entry is shown stays the caller's: in the side-by-side form the detail
 * shows the current pick whether or not [detailOpen] is set, the way these screens always behaved.
 */
@Composable
fun ListDetail(
  listWidth: Dp,
  detailOpen: Boolean,
  backLabel: String,
  onBack: () -> Unit,
  list: @Composable (Modifier) -> Unit,
  modifier: Modifier = Modifier,
  detail: @Composable () -> Unit,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val sideBySide = maxWidth >= listWidth + MIN_DETAIL_WIDTH
    val showDetail = sideBySide || detailOpen
    // One call site per slot, in every form: a slot called from two branches is two different places
    // in the composition, and would lose its scroll position whenever the form changed. For the same
    // reason the list stays composed while the detail has the screen — at no width, rather than not at
    // all — so coming back to an 80-field list lands where you left it, not at the top.
    Row(Modifier.fillMaxSize()) {
      list(
        when {
          sideBySide -> Modifier.width(listWidth).fillMaxHeight().padding(end = 10.dp)
          detailOpen -> Modifier.width(0.dp).fillMaxHeight().clipToBounds()
          else -> Modifier.fillMaxSize()
        },
      )
      if (sideBySide) Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
      if (showDetail) {
        Column(
          Modifier.weight(1f).fillMaxHeight().padding(start = if (sideBySide) 10.dp else 0.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!sideBySide) BackBar(backLabel, onBack)
          Box(Modifier.fillMaxWidth().weight(1f)) { detail() }
        }
      }
    }
  }
}

/** The way back to the list when the detail has the screen to itself. */
@Composable
private fun BackBar(label: String, onBack: () -> Unit) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "back to $label", onClick = onBack)
    Text(
      label.uppercase(),
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.DarkGray,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
