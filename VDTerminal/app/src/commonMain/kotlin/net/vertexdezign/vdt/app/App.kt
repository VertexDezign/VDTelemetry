package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.VdtData
import net.vertexdezign.vdt.app.net.ConnectionState
import net.vertexdezign.vdt.app.panels.EmptyPanel
import net.vertexdezign.vdt.app.panels.EngineTransmission
import net.vertexdezign.vdt.app.panels.Footer
import net.vertexdezign.vdt.app.panels.Header
import net.vertexdezign.vdt.app.panels.Implements
import net.vertexdezign.vdt.app.panels.Lighting
import net.vertexdezign.vdt.app.panels.MapPanel
import net.vertexdezign.vdt.app.theme.VdtColors
import com.russhwolf.settings.Settings

@Composable
fun App(
    telemetry: VdtData?,
    connection: ConnectionState,
    mapUrl: String,
    settings: Settings,
    onMenuClick: () -> Unit = {},
) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(VdtColors.Light)) {
            when {
                telemetry == null -> LoadingScreen()
                else -> Dashboard(telemetry, mapUrl, settings, onMenuClick)
            }

            if (connection != ConnectionState.Connected) {
                Box(
                    Modifier.fillMaxSize().background(VdtColors.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (connection == ConnectionState.Connecting) "CONNECTING…" else "CONNECTION LOST — RECONNECTING…",
                        color = VdtColors.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("VDTERMINAL LOADING…", color = VdtColors.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Dashboard(data: VdtData, mapUrl: String, settings: Settings, onMenuClick: () -> Unit) {
    val vehicle = data.vehicle
    Column(Modifier.fillMaxSize()) {
        Header(data.environment, vehicle, onMenuClick)

        if (vehicle == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No vehicle connected", color = VdtColors.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Footer(null)
            return@Column
        }

        // 3x2 grid
        Column(Modifier.fillMaxWidth().weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cell(Modifier.weight(1f)) { MapPanel(mapUrl, data.environment?.pda, vehicle.gps?.heading ?: 0, settings) }
                Cell(Modifier.weight(1f)) { EngineTransmission(vehicle) }
                Cell(Modifier.weight(1f)) { Implements(vehicle) }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cell(Modifier.weight(1f)) { Lighting(vehicle) }
                Cell(Modifier.weight(1f)) { EmptyPanel() }
                Cell(Modifier.weight(1f)) { EmptyPanel() }
            }
        }

        Footer(vehicle)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight()) { content() }
}
