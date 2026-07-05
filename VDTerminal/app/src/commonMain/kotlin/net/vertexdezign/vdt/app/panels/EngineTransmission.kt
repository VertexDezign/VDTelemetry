package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.FoldableState
import net.vertexdezign.vdt.Vehicle
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SimpleGauge
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.components.format2
import net.vertexdezign.vdt.app.theme.VdtColors

/** Engine / transmission panel: speed gauge, RPM, temps, cruise, and vehicle fill units. */
@Composable
fun EngineTransmission(vehicle: Vehicle) {
    Panel(title = "Engine and Transmission", icon = Icons.Filled.Agriculture) {
        val motor = vehicle.motor
        if (motor == null) {
            Text("No engine data", color = VdtColors.DarkGray)
            return@Panel
        }
        val cruise = vehicle.cruiseControl
        val maxSpeed = (motor.maxSpeed?.let { maxOf(it.forward ?: 0, it.backward ?: 0) } ?: 0).let { if (it <= 0) 50 else it }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Left: RPM + fuel/hr
                Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metric("${motor.rpm?.value ?: 0}", "RPM")
                    metric(usage(motor.fuel()?.usage, motor.fuel()?.unit), "FUEL/HR")
                }
                // Center: speed gauge + cruise
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SimpleGauge(
                        value = vehicle.speed?.value ?: 0f,
                        min = 0f,
                        max = maxSpeed.toFloat(),
                        unit = vehicle.speed?.unit ?: "",
                        size = 140.dp,
                        isActive = cruise?.active ?: false,
                    )
                    if (cruise != null) {
                        Text(
                            format2(cruise.targetSpeed ?: 0f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (cruise.active == true) VdtColors.Green else VdtColors.DarkGray,
                        )
                        Text("CRUISE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
                    }
                }
                // Right: water temp + def/hr
                Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metric("${motor.temperatur?.value ?: 0}${motor.temperatur?.unit ?: ""}", "WATER")
                    metric(usage(motor.def()?.usage, null), "DEF/HR")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val foldable = vehicle.foldable
                StatusIconButton(
                    icon = Icons.Filled.UnfoldMore,
                    modifier = Modifier.weight(1f),
                    active = foldable != null,
                    color = if (foldable == FoldableState.EXTENDED) StatusColor.Green else StatusColor.White,
                )
                StatusIconButton(Icons.Filled.PowerSettingsNew, Modifier.weight(1f), active = vehicle.isTurnedOn == true, color = StatusColor.Green)
                StatusIconButton(
                    if (vehicle.lowered == true) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    Modifier.weight(1f),
                    active = vehicle.lowered == true,
                    color = StatusColor.Green,
                )
            }

            FillUnitsDisplay(vehicle.fillUnits?.fillUnit ?: emptyList(), Modifier.fillMaxWidth(), spacing = 4)
        }
    }
}

private fun net.vertexdezign.vdt.Motor.fuel() = fillUnits?.fuel
private fun net.vertexdezign.vdt.Motor.def() = fillUnits?.def

private fun usage(value: Float?, unit: String?): String =
    if (value == null) "--" else "$value${unit ?: ""}"

@Composable
private fun metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.Gray)
    }
}
