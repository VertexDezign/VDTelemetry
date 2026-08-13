package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.PfManual
import net.vertexdezign.vdt.model.PfMode
import net.vertexdezign.vdt.model.PfNozzles
import net.vertexdezign.vdt.model.PfSubSection
import net.vertexdezign.vdt.model.PfValue
import net.vertexdezign.vdt.model.PrecisionFarming
import net.vertexdezign.vdt.model.SectionSide
import net.vertexdezign.vdt.model.Vehicle
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
 *
 * [onCommand] makes the rate row live — the auto/manual switch and the manual step. Left out, every
 * row is a readout and nothing in the view is tappable.
 */
@Composable
fun SectionView(
  workWidth: WorkWidth?,
  workAreas: List<WorkArea>,
  precisionFarming: PrecisionFarming?,
  modifier: Modifier = Modifier,
  onCommand: ((ClientMessage) -> Unit)? = null,
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
            RateReadout(precisionFarming, rate, onCommand)
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
 * The boom average: what the ground has, and where this pass takes it. Both halves are network-synced,
 * so this is the one part of the Precision Farming view that reads the same in multiplayer.
 *
 * *Where this pass takes it* is a different number in the two modes, and that is the point of the
 * readout. In auto the tool aims at the map's target, so the target is what it moves to. In manual it
 * applies a fixed step whatever the ground says, so it moves to `reading + step` — which may fall
 * short of the target, overshoot it, or be the only figure there is on ground PF has no target for.
 * Showing the target in manual mode would name something the machine is not aiming at.
 *
 * [onCommand] wires the mode switch and the step; without it the row is a readout, which is what the
 * map's own strip wants.
 */
@Composable
private fun RateReadout(pf: PrecisionFarming, value: PfValue, onCommand: ((ClientMessage) -> Unit)?) {
  val manual = pf.manual?.takeUnless { pf.auto }
  val figures = rateFigures(pf.mode, value, manual)
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
    val ink = if (value.deficit > 0f) VdtColors.Amber else VdtColors.Green
    // Kept across recompositions: the figures either side of it change several times a second while
    // the colour changes only when the reading crosses its target, so rebuilding the slot with them
    // would rebuild a composable-holding map on every frame the numbers move.
    val arrow = remember(ink) { mapOf(ARROW_SLOT to arrowGlyph(ink)) }
    Text(
      rateText(figures),
      fontSize = READOUT_TEXT_SIZE,
      fontWeight = FontWeight.Bold,
      color = ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      inlineContent = arrow,
      modifier = Modifier.weight(1f),
    )
    // What the pass costs in product, which is the number PF's own HUD leads with. Which figure that
    // is depends on the mode, exactly as it does in game: in manual the nominal cost of the chosen
    // step, which stands whether or not the tool is running and is what a step is picked by; in auto
    // the live output, because there the tool picks its own rate per square metre and nothing else
    // describes it. Auto therefore has it only while the boom is down and working.
    rateCost(pf, manual)?.let {
      Text(it, fontSize = 9.sp, color = VdtColors.DarkGray, maxLines = 1)
    }
    RateModeControls(pf, manual, onCommand)
  }
}

/**
 * The mode switch and, in manual, the step either side of it.
 *
 * The chip is the switch: it already had to say which mode the tool is in, and a separate button for
 * that would be a second thing saying the same word. Which mode it is reads off the **word**, not the
 * colour — the whole panel is read at a glance by people who cannot rely on hue.
 *
 * Every tap sends an absolute target computed from what is rendered, so a dropped or doubled command
 * over the file channel settles back to what the machine reports rather than drifting.
 *
 * The two step buttons make this row a few dp taller than the reserved line above it, so switching
 * modes nudges what is under it. Left that way on purpose: the reserved slot exists for content that
 * changes several times a second while you drive (see [SectionView]), and the mode changes when
 * somebody presses this chip — reserving the taller height permanently would put a gap under every
 * automatic sprayer to avoid a reflow nobody can be surprised by.
 */
