package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.CropRotationPlan
import net.vertexdezign.vdt.model.CropRotationSlot

// CropRotation.FALLOW_STATE / NO_CATCH_CROP_STATE — index 0 is the "nothing planted" sentinel for
// both the main crop and the catch crop; rendered muted rather than as a named crop.
private const val FALLOW_STATE = 0

/**
 * Farm-page panel for the optional FS25_CropRotation channel: lists the farm's saved rotation plans
 * and the crop sequence of each. Read-only for now (editing the sequence is a later step). A null
 * [data] means the mod isn't installed, distinct from installed-but-no-plans.
 */
@Composable
fun CropRotationPanel(data: CropRotationData?, modifier: Modifier = Modifier) {
  Panel(title = "Crop Rotation", icon = Icons.Filled.Agriculture, modifier = modifier) {
    when {
      data == null -> Centered("CropRotation mod not installed")

      data.rotations.isEmpty() -> Centered("No rotation plans — create one in the game menu")

      else ->
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          data.rotations
            .sortedBy { it.index }
            .forEach { plan -> PlanSection(plan) }
        }
    }
  }
}

@Composable
private fun PlanSection(plan: CropRotationPlan) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      plan.name.ifBlank { "Unnamed rotation" }.uppercase(),
      color = VdtColors.DarkGray,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (plan.sequence.isEmpty()) {
      Text("No crops in this rotation", color = VdtColors.Gray, fontSize = 11.sp)
    } else {
      // The sequence is ordered; show each step with its position so the cycle reads top to bottom.
      plan.sequence.forEachIndexed { i, slot -> SlotRow(i + 1, slot) }
    }
  }
}

@Composable
private fun SlotRow(position: Int, slot: CropRotationSlot) {
  val fallow = slot.state == FALLOW_STATE
  Row(
    Modifier.fillMaxWidth().padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      Modifier.size(8.dp).clip(CircleShape).background(if (fallow) VdtColors.TrackGray else VdtColors.Green),
    )
    Text(
      "$position.",
      color = VdtColors.Gray,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
    )
    Text(
      slot.crop.ifBlank { if (fallow) "Fallow" else "Crop ${slot.state}" },
      color = if (fallow) VdtColors.Gray else VdtColors.TextDark,
      fontSize = 12.sp,
      fontWeight = if (fallow) FontWeight.Normal else FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    // Catch crop only when one is set (state 0 = no catch crop).
    if (slot.catchCropState != FALLOW_STATE) {
      Text(
        "+ ${slot.catchCrop.ifBlank { "catch" }}",
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun Centered(text: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = VdtColors.Gray, fontSize = 12.sp)
  }
}
