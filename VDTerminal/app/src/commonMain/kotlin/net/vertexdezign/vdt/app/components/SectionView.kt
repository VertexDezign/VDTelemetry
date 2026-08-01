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
 * The two Precision Farming rows claim their space up front rather than taking it from whatever
 * happens to be in them: both hold content that comes and goes at driving speed, and a panel that
 * reflows under the reader is harder to read than one with a gap in it.
 *
 * The rate row does it with a blank line at [READOUT_TEXT_SIZE] rather than a height in dp. A fixed dp
 * is a guess at how tall a line of text is, and a wrong one clips the glyphs — which it did, at the
 * ordinary font scale, the moment there was a rate to draw. Reserving with the same text metric the
 * content uses cannot be wrong at any scale. The strip has no text and is genuinely a fixed height.
 */
private val READOUT_TEXT_SIZE = 10.sp
private val STRIP_HEIGHT = 8.dp

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
 *
 * The first two follow the data. The two Precision Farming rows do **not**: they claim their height
 * from what the machine is capable of and then let their contents come and go, because what goes in
 * them changes several times a second and a panel that reflows while you read it is worse than one
 * with a gap in it.
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
  val readoutSlot = readoutSlotShown(precisionFarming)
  val stripSlot = precisionFarming != null && stripSlotShown(precisionFarming)
  if (status == null && bar == null && !readoutSlot && !stripSlot) return

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
      is SprayBar.Nozzles -> NozzleBar(bar.nozzles)
      is SprayBar.Sections -> SectionBar(bar.sections)
      null -> Unit
    }

    if (precisionFarming != null) {
      // Reserved slots, not "draw it when there is something to draw". What goes in them comes and
      // goes several times a second — see [spotNozzles] for why — and a row that appears and vanishes
      // shoves everything under it up and down. The space is claimed once, from the machine's
      // capabilities, and only the content inside it changes.
      if (readoutSlot) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
          // Holds the line open at exactly the height a readout takes, in every state and at every
          // font scale, because it is the same text metric the readouts themselves use.
          Text(" ", fontSize = READOUT_TEXT_SIZE, fontWeight = FontWeight.Bold)
          // One line here, never two: the rate where there is one, and otherwise — herbicide, where PF
          // keeps no rates at all — what spot spraying is saving.
          if (rate != null) {
            RateReadout(precisionFarming, rate)
          } else {
            spotNozzles(precisionFarming)?.let { SpotReadout(it, live = status?.active == true) }
          }
        }
      }
      if (stripSlot) {
        Box(Modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
          if (strip.isNotEmpty()) RateStrip(strip, precisionFarming.mode)
        }
      }
    }
  }
}

/** Which of the two bars this machine gets — see [sprayBar]. */
internal sealed interface SprayBar {
  data class Nozzles(val nozzles: PfNozzles) : SprayBar

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
  if (nozzles != null && nozzles.active.isNotEmpty()) return SprayBar.Nozzles(nozzles)
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
 *
 * A lit cell is shaded by how hard that nozzle is running, which only varies on a pulse-width
 * modulation boom. There it is the whole point: through a turn the inside of the boom fades and the
 * outside stays solid, which is exactly what the driver sees out of the window and what a bar of
 * on/off cells would flatly deny.
 */
@Composable
internal fun NozzleBar(nozzles: PfNozzles, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(2.dp)).background(VdtColors.TrackGray),
    horizontalArrangement = Arrangement.spacedBy(0.5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    nozzles.active.forEachIndexed { index, on ->
      Box(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .background(
            if (on) VdtColors.Accent.copy(alpha = nozzleAlpha(nozzles.amountAt(index))) else VdtColors.TrackGray,
          ),
      )
    }
  }
}

/**
 * Opacity for a nozzle running at [amount] of full flow.
 *
 * Floored well above transparent: a nozzle pulsing slowly is still spraying, and the one thing this
 * cell must never look like is the shut one next to it. The floor is what separates "dialled down" from
 * "off"; the range above it is what makes the gradient across a turning boom readable.
 */
internal fun nozzleAlpha(amount: Float): Float = NOZZLE_MIN_ALPHA + (1f - NOZZLE_MIN_ALPHA) * amount.coerceIn(0f, 1f)

