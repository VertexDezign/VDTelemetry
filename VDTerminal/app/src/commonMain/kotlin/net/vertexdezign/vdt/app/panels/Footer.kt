package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.floor

private val GpsGreen = Color(0xFF16A34A)
private val Gray400 = Color(0xFF9CA3AF)
private val Gray500 = Color(0xFF6B7280)
private val Gray700 = Color(0xFF374151)

private fun directionFromHeading(heading: Int): String {
  val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
  val index = (floor(heading / 22.5 + 0.5).toInt()) % 8
  return dirs[(index + 8) % 8]
}

/** Bottom status bar. Port of the React `Footer`. */
@Composable
fun Footer(vehicle: Vehicle?, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .background(VdtColors.Black)
      .height(56.dp)
      .padding(horizontal = 24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (vehicle == null) {
      Row(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.SatelliteAlt, null, tint = Gray700, modifier = Modifier.size(24.dp))
        Box(
          Modifier.size(32.dp).clip(CircleShape).border(2.dp, Gray500, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Filled.Memory, null, tint = Gray500, modifier = Modifier.size(20.dp))
        }
      }
      Text("VDTERMINAL SYSTEM READY", color = Gray400, fontSize = 10.sp, fontWeight = FontWeight.Bold)
      return@Row
    }

    val gpsEnabled = vehicle.gps?.enabled ?: false
    val gpsActive = vehicle.gps?.active ?: false
    val aiActive = vehicle.ai?.active ?: false
    val heading = vehicle.gps?.heading ?: 0
    val fuelLevel =
      vehicle.motor
        ?.fillUnits
        ?.fuel
        ?.fillLevelPercentage ?: 100
    val lowFuel = fuelLevel <= 10

    // Left
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(40.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Filled.SatelliteAlt,
        null,
        modifier = Modifier.size(24.dp),
        tint = if (gpsEnabled) (if (gpsActive) GpsGreen else Gray500) else Gray700,
      )
      Box(
        Modifier.size(32.dp).clip(CircleShape).border(2.dp, if (aiActive) GpsGreen else Gray500, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.Memory,
          null,
          tint = if (aiActive) GpsGreen else Gray500,
          modifier = Modifier.size(20.dp),
        )
      }
    }
    // Center
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(directionFromHeading(heading), color = VdtColors.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
      Text("$heading", color = VdtColors.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
          Icons.Filled.LocalGasStation,
          null,
          tint = if (lowFuel) VdtColors.Red else Gray400,
          modifier = Modifier.size(20.dp),
        )
        Box(Modifier.width(4.dp).height(24.dp).background(Gray700)) {
          Box(
            Modifier
              .fillMaxWidth()
              .fillMaxHeight(fuelLevel / 100f)
              .align(Alignment.BottomStart)
              .background(VdtColors.White),
          )
        }
      }
    }
    // Right
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
      Text(
        vehicle.name,
        color = Gray400,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        vehicle.type,
        color = Gray400,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
