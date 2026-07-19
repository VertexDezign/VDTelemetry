package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.AnimalsPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore

/**
 * The Animals app: the local farm's owned animal husbandries (pens). Base-game data, so it is always
 * available (the panel renders its own waiting/empty states). Read-only overview — productivity,
 * animal counts, condition bars, and the per-group animal breakdown.
 */
object AnimalsApp : VdtApp {
  override val id = "animals"
  override val title = "Animals"
  override val icon: ImageVector = Icons.Filled.Pets

  @Composable
  override fun FullPage(modifier: Modifier) {
    val husbandry by LocalVdtStore.current.husbandry.collectAsState()
    AnimalsPanel(husbandry, modifier)
  }
}
