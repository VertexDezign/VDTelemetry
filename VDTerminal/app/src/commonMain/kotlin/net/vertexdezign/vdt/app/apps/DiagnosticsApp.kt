package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.DiagnosticsPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore

/**
 * The Diagnostics app: the server-measured observed write cadence of every channel file. Always
 * available (it reads the server's own stats feed, not any optional mod). A read-only tool to verify
 * the mod's configured intervals / performance profile end to end.
 */
object DiagnosticsApp : VdtApp {
  override val id = "diagnostics"
  override val title = "Diagnostics"
  override val icon: ImageVector = Icons.Filled.Schedule

  @Composable
  override fun FullPage(modifier: Modifier) {
    val stats by LocalVdtStore.current.channelStats.collectAsState()
    DiagnosticsPanel(stats, modifier)
  }
}