@Composable
private fun RateModeControls(pf: PrecisionFarming, manual: PfManual?, onCommand: ((ClientMessage) -> Unit)?) {
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    if (manual != null) {
      StepButton(Icons.Filled.Remove, "lower the application rate", manual.canStep(-1), onCommand) {
        ClientMessage.SetSprayAmountStep(manual.stepped(-1))
      }
    }
    // Tappable only where PF itself allows the switch: it gates its own keybind on the same flag and
    // forces manual straight back off, so a chip that looked live would be a button the game undoes.
    val toggle = onCommand?.takeIf { pf.canToggleAuto }
    Text(
      // Which mode reads off `auto`, never off having a step: a machine in manual whose step the mod
      // could not read is still in manual, and a chip saying AUTO there would be the one lie in the
      // row. The step joins the word when there is one.
      modeLabel(pf.auto, manual),
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      color = if (pf.auto) VdtColors.ProgressBlue else VdtColors.TextDark,
      maxLines = 1,
      modifier = Modifier
        .clip(RoundedCornerShape(3.dp))
        .then(if (toggle != null) Modifier.background(VdtColors.TrackGray) else Modifier)
        // Disabled rather than absent, so the chip is announced as a switch that is currently locked
        // instead of as a word — the same distinction the greyed step buttons make visually.
        .clickable(enabled = toggle != null, role = Role.Button) {
          toggle?.invoke(ClientMessage.SetSprayAmountAuto(!pf.auto))
        }
        .padding(horizontal = 3.dp, vertical = 1.dp),
    )
    if (manual != null) {
      StepButton(Icons.Filled.Add, "raise the application rate", manual.canStep(1), onCommand) {
        ClientMessage.SetSprayAmountStep(manual.stepped(1))
      }
    }
  }
}

/**
 * One end of the step control. Greyed at the machine's own limit rather than hidden, so the row keeps
 * its width as the rate is driven up and down — and so "this is as far as it goes" is visible.
 */
