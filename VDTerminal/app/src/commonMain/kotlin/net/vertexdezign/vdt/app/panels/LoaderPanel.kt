package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Vehicle

/**
 * The loader on its own (issue #169): the same screen the ISOBUS panel opens on a loader, as a tile
 * that shows it **whatever the game has selected**.
 *
 * That is the difference from the ISOBUS tile, and the reason for a second home. The terminal follows
 * the selection, so it shows the loader only while the loader or its tool is the selected machine; a
 * driver who has the tractor selected to drive, or a trailer behind it, still wants the bucket's angle
 * in the corner of the screen. The rig is resolved the way the ISOBUS panel resolves it, diagram paths
 * included, so "set level" is addressed the same way from both.
 */
@Composable
fun LoaderPanel(vehicle: Vehicle?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  val rig = vehicle?.let { v ->
    val nodes = layoutRig(v)
    if (nodes.isNotEmpty()) {
      loaderRigOf(nodes.map { it.id to it.machine })
    } else {
      loaderRigOf(rigMachines(v).map { null to it })
    }
  }

  Panel(
    title = rig?.tool?.name ?: "Loader",
    icon = Icons.Filled.Construction,
    modifier = modifier,
    headerActions = { rig?.let { LoaderStateChip(it) } },
  ) {
    if (rig == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          if (vehicle == null) "No vehicle connected" else "No loader on this rig",
          color = VdtColors.DarkGray,
          fontSize = 12.sp,
          textAlign = TextAlign.Center,
        )
      }
    } else {
      LoaderSection(rig, onCommand, Modifier.fillMaxSize())
    }
  }
}
