package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.PfMode
import net.vertexdezign.vdt.model.PfSubSection
import net.vertexdezign.vdt.model.PfValue
import net.vertexdezign.vdt.model.PrecisionFarming
import net.vertexdezign.vdt.model.SectionSide
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.WorkSection
import net.vertexdezign.vdt.model.WorkWidth
import kotlin.math.roundToInt

/**
 * What the tool is doing across its width — the terminal's section view.
 *
 * Three strips, each drawn only when the machine reports it, because the three come from different
 * places and most machines have one of them:
 *
 *  * a status line, from the work areas: what kind of work, and whether it is happening. This is the
 *    only one a tool without sections has — a tedder or a plough is all-or-nothing, and "is it
 *    actually working" is exactly what you can't see from the seat.
 *  * the shutoff bar, from `spec_variableWorkWidth`: one cell per section, lit when it is on. This is
 *    the base game's whole notion of section control.
 *  * the rate strip, from Precision Farming: what is being put down across the boom, tinted by how
 *    far each slice is below its target. It only exists in singleplayer and on the host (PF computes
 *    it server-side), which is why the reading above it comes from the boom average instead — that
 *    one is streamed to everybody.
 */
@Composable
fun SectionView(
  workWidth: WorkWidth?,
  workAreas: List<WorkArea>,
  precisionFarming: PrecisionFarming?,
  modifier: Modifier = Modifier,
) {
  val sections = workWidth?.sections.orEmpty()
  val status = workAreaStatus(workAreas)
  if (sections.isEmpty() && status == null && precisionFarming == null) return

  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (status != null) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(status.color))
        Text(
          status.label,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = VdtColors.DarkGray,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        // The live width, which is what changes as sections fold in — the reason the aggregate is
        // worth showing next to the bar rather than instead of it.
        val width = workWidth?.total ?: workAreas.firstOrNull { it.active }?.width
        if (width != null && width > 0f) {
          Text(
            "${formatMeters(width)} m",
            fontSize = 9.sp,
            color = VdtColors.DarkGray,
            maxLines = 1,
          )
        }
      }
    }

    if (sections.isNotEmpty()) {
      SectionBar(sections)
    }

    if (precisionFarming != null) {
      RateReadout(precisionFarming)
      // First area with a strip: a tool has one boom, and PF only sub-divides the areas it works
      // with. Absent on a multiplayer client, where the readout above stands alone.
      precisionFarming.workAreas.firstOrNull { it.subSections.isNotEmpty() }?.let {
        RateStrip(it.subSections, precisionFarming.mode)
      }
    }
  }
}

/** One cell per shutoff section, in the boom's own order; a center section is bracketed, as in game. */
@Composable
internal fun SectionBar(sections: List<WorkSection>, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().height(12.dp),
    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    sections.forEachIndexed { index, section ->
      val center = section.side == SectionSide.CENTER
      if (center && sections.getOrNull(index - 1)?.side != SectionSide.CENTER) Separator()
      Box(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .clip(RoundedCornerShape(2.dp))
          .background(if (section.active) VdtColors.Green else VdtColors.TrackGray)
          .border(1.dp, if (section.active) VdtColors.Green else VdtColors.PanelBorder, RoundedCornerShape(2.dp)),
      )
      if (center && sections.getOrNull(index + 1)?.side != SectionSide.CENTER) Separator()
    }
  }
}

/** The game's own marker for where the middle of the boom is (VariableWorkWidthHUDExtension:86-94). */
@Composable
private fun Separator() {
  Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.DarkGray))
}

/**
 * The boom average: what the ground has, and what the tool is aiming for. Both are network-synced, so
 * this is the one part of the Precision Farming view that reads the same in multiplayer.
 */
@Composable
private fun RateReadout(pf: PrecisionFarming) {
  val value = pf.primary ?: return
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      if (pf.mode == PfMode.LIME) "pH" else "N",
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.DarkGray,
    )
    Text(
      rateLabel(pf.mode, value),
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      color = if (value.deficit > 0f) VdtColors.Amber else VdtColors.Green,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (pf.auto) {
      Text("AUTO", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.ProgressBlue)
    }
  }
}

/** One cell per ~2 m slice across the boom, tinted by how far that slice is below its target. */
@Composable
internal fun RateStrip(subSections: List<PfSubSection>, mode: PfMode, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (slice in subSections) {
      Box(Modifier.weight(1f).fillMaxHeight().background(sliceColor(slice, mode)))
    }
  }
}

/** What a work-area set says about the tool, as a lamp and a word. */
internal data class WorkStatus(val label: String, val color: Color)

/**
 * The status line's two facts, from the engine's own predicates: is any part of this tool able to
 * work, and has any part of it actually worked ground in the last 200 ms.
 *
 * Internal rather than private so the wording can be tested — "working" and "ready" look alike in a
 * screenshot and mean very different things to someone deciding whether to lift the tool.
 */
internal fun workAreaStatus(areas: List<WorkArea>): WorkStatus? {
  if (areas.isEmpty()) return null
  // The type is the tool's job, so the first named one names the whole tool; a rig with a cultivator
  // and a sowing area is described by what it leads with.
  val type = areas.firstOrNull { it.type != null }?.type?.lowercase()?.replaceFirstChar { it.uppercase() }
  val working = areas.count { it.processing }
  val active = areas.count { it.active }
  val label =
    when {
      working > 0 -> "${type ?: "Tool"} · working"
      active > 0 -> "${type ?: "Tool"} · ready"
      else -> "${type ?: "Tool"} · off"
    }
  val color =
    when {
      working > 0 -> VdtColors.Green
      active > 0 -> VdtColors.Amber
      else -> VdtColors.Gray
    }
  return WorkStatus(label, color)
}

/**
 * How one slice reads: grey where PF has no data, and otherwise a red-to-green ramp on how much of
 * the target is already in the ground. Green means "nothing needed here", which on a variable-rate
 * strip is the same message as a shut-off section — the tool should be putting little or nothing down.
 */
internal fun sliceColor(slice: PfSubSection, mode: PfMode): Color {
  if (!slice.valid) return VdtColors.Gray
  val level = if (mode == PfMode.LIME) slice.ph else slice.n
  val target = if (mode == PfMode.LIME) slice.phTarget else slice.nTarget
  if (level == null || target == null || target <= 0f) return VdtColors.Green
  val ratio = (level / target).coerceIn(0f, 1f)
  // Two stops rather than one: a straight red->green lerp passes through a muddy olive that reads as
  // "fine" at a glance, and the middle of this scale is the part that matters.
  return if (ratio < 0.5f) {
    lerp(VdtColors.Red, VdtColors.Amber, ratio * 2f)
  } else {
    lerp(VdtColors.Amber, VdtColors.Green, (ratio - 0.5f) * 2f)
  }
}

/** `45 → 90 kg/ha`, or just the reading when it is already at target. */
internal fun rateLabel(mode: PfMode, value: PfValue): String {
  val unit = value.unit?.let { " $it" }.orEmpty()
  val level = formatRate(mode, value.level)
  if (value.deficit <= 0f) return "$level$unit"
  return "$level → ${formatRate(mode, value.target)}$unit"
}

private fun formatRate(mode: PfMode, value: Float): String =
  if (mode == PfMode.LIME) formatMeters(value) else value.roundToInt().toString()

/** One decimal, and none when it would be `.0` — the width of a boom is not a lab measurement. */
private fun formatMeters(value: Float): String {
  val tenths = (value * 10f).roundToInt()
  return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
