package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors

/** Placeholder panel. Port of `EmptyPanel`. */
@Composable
fun EmptyPanel() {
    Panel(title = "ToDo", icon = Icons.Filled.Bolt) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("TODO", color = VdtColors.Gray, fontSize = 12.sp)
        }
    }
}
