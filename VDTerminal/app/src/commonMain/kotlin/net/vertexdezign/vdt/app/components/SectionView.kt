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
import net.vertexdezign.vdt.model.PfNozzles
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
 * Four rows, each drawn only when the machine reports it, because they come from different places and
 * most machines have one or two of them:
 *
 *  * a status line, from the work areas: what kind of work, and whether it is happening. This is the
 *    only one a tool without sections has — a tedder or a plough is all-or-nothing, and "is it
 *    actually working" is exactly what you can't see from the seat.
 *  * the spray bar: either Precision Farming's live per-nozzle states or, on a machine without them,
 *    the base game's shutoff sections. See [sprayBar] for why it is one or the other and never both.
 *  * the rate readout, from PF's boom averages — which are streamed to every client, so this is the
 *    number that is there in multiplayer.
 *  * the rate strip, from PF's per-slice readings: what is in the ground across the boom, tinted by
 *    how far each slice is below target. Server-side only, and only computed at all when the tool is
 *    liming or fertilizing — so it is detail on top of the readout, never the thing itself.
 */
@Composable
fun SectionView(
  workWidth: WorkWidth?,
  workAreas: List<WorkArea>,
  precisionFarming: PrecisionFarming?,
  modifier: Modifier = Modifier,
) {
  val status = workAreaStatus(workAreas)
  val bar = sprayBar(workWidth, precisionFarming)
  val strip = activeStrip(precisionFarming)
  val rate = precisionFarming?.primary
  if (status == null && bar == null && strip.isEmpty() && rate == null) return

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
        // How much of the boom is on, when the bar is the nozzles' — the one number you would
        // otherwise have to count cells for, and the one spot spraying moves constantly.
        precisionFarming?.nozzles?.let {
          Text(
            "${it.activeCount}/${it.count}",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (it.activeCount > 0) VdtColors.Green else VdtColors.DarkGray,
            maxLines = 1,
          )
        }
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

    when (bar) {
      is SprayBar.Nozzles -> NozzleBar(bar.active)
      is SprayBar.Sections -> SectionBar(bar.sections)
      null -> Unit
    }

    if (precisionFarming != null) {
      // One line in this slot, never two: the rate where there is one, and otherwise — herbicide,
      // where PF keeps no rates at all — what spot spraying is saving.
      if (rate != null) {
        RateReadout(precisionFarming, rate)
      } else if (savingShown(precisionFarming, status)) {
        SavingReadout(precisionFarming.nozzles!!)
      }
      if (strip.isNotEmpty()) RateStrip(strip, precisionFarming.mode)
    }
  }
}

/** Which of the two bars this machine gets — see [sprayBar]. */
internal sealed interface SprayBar {
  data class Nozzles(val active: List<Boolean>) : SprayBar

  data class Sections(val sections: List<WorkSection>) : SprayBar
}

/**
 * One bar, not two.
 *
 * When Precision Farming drives a sprayer's nozzles it **takes the base game's width controls away** —
 * `ExtendedSprayerEffects` removes `VariableWorkWidth`'s input handler and HUD on exactly those
 * machines — so their shutoff sections are frozen all-on and say nothing. The nozzle states are the
 * live answer there, and they already fold the sections in, so showing both would be one honest bar
 * next to one stuck one.
 *
 * Everything else — a tool PF has no nozzle data for, a spreader, a cultivator with folding sections —
 * still gets the shutoff bar, which on those machines does move.
 */
internal fun sprayBar(workWidth: WorkWidth?, precisionFarming: PrecisionFarming?): SprayBar? {
  val nozzles = precisionFarming?.nozzles
  if (nozzles != null && nozzles.active.isNotEmpty()) return SprayBar.Nozzles(nozzles.active)
  val sections = workWidth?.sections.orEmpty()
  return if (sections.isEmpty()) null else SprayBar.Sections(sections)
}

/**
 * The sub-section strip worth drawing, or nothing.
 *
 * PF only fills sub-sections in while liming or fertilizing (`isValid = isLiming or isFertilizing`),
 * so a sprayer with herbicide in the tank reports a full set of slices that all read "no data". Drawn
 * literally that is a row of grey cells saying nothing, which reads as a broken bar rather than an
 * absent one — so a strip with nothing valid in it is not a strip.
 */
internal fun activeStrip(precisionFarming: PrecisionFarming?): List<PfSubSection> = precisionFarming
  ?.workAreas
  ?.firstOrNull { area -> area.subSections.any { it.valid } }
  ?.subSections
  .orEmpty()

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
 * One cell per nozzle, left to right, lit where spray is actually leaving the boom.
 *
 * Drawn tighter than the shutoff bar and without its gaps: there are several times as many nozzles as
 * sections, and what you read off this is the *shape* of the spray — a solid block, a gap where a
 * section is off, or the scattered pattern spot spraying makes as it finds weeds.
 */
@Composable
internal fun NozzleBar(active: List<Boolean>, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(2.dp)).background(VdtColors.TrackGray),
    horizontalArrangement = Arrangement.spacedBy(0.5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (on in active) {
      Box(Modifier.weight(1f).fillMaxHeight().background(if (on) VdtColors.Accent else VdtColors.TrackGray))
    }
  }
}

/**
 * The boom average: what the ground has, and what the tool is aiming for. Both are network-synced, so
 * this is the one part of the Precision Farming view that reads the same in multiplayer.
 */
@Composable
private fun RateReadout(pf: PrecisionFarming, value: PfValue) {
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

/**
 * What spot spraying is saving: the share of a full-width application this pass is not putting down.
 *
 * The number a spot-spray terminal leads with, and here it is the game's own arithmetic rather than an
 * estimate — PF multiplies the sprayer's usage by the active-nozzle fraction exactly.
 */
@Composable
private fun SavingReadout(nozzles: PfNozzles) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("SPOT", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.ProgressBlue)
    Text(
      "${(nozzles.saved * 100f).roundToInt()}% saved",
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      // Green the moment anything is being skipped; grey at full rate, where there is no saving to
      // report and the line is only there to say spot spraying is on.
      color = if (nozzles.saved > 0f) VdtColors.Green else VdtColors.DarkGray,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
    Text(
      "${(nozzles.fraction * 100f).roundToInt()}% rate",
      fontSize = 9.sp,
      color = VdtColors.DarkGray,
      maxLines = 1,
    )
  }
}

/**
 * Whether the saving is worth stating: spot spraying fitted, nozzles to count, and the tool actually
 * working.
 *
 * The last one is the catch. A raised or switched-off sprayer has every nozzle closed, which is a
 * perfectly true "100% saved" and a completely useless one — it is not saving anything, it is not
 * spraying. So the line only appears while ground is going under the boom, where 100% means the
 * genuinely interesting thing: a full-width pass over clean crop, putting nothing down.
 */
internal fun savingShown(precisionFarming: PrecisionFarming, status: WorkStatus?): Boolean {
  val nozzles = precisionFarming.nozzles ?: return false
  return precisionFarming.spotSpray == true && nozzles.count > 0 && status?.working == true
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

/**
 * What a work-area set says about the tool, as a lamp and a word. [working] is the strong claim —
 * ground actually went under it — as opposed to merely being able to work.
 */
internal data class WorkStatus(val label: String, val color: Color, val working: Boolean)

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
  return WorkStatus(label, color, working = working > 0)
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
