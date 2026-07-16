package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.widgets.CropRotationWidget
import net.vertexdezign.vdt.app.widgets.Widget

/** FS25_CropRotation planner. Full page fills the screen; also provides the crop-rotation tile. */
object CropRotationApp : VdtApp {
  override val id = "cropRotation"
  override val title = "Crop Rotation"
  override val icon: ImageVector = Icons.Filled.Grass
  override val widgets: List<Widget> = listOf(CropRotationWidget)

  @Composable
  override fun FullPage(modifier: Modifier) = CropRotationWidget.Content(modifier)
}