@Composable
private fun StepButton(
  icon: ImageVector,
  description: String,
  enabled: Boolean,
  onCommand: ((ClientMessage) -> Unit)?,
  message: () -> ClientMessage,
) {
  val live = enabled && onCommand != null
  Icon(
    icon,
    contentDescription = description,
    tint = if (live) VdtColors.TextDark else VdtColors.TextDisabled,
    modifier = Modifier
      .size(18.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.TrackGray)
      // At the machine's limit the button stays in place and stops responding, rather than losing its
      // click handler entirely: `enabled` says "this is a button, and it is not available", which is
      // the same thing the grey tint says to everyone who can see it.
      .clickable(enabled = live, role = Role.Button) { onCommand?.invoke(message()) }
      .padding(2.dp),
  )
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
      color = if (live) VdtColors.ProgressBlue else VdtColors.DarkGray,
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
      else -> VdtColors.TextDisabled
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

/**
 * The member of a hitched chain whose section view a slot should draw — itself, or something behind
 * it.
 *
 * A rig slot is a *position*, not a machine. A slurry tanker with a dribble bar or an injector on the
 * back is one thing you hitch and one thing you drive, and the panel already reads it that way for
 * fill units — `collectFillUnits` has always walked the chain. The section view was the odd one out,
 * reading the head and nothing else.
 *
 * On that rig the head has nothing to say: the base game shuts a barrel's work areas off while a tool
 * is attached, and it reports none at all (`examples/json/telemetry/precisionFarming/`
 * `liquidManure_dribbleBar.json` — the Kaweco's `workAreas` is empty, the Bomech behind it is the one
 * with the 21 m sprayer area). Nor can that machine be given a slot of its own: the mod reports its
 * `position` as an empty string, so no slot matches it. Through its parent is the only way it is ever
 * seen.
 *
 * The test is **readings, not capability**: a barrel reports a Precision Farming mode with no numbers
 * in it, so "has a PF block" would stop at the head and draw the empty line this was reported for.
 * Only a machine with work areas, shutoff sections or an actual rate wins, and the walk stops at the
 * first — a chain with a working head is unaffected, which is every ordinary implement.
 */
internal fun sectionMember(implement: Implement): Implement {
  if (implement.showsSectionView()) return implement
  for (child in implement.implement) {
    val found = sectionMember(child)
    if (found.showsSectionView()) return found
  }
  return implement
}

/** The three things [SectionView] draws from, asked as "is any of this actually here". */
private fun Implement.showsSectionView(): Boolean = workAreas.isNotEmpty() ||
  workWidth?.sections?.isNotEmpty() == true ||
  precisionFarming?.primary != null

/**
 * The Precision Farming rates a **vehicle** slot should draw — its own, or none at all.
 *
 * [sectionMember] walks *down* a hitched chain to find the machine doing the work. This is the same
 * question asked at the top of the rig, and it has to be asked separately because the mod answers it
 * before the app ever sees the data: `rateSource` hands a machine that applies nothing itself the
 * rates of the one it is driving, so on a Vredo VT5536 with an injecting disc harrow the vehicle's
 * `precisionFarming` block is byte-for-byte the harrow's.
 *
 * That substitution is right for a barrel, whose tool is nested and has no slot to be shown in. It is
 * wrong here: the harrow is hitched at BACK and has a slot of its own, so drawing the borrowed block
 * on the vehicle tile too puts the same reading — and the same live step buttons — on screen twice.
 *
 * So the vehicle keeps the rates only when it is doing the work: a self-propelled sprayer with its own
 * boom (the Rogator's `SPRAYER` work area) shows them, a prime mover for somebody else's tool does
 * not. Work areas and shutoff sections are the test, never the PF block itself — that block is exactly
 * the thing that may have been borrowed.
 */
internal fun ownRates(vehicle: Vehicle): PrecisionFarming? = vehicle.precisionFarming?.takeIf {
  vehicle.workAreas.isNotEmpty() || vehicle.workWidth?.sections?.isNotEmpty() == true
}

/**
 * The one boom on a rig, and the facts a map strip draws from it.
 *
 * [width] is the live working width — [WorkWidth.total] where the tool reports one, and otherwise the
 * width of a work area that is actually down, which is the same fallback the panel's status line uses.
 */
internal data class Boom(
  val bar: SprayBar,
  /** Absent on a machine Precision Farming drives no nozzles on: the count is a nozzle fact. */
  val nozzles: PfNozzles?,
  val width: Float?,
  val status: WorkStatus?,
)

/**
 * The boom on this rig, or null when nothing on it has one.
 *
 * The rig walked in the order it is hitched — the machine itself, then each implement depth-first,
 * exactly like the map's own `workFootprints` — and the **first** thing with a bar to draw wins. The
 * machine goes first so a self-propelled sprayer is described by its own boom rather than by whatever
 * is hanging off it; on an ordinary tractor it simply has nothing to offer and the walk moves on.
 *
 * One bar, because the strip is a picture of *the* boom: a rig with two tools that both have sections
 * is not a thing you drive, and given one, the leading tool is the honest thing to show. Pure, so
 * which tool gets picked is pinned by a test rather than by whatever the rig happened to look like the
 * day it was tried.
 */
internal fun boomOf(vehicle: Vehicle?): Boom? {
  if (vehicle == null) return null

  fun boom(workWidth: WorkWidth?, areas: List<WorkArea>, pf: PrecisionFarming?): Boom? {
    val bar = sprayBar(workWidth, pf) ?: return null
    return Boom(
      bar = bar,
      nozzles = pf?.nozzles,
      width = workWidth?.total?.takeIf { it > 0f } ?: areas.firstOrNull { it.active }?.width,
      status = workAreaStatus(areas),
    )
  }

  fun walk(implements: List<Implement>): Boom? {
    for (implement in implements) {
      boom(implement.workWidth, implement.workAreas, implement.precisionFarming)?.let { return it }
      walk(implement.implement)?.let { return it }
    }
    return null
  }

  return boom(vehicle.workWidth, vehicle.workAreas, vehicle.precisionFarming) ?: walk(vehicle.implement)
}

/**
 * The boom across the bottom of the map — the terminal's section strip.
 *
 * The bar and nothing but the bar, with the two numbers that label it. The rate readout and the rate
 * strip stay on the rig panel deliberately: this is the screen you steer by, and the map already has
 * the machine, the course and the lightbar competing for the same glance.
 *
 * It earns its place on top of the work-area footprint the map already draws, because on a Precision
 * Farming sprayer the two disagree. A base-game section switching off takes its work area with it
 * (`VariableWorkWidth:getIsWorkAreaActive` returns false for a shut section, and the footprint is
 * drawn only from active areas), so there the strip merely confirms a gap you can already see. PF
 * freezes those sections all-on and switches *nozzles* instead — so the footprint stays a solid
 * full-width quad while spot spraying blinks half the boom, and this is the only place that shows.
 *
 * Drawn as an overlay rather than a row under the map, so a tool being hitched mid-drive does not
 * reshuffle the map under the driver. [onHeight] reports the room it takes, so the ground-layer legend
 * in the same corner can sit above it instead of under it.
 */
@Composable
internal fun BoxScope.SectionStrip(boom: Boom, modifier: Modifier = Modifier, onHeight: (Dp) -> Unit = {}) {
  val density = LocalDensity.current
  Column(
    modifier
      .align(Alignment.BottomCenter)
      .fillMaxWidth()
      // Before the padding, so what it reports is the room the strip occupies rather than the room
      // inside it — the legend has to clear the whole thing, chrome included.
      .onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
      .padding(6.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      // The same lamp as the rig panel, and the only thing here that says whether the tool is down and
      // working at all. The bar below cannot: a shutoff section reads "on" on a raised implement, and
      // every nozzle reads "off" on a lowered boom that has simply found no weeds.
      boom.status?.let { status ->
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(status.color))
      }
      boom.nozzles?.let { nozzles ->
        Text(
          "${nozzles.activeCount}/${nozzles.count}",
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = if (nozzles.activeCount > 0) VdtColors.Green else VdtColors.DarkGray,
          maxLines = 1,
        )
      }
      Spacer(Modifier.weight(1f))
      boom.width?.takeIf { it > 0f }?.let { width ->
        Text("${formatMeters(width)} m", fontSize = 9.sp, color = VdtColors.DarkGray, maxLines = 1)
      }
    }
    when (val bar = boom.bar) {
      is SprayBar.Nozzles -> NozzleBar(bar.nozzles)
      is SprayBar.Sections -> SectionBar(bar.sections)
    }
  }
}