private const val NOZZLE_MIN_ALPHA = 0.45f

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
      fontSize = READOUT_TEXT_SIZE,
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
 *
 * [live] is whether the boom is down and moving. Raised or stopped, every nozzle is shut, which is a
 * perfectly true "100% saved" and a completely useless one — so the label stays put and the number
 * goes quiet rather than lying at full volume.
 */
@Composable
private fun SpotReadout(nozzles: PfNozzles, live: Boolean) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "SPOT",
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      color = if (live) VdtColors.ProgressBlue else VdtColors.Gray,
    )
    Text(
      if (live) "${(nozzles.saved * 100f).roundToInt()}% saved" else "—",
      fontSize = READOUT_TEXT_SIZE,
      fontWeight = FontWeight.Bold,
      overflow = TextOverflow.Ellipsis,
      // Green the moment anything is being skipped; grey at full rate, where there is no saving to
      // report and the line is only there to say spot spraying is on.
      color = if (live && nozzles.saved > 0f) VdtColors.Green else VdtColors.DarkGray,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
    if (live) {
      Text(
        "${(nozzles.fraction * 100f).roundToInt()}% rate",
        fontSize = 9.sp,
        color = VdtColors.DarkGray,
        maxLines = 1,
      )
    }
  }
}

/**
 * The nozzles to report a saving for, or null when a saving would be a lie.
 *
 * Spot spraying has to be *fitted*: without it, closed nozzles are folded-away boom — less liquid over
 * less ground, which is not a saving.
 *
 * Deliberately **not** gated on the tool processing ground, which is what the first version did and
 * what made the line flicker. `getIsWorkAreaProcessing` is only true within 200 ms of the processing
 * function reporting a changed area (`WorkArea.lua:191-198`), and a spot sprayer over clean crop
 * changes nothing — so it toggles with the weeds, several times a second. Whether the boom is down
 * and moving is what actually decides if the number means anything, and that is [WorkStatus.active].
 */
internal fun spotNozzles(precisionFarming: PrecisionFarming): PfNozzles? {
  val nozzles = precisionFarming.nozzles ?: return null
  if (precisionFarming.spotSpray != true || nozzles.count <= 0) return null
  return nozzles
}

/**
 * Whether to keep room for the rate line.
 *
 * Claimed from the *mode*, not from having a number this instant. The rates go absent off the field
 * and on unsampled ground — the mod withholds a reading PF is not maintaining — so a slot that
 * followed the value would blink at every headland. In a mode with rates the row is held and simply
 * goes blank; with herbicide it is held only if there is a spot-spray saving to put in it.
 */
internal fun readoutSlotShown(precisionFarming: PrecisionFarming?): Boolean {
  if (precisionFarming == null) return false
  return precisionFarming.mode != PfMode.OTHER || spotNozzles(precisionFarming) != null
}

/**
 * Whether to keep room for the sub-section strip.
 *
 * PF fills sub-sections in only while liming or fertilizing, so with herbicide the slot would be
 * permanently blank and is not claimed at all. In the modes where it does compute them, the slot is
 * held even in the moments nothing is valid — crossing a headland or unsampled ground — because that
 * is exactly when it would otherwise blink.
 */
internal fun stripSlotShown(precisionFarming: PrecisionFarming): Boolean =
  precisionFarming.mode != PfMode.OTHER && precisionFarming.workAreas.any { it.subSections.isNotEmpty() }

/** One cell per ~2 m slice across the boom, tinted by how far that slice is below its target. */
@Composable
internal fun RateStrip(subSections: List<PfSubSection>, mode: PfMode, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().height(STRIP_HEIGHT).clip(RoundedCornerShape(2.dp)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (slice in subSections) {
      Box(Modifier.weight(1f).fillMaxHeight().background(sliceColor(slice, mode)))
    }
  }
}

/**
 * What a work-area set says about the tool, as a lamp and a word.
 *
 * The two flags are not degrees of the same thing. [active] is the tool being in a position to work —
 * lowered, in contact, moving forward — and is steady while you drive a field. [working] is the
 * stronger claim that ground actually *changed* in the last 200 ms, which is the right thing to light
 * a lamp with and the wrong thing to lay a row out with: a spot sprayer over clean crop, or any tool
 * passing over ground it already worked, flips it several times a second.
 */
internal data class WorkStatus(val label: String, val color: Color, val active: Boolean, val working: Boolean)

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
  return WorkStatus(label, color, active = active > 0, working = working > 0)
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