/**
 * The two figures a rate readout puts either side of the arrow: what is in the ground, and where this
 * pass leaves it. [setPoint] is null when the pass moves nothing — at or above target in auto, or a
 * manual step the machine reports no change for — and then the reading stands alone.
 *
 * Pure, and separate from the drawing, because "which number goes on the right" is the whole
 * difference between the two modes and is worth pinning in a test rather than in a screenshot.
 */
internal data class RateFigures(val level: String, val setPoint: String?, val unit: String?)

/**
 * `45 → 90 kg/ha` in auto — the reading and the map's target — and `45 → 60 kg/ha` in manual, where
 * the right-hand figure is the reading plus the fixed step the machine is set to apply.
 *
 * The manual figure is deliberately **not** clamped to the target: overshooting is a real outcome of
 * choosing your own rate, and a readout that hid it would hide the reason to turn the step down.
 */
internal fun rateFigures(mode: PfMode, value: PfValue, manual: PfManual?): RateFigures {
  val level = formatRate(mode, value.level)
  val change = manual?.change
  val setPoint = when {
    manual != null -> if (change != null && change > 0f) formatRate(mode, value.level + change) else null
    value.deficit > 0f -> formatRate(mode, value.target)
    else -> null
  }
  return RateFigures(level, setPoint, value.unit)
}

/**
 * What the mode chip says: `AUTO`, or `MAN` with the step out of however many the machine has.
 *
 * Reads the mode off [auto] alone. [manual] only decides whether the step can be named — the mod
 * withholds it in a mode PF keeps no rates for, and a tool in manual is in manual either way.
 */
internal fun modeLabel(auto: Boolean, manual: PfManual?): String = when {
  auto -> "AUTO"
  manual != null -> "MAN ${manual.step}/${manual.max}"
  else -> "MAN"
}

/**
 * The product this pass costs per hectare, e.g. `600 kg/ha` — the line PF's own HUD leads with.
 *
 * [manual] is the step the machine is on, already filtered to null in auto by the caller. Given one,
 * the nominal cost of that step is the answer: it is what the driver is choosing between, and it
 * holds with the boom up. Without one — auto — the answer is what is actually leaving the machine,
 * which is the only rate auto has and which goes absent the moment the tool stops working.
 */
internal fun rateCost(pf: PrecisionFarming, manual: PfManual?): String? {
  val rate = manual?.rate ?: pf.rate ?: return null
  val unit = (if (manual?.rate != null) manual.rateUnit else pf.rateUnit) ?: return null
  // Whole units above 10, one decimal below: a spreader is set in kilos per hectare and a slurry
  // tanker in a couple of cubic metres, and rounding the tanker to "2" loses the setting.
  val text = if (rate >= 10f) rate.roundToInt().toString() else formatMeters(rate)
  return "$text $unit"
}

/**
 * The readout as one line of text, with the arrow as an **inline icon** rather than a character.
 *
 * A "→" here is what issue #77 was: the wasm build ships no font fallback, so a Unicode arrow lands
 * outside the bundled font's coverage and renders as a box with a cross in it (see FinancePanel's sort
 * caret and InvoiceBuilder's remove button, which went the same way). Inline content keeps it a single
 * [Text], so the line still ellipsizes as one thing in a narrow tile — which a Row of three pieces
 * would not.
 *
 * The alternate text is ASCII for the same reason: it is what shows if the slot is ever rendered
 * without its content, and it must not be a second tofu.
 */
private fun rateText(figures: RateFigures): AnnotatedString = buildAnnotatedString {
  append(figures.level)
  if (figures.setPoint != null) {
    append(' ')
    appendInlineContent(ARROW_SLOT, "->")
    append(' ')
    append(figures.setPoint)
  }
  figures.unit?.let { append(" $it") }
}

private const val ARROW_SLOT = "arrow"

/** The arrow itself, sized in `sp` so it scales with the line it sits in rather than beside it. */
private fun arrowGlyph(tint: Color) = InlineTextContent(
  Placeholder(READOUT_TEXT_SIZE, READOUT_TEXT_SIZE, PlaceholderVerticalAlign.Center),
) {
  Icon(
    Icons.AutoMirrored.Filled.ArrowRightAlt,
    contentDescription = "to",
    tint = tint,
    modifier = Modifier.fillMaxSize(),
  )
}

private fun formatRate(mode: PfMode, value: Float): String =
  if (mode == PfMode.LIME) formatMeters(value) else value.roundToInt().toString()

/** One decimal, and none when it would be `.0` — the width of a boom is not a lab measurement. */
private fun formatMeters(value: Float): String {
  val tenths = (value * 10f).roundToInt()
  return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
