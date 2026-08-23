package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.resources.Res
import net.vertexdezign.vdt.app.resources.isobus_mixer_wagon
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Discharge
import net.vertexdezign.vdt.model.DischargeReason
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Mass
import net.vertexdezign.vdt.model.MixState
import net.vertexdezign.vdt.model.Mixer
import net.vertexdezign.vdt.model.MixerIngredient
import net.vertexdezign.vdt.model.TipState
import net.vertexdezign.vdt.model.Tipping
import net.vertexdezign.vdt.model.Vehicle
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

/** Aspect ratio (w/h) of `isobus_mixer_wagon.png` — 1200×600, so exactly 2:1. */
private const val ART_ASPECT = 2f

/**
 * The text block on the machine's red body, as fractions of the art's box.
 *
 * Measured off the asset rather than guessed: the tub is a trapezoid, and this is the largest
 * rectangle that stays on red from its top edge down to where the augers' housing cuts in. Anything
 * wider spills onto the frame at the bottom of the range.
 */
private const val TUB_LEFT = 0.53f
private const val TUB_RIGHT = 0.80f
private const val TUB_TOP = 0.12f
private const val TUB_BOTTOM = 0.54f

/**
 * Smaller than this the art says nothing a picture is worth saying, so the panel drops it and gives
 * the room to the bars. The picture is orientation; the bars are the reason to look.
 *
 * Set by the *text*, not by the machine: the tub's block is about a quarter of the art's width, so
 * below this the figures written on the body stop being legible — and they read fine in the plain
 * row that replaces them.
 */
private val MIN_ART_HEIGHT = 96.dp

/** Wide and low enough that the art belongs beside the bars rather than above them. */
private val SIDE_BY_SIDE_FROM = 520.dp

/** Line box of the tub's figures, as a multiple of their type size. */
private const val TUB_LINE = 1.15f

/** The tub's sub-lines, relative to the headline they sit under. */
private const val SUB_SCALE = 0.62f

/** The gap between the two figures on the tub, as a multiple of the headline's size. */
private const val TUB_GAP = 0.35f

/** Roughly how many headline glyph-widths the tub's block has to hold: "225,000 l" and some slack. */
private const val HEADLINE_EMS = 5.2f

/** Below this the four-line block would be too small to read, so it drops to the two figures alone. */
private const val TUB_FULL_FROM = 12f

/** Past this the panel header can no longer hold the machine's name *and* the state; see [IsoBusPanel]. */
private val BARE_HEADER_BELOW = 260.dp

/** What one ingredient's label, bar and (occasional) shortfall line come to. Used to budget the art. */
private val BAR_ROW_HEIGHT = 36.dp

/** One line of the status strip plus the gap under it; it wraps to a second when the chips need one. */
private val STATUS_STRIP_HEIGHT = 23.dp

/**
 * One machine on the rig, flattened out of [Vehicle] or [Implement] — the two speak the same shape,
 * and a mixer wagon can be either (a self-propelled one is the vehicle).
 */
internal data class IsoBusMachine(
  val name: String,
  val foldable: FoldableState?,
  val tipping: Tipping?,
  val discharge: Discharge?,
  val mass: Mass?,
  val mixer: Mixer?,
) {
  /**
   * Whether this machine has anything the panel knows how to draw. The dispatch list, and the one
   * place it grows when a section is added.
   */
  val hasSection: Boolean get() = mixer != null
}

private fun Vehicle.isoBus() = IsoBusMachine(name, foldable, tipping, discharge, mass, mixer)

private fun Implement.isoBus() = IsoBusMachine(name, foldable, tipping, discharge, mass, mixer)

/** Every machine on the rig, the vehicle first, then its implements depth-first in hitch order. */
internal fun rigMachines(vehicle: Vehicle): List<IsoBusMachine> {
  val out = mutableListOf(vehicle.isoBus())
  fun walk(list: List<Implement>) {
    for (imp in list) {
      out += imp.isoBus()
      walk(imp.implement)
    }
  }
  walk(vehicle.implement)
  return out
}

/**
 * The machine this panel is showing.
 *
 * [slot] null means *auto*: the first machine on the rig with a section to draw, which is how a real
 * terminal behaves — it shows whatever announced itself on the bus, not a position you chose. A named
 * slot pins the tile to that position instead, which is what lets two tiles show two machines of a
 * combination rig at once.
 */
internal fun isoBusMachine(vehicle: Vehicle?, slot: RigSlot?): IsoBusMachine? {
  if (vehicle == null) return null
  if (slot == null) return rigMachines(vehicle).firstOrNull { it.hasSection }
  val position = slot.implementPosition ?: return vehicle.isoBus()
  return findIsoBusImplement(vehicle.implement, position)?.isoBus()
}

private fun findIsoBusImplement(list: List<Implement>, position: String): Implement? {
  for (imp in list) {
    if (imp.position == position) return imp
    findIsoBusImplement(imp.implement, position)?.let { return it }
  }
  return null
}

/**
 * The ISOBUS panel: the terminal the tractor lends to whatever is hitched behind it. Renders [slot] of
 * [vehicle] — or, with a null [slot], whatever on the rig has something to say.
 *
 * Unlike [RigSlotPanel], which is deliberately type-agnostic and shows the same six things about any
 * machine, this is the type-*aware* complement — and it dispatches on **which aspect subtree the
 * machine carries**, never on its `type` string. `type` is modder-defined and open-ended, and more
 * importantly a machine is not one class: a fertilizing seeder has `sowing` *and* `spraying`. Aspect
 * presence composes; a switch on `type` would have to pick one and drop the other. The dispatch list
 * is [IsoBusMachine.hasSection], and the mixer wagon (issue #113) is the first entry on it.
 *
 * Measures itself and thins out rather than taking a "compact" flag, like every other panel here:
 * what fits is a fact about the size the tile ended up at.
 */
@Composable
fun IsoBusPanel(vehicle: Vehicle?, slot: RigSlot?, modifier: Modifier = Modifier) {
  val machine = isoBusMachine(vehicle, slot)
  val mixer = machine?.mixer

  BoxWithConstraints(modifier) {
    // Narrow, the machine's name and the mix state cannot both sit in the header without one running
    // over the other. The state is the thing you glance at, so it moves into the body's status strip
    // rather than being dropped — see [MachineStatus].
    val bareHeader = maxWidth < BARE_HEADER_BELOW

    Panel(
      title = if (machine?.hasSection == true) machine.name else "ISOBUS",
      icon = if (bareHeader) null else Icons.Filled.Memory,
      modifier = Modifier.fillMaxSize(),
      headerActions = { if (mixer != null && !bareHeader) MixStateChip(mixer) },
    ) {
      if (mixer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            when {
              vehicle == null -> "No vehicle connected"
              slot == null -> "No ISOBUS machine on the rig"
              else -> "Nothing attached to ${slot.label.lowercase()}"
            },
            color = VdtColors.DarkGray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        MixerSection(machine, mixer, showStateInBody = bareHeader)
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Mixer wagon
// ---------------------------------------------------------------------------

@Composable
private fun MixerSection(machine: IsoBusMachine, mixer: Mixer, showStateInBody: Boolean) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    // Hoisted out of the scope: further in, these read as the enclosing Column's, not the box's.
    val bodyWidth = maxWidth
    val bodyHeight = maxHeight

    if (bodyWidth >= SIDE_BY_SIDE_FROM && bodyWidth > bodyHeight * 1.4f) {
      // Wide and low: the art is 2:1 and would eat the height a stacked layout needs for the bars.
      Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          MachineArt(mixer, machine.mass, Modifier.weight(1f).fillMaxWidth())
          MachineStatus(machine, mixer, showStateInBody)
        }
        RatioBars(mixer, bodyWidth * 0.45f, Modifier.weight(0.45f).fillMaxHeight())
      }
    } else {
      val art = artHeight(bodyWidth, bodyHeight, mixer.ingredients.size)
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (art >= MIN_ART_HEIGHT) {
          MachineArt(mixer, machine.mass, Modifier.height(art).fillMaxWidth())
        } else {
          TubReadout(mixer, machine.mass, Modifier.fillMaxWidth())
        }
        MachineStatus(machine, mixer, showStateInBody)
        RatioBars(mixer, bodyWidth, Modifier.weight(1f).fillMaxWidth())
      }
    }
  }
}

/**
 * How much height the art gets in the stacked layout.
 *
 * Budgeted from what the rest of the panel needs rather than as a flat fraction: the bars are the
 * reason the panel exists, so they are subtracted first and the picture takes what is left, bounded
 * by its own natural height (the art is 2:1, so a full-width band is already half the tile's width
 * tall) and by a share of the body, so a big tile is not mostly poster.
 *
 * Four rows' worth of bars are reserved at most: every base-game recipe is shorter than that, so the
 * usual case gives the whole list room, while a modded recipe of six scrolls instead of costing the
 * picture entirely.
 */
private fun artHeight(width: Dp, height: Dp, ingredients: Int): Dp {
  val natural = width / ART_ASPECT
  val cap = height * 0.55f
  val reserved = STATUS_STRIP_HEIGHT + BAR_ROW_HEIGHT * minOf(ingredients, 4)
  return minOf(natural, cap, height - reserved)
}

/**
 * The machine, with its load and weight written on the body — the mixer wagon's own weighing terminal
 * is on the front ladder of the real thing, and this is the same number in the same place.
 *
 * The art is one specific twin-auger vertical mixer standing in for the class, as the rest of the
 * ISOBUS machine set does. The level is **not** drawn as a rising fill: a mixer's contents are visible
 * from the top, not the side, so a filling side view would be a picture of something that does not
 * happen.
 */
@Composable
private fun MachineArt(mixer: Mixer, mass: Mass?, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier) {
    // The largest 2:1 box that fits, letterboxed on the long axis — same treatment as [Lighting].
    val boxW: Dp
    val boxH: Dp
    if (maxWidth / maxHeight >= ART_ASPECT) {
      boxH = maxHeight
      boxW = maxHeight * ART_ASPECT
    } else {
      boxW = maxWidth
      boxH = maxWidth / ART_ASPECT
    }

    Box(Modifier.size(boxW, boxH).align(Alignment.Center)) {
      Image(
        painterResource(Res.drawable.isobus_mixer_wagon),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )

      val tubW = boxW * (TUB_RIGHT - TUB_LEFT)
      val tubH = boxH * (TUB_BOTTOM - TUB_TOP)
      val type = tubType(tubW, tubH)
      Column(
        Modifier
          .offset(x = boxW * TUB_LEFT, y = boxH * TUB_TOP)
          .width(tubW)
          .height(tubH),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // White on the body's #DA1218 reads at 5.2:1; black would be 4.1:1.
        Text("${formatInt(mixer.value.roundToInt())} l", style = type.headline, maxLines = 1)
        if (type.full) {
          Text("of ${formatInt(mixer.capacity)}", style = type.sub, maxLines = 1)
        }
        // The load's own weight, never the machine's mass minus its empty one — see [Mixer.mass].
        val load = mixer.mass
        if (load != null) {
          Spacer(Modifier.height(type.gap))
          Text(formatTonnes(load), style = type.headline, maxLines = 1)
          if (type.full && mass != null) {
            Text("${formatTonnes(mass.value)} total", style = type.sub, maxLines = 1)
          }
        } else if (mass != null) {
          Spacer(Modifier.height(type.gap))
          Text(formatTonnes(mass.value), style = type.headline, maxLines = 1)
        }
      }
    }
  }
}

/** The type the tub's readout is set in, and whether the two sub-lines fit alongside the figures. */
private data class TubType(val headline: TextStyle, val sub: TextStyle, val gap: Dp, val full: Boolean)

/**
 * Sizes the block on the tub to the space it actually has, in both axes.
 *
 * Two things this has to do that the obvious version does not. It sets an explicit [lineHeight]:
 * Material 3's default text style carries an **absolute** 24sp line height, so a `Text` given only a
 * `fontSize` keeps 24dp-tall line boxes however small the type is — four of them overran the tub and
 * the last two were clipped to slivers. And it picks the **line count from what fits** rather than
 * from a width threshold: the capacity and the gross weight are what give way, because what is in
 * there and what it weighs are the numbers this block exists for.
 */
@Composable
private fun tubType(width: Dp, height: Dp): TubType {
  // A headline glyph is about 0.6em wide and the longest is around nine ("225,000 l"), so the block's
  // width supports a headline of roughly a fifth of it.
  val byWidth = width.value / HEADLINE_EMS
  // Four lines: two headlines, two sub-lines at SUB_SCALE, and the gap between the figures.
  val four = minOf(height.value / (TUB_LINE * 2 * (1 + SUB_SCALE) + TUB_GAP), byWidth)
  val two = minOf(height.value / (TUB_LINE * 2 + TUB_GAP), byWidth)
  val full = four >= TUB_FULL_FROM
  val size = (if (full) four else two).coerceIn(9f, 34f).sp
  return TubType(
    headline = tubStyle(size, VdtColors.White, FontWeight.Bold),
    sub = tubStyle(size * SUB_SCALE, VdtColors.White.copy(alpha = 0.85f), FontWeight.Normal),
    gap = (size.value * TUB_GAP).dp,
    full = full,
  )
}

/**
 * A line box exactly as tall as its type, centred and trimmed — the same treatment `ProgressBar`'s
 * labels get, and for the same reason: the inherited line height is fixed in absolute sp and has
 * nothing to do with the size actually being drawn.
 */
private fun tubStyle(size: TextUnit, color: Color, weight: FontWeight) = TextStyle(
  color = color,
  fontSize = size,
  fontWeight = weight,
  lineHeight = size * TUB_LINE,
  lineHeightStyle =
  LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
  ),
)

/** The same two numbers as a plain row, for a tile too short to carry the picture. */
@Composable
private fun TubReadout(mixer: Mixer, mass: Mass?, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
    Figure("Load", "${formatInt(mixer.value.roundToInt())} l", "of ${formatInt(mixer.capacity)}")
    val load = mixer.mass
    if (load != null) {
      Figure("Weight", formatTonnes(load), mass?.let { "${formatTonnes(it.value)} total" })
    } else if (mass != null) {
      Figure("Weight", formatTonnes(mass.value), null)
    }
  }
}

@Composable
private fun Figure(label: String, value: String, sub: String?) {
  Column {
    Text(label.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Text(value, color = VdtColors.TextDark, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    if (sub != null) Text(sub, color = VdtColors.DarkGray, fontSize = 10.sp, maxLines = 1)
  }
}

/**
 * The drum, the discharge and the fold, as a row of chips.
 *
 * It **wraps** rather than running one line: five chips can be up at once (the mix state on a narrow
 * tile, the drum, the tip side by its localized name, a refusal and the fold), and side by side the
 * strip only has the art column's width. A plain `Row` would have laid the overflow past the panel's
 * edge — and the chip most likely to fall off it is the last one added, the refusal, which is the one
 * the driver is standing at the trough for. [STATUS_STRIP_HEIGHT] budgets one line; a second is taken
 * from the bars, which are weighted, rather than from the panel.
 *
 * "Running" is the **drum**, and it is deliberately not the machine's turn-on state: the drum also
 * turns while discharging and for the mix cycle after the last thing went in, so reading turn-on as
 * "is it running" calls a mixing machine idle.
 *
 * There is no second chip for turn-on. On a mixer wagon turn-on engages the loading mechanism — which
 * on a machine that has none is nothing at all — and it feeds the same condition as the drum, so
 * whenever it is on the drum is turning too. Shown next to "Running" it was one fact stated twice.
 * What would earn a chip back is the **bale pickup** specifically (armed, and the game's own "that
 * bale is not in the recipe"), and that needs `spec.baleTriggers`, which the engine builds server-side
 * only. See FUTURE.md.
 */
@Composable
private fun MachineStatus(machine: IsoBusMachine, mixer: Mixer, showState: Boolean) {
  FlowRow(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // Narrow, the header gave up the mix state; it leads the strip instead of being lost.
    if (showState) {
      val (icon, tint) = mixStateMark(mixer)
      Chip(icon, mixStateLabel(mixer), tint)
    }

    when {
      !mixer.powered -> Chip(Icons.Filled.PowerOff, "No power", VdtColors.TextDisabled)

      mixer.running && mixer.remaining > 0 ->
        Chip(Icons.Filled.Sync, "Mixing ${formatSeconds(mixer.remaining)}", VdtColors.AccentText)

      mixer.running -> Chip(Icons.Filled.Sync, "Running", VdtColors.AccentText)

      else -> Chip(Icons.Filled.Pause, "Idle", VdtColors.DarkGray)
    }

    machine.tipping?.let { TipChip(it) }

    // The engine's own "why is nothing coming out". A terminal earns its place at the trough here:
    // the tip side is open, the drum is turning, and the reason it is not unloading is a fact only
    // the game has. Absent whenever nothing is wrong, which is most of the time.
    machine.discharge?.reason?.let { Chip(Icons.Filled.PriorityHigh, refusalOf(it), VdtColors.Amber) }

    when (machine.foldable) {
      FoldableState.FOLDED -> Chip(Icons.Filled.UnfoldLess, "Folded", VdtColors.DarkGray)
      FoldableState.EXTENDED -> Chip(Icons.Filled.UnfoldMore, "Unfolded", VdtColors.AccentText)
      null -> Unit
    }
  }
}

/**
 * Which way the wagon is unloading, and whether the door is moving.
 *
 * The side is named rather than drawn on the machine: the art is a side view, and left/right on a
 * mixer wagon is toward and away from the viewer — a position on that picture could not say it.
 * While a tip is under way it names the side actually in use; otherwise the one the next tip will
 * take.
 */
@Composable
private fun TipChip(tipping: Tipping) {
  val open = tipping.state != TipState.CLOSED
  val index = if (open) tipping.side ?: tipping.preferredSide else tipping.preferredSide
  val name = index?.let { tipping.sides.getOrNull(it - 1) }?.ifBlank { null }
  val label = when (tipping.state) {
    TipState.CLOSED -> name?.let { "Tip: $it" } ?: "Closed"
    TipState.OPENING -> name?.let { "Opening $it" } ?: "Opening"
    TipState.OPEN -> name?.let { "Unloading $it" } ?: "Unloading"
    TipState.CLOSING -> name?.let { "Closing $it" } ?: "Closing"
  }
  Chip(
    if (open) Icons.Filled.Sync else Icons.Filled.Pause,
    label,
    if (open) VdtColors.AccentText else VdtColors.DarkGray,
  )
}

/**
 * The engine's discharge refusals, in the words that fit a machine unloading into a feeding trough —
 * which is what every one of these means on a mixer wagon.
 */
internal fun refusalOf(reason: DischargeReason): String = when (reason) {
  DischargeReason.NOT_ALLOWED_HERE -> "Can't unload here"
  DischargeReason.NO_FREE_CAPACITY -> "Trough full"
  DischargeReason.FILLTYPE_NOT_SUPPORTED -> "Won't take this feed"
  DischargeReason.TOOLTYPE_NOT_SUPPORTED -> "Wrong tool for it"
  DischargeReason.NO_ACCESS -> "Not your trough"
  DischargeReason.NO_ACCESS_LAND -> "Not your land"
}

@Composable
private fun Chip(icon: ImageVector, label: String, tint: Color) {
  Row(
    Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 6.dp, vertical = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
    Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

/**
 * What the tub holds, in the engine's own words plus a glyph.
 *
 * The word is the mod's `title` — the loaded fill type's localized name, which is already exactly
 * right in all three loaded states ("Silage", "Mixing", "Total Mixed Ration") — and the glyph carries
 * the verdict, so neither the state nor its severity is ever told by hue alone.
 */
@Composable
private fun RowScope.MixStateChip(mixer: Mixer) {
  val (icon, tint) = mixStateMark(mixer)
  Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
  Text(mixStateLabel(mixer).uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/**
 * What is in the tub, in a word.
 *
 * The engine's own title is right for two of the three loaded states — the material while one thing
 * is in ("Heu"), the recipe's own name once the mix is finished ("Totalmischration"). It is *usable*
 * for the third and not much good: FORAGE_MIXING's title is "Futter", which says the load is feed
 * without saying the ratio is wrong, and the word a driver needs there is the fault. So off-ratio
 * gets a word of our own — deliberately not "mixing", which the status strip already uses for the
 * drum turning.
 */
private fun mixStateLabel(mixer: Mixer): String = when (mixer.state) {
  MixState.EMPTY -> "Empty"
  MixState.OUT_OF_RATIO -> "Off ratio"
  else -> mixer.title ?: mixer.fillType ?: "Loaded"
}

/** The glyph that carries the verdict, so hue is never the only thing saying it. */
private fun mixStateMark(mixer: Mixer): Pair<ImageVector, Color> = when (mixer.state) {
  MixState.READY -> Icons.Filled.Check to VdtColors.AccentText
  MixState.OUT_OF_RATIO -> Icons.Filled.Sync to VdtColors.Amber
  MixState.SINGLE -> Icons.Filled.HourglassBottom to VdtColors.DarkGray
  MixState.EMPTY -> Icons.Filled.Pause to VdtColors.TextDisabled
}

// ---------------------------------------------------------------------------
// The mixing-ratio bars
// ---------------------------------------------------------------------------

/** Bar geometry. The track is short — this is a strip of them, not a stack of progress bars. */
private val BAR_HEIGHT = 12.dp

/** Narrower than this, a bar row drops its weight column and shortens its hint to fit one line. */
private val COMPACT_BARS_BELOW = 230.dp
private val MARKER_WIDTH = 3.dp

@Composable
private fun RatioBars(mixer: Mixer, width: Dp, modifier: Modifier = Modifier) {
  if (mixer.ingredients.isEmpty()) {
    // A mixer whose XML names no recipe: a trailer with a drum. Nothing to be short of.
    Box(modifier.fillMaxWidth().padding(top = 4.dp)) {
      Text("No recipe — takes one material at a time", color = VdtColors.DarkGray, fontSize = 10.sp)
    }
    return
  }
  // Scrolls rather than clips: recipes are map data and a modded one may run to six ingredients, so
  // "how many fit" is never something this panel gets to decide by dropping the ones that don't.
  // Centred rather than top-aligned: beside the art on a wide tile, and under it on a full page, a
  // short list stranded at the top reads as a panel that failed to finish loading. When the list
  // outgrows the space, Center lays out from the top anyway and it scrolls.
  Column(
    modifier.verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
  ) {
    for (ingredient in mixer.ingredients) RatioBar(mixer, ingredient, compact = width < COMPACT_BARS_BELOW)
  }
}

/**
 * One ingredient's bar: the recipe's allowed window drawn as a band on the track, and a marker where
 * this ingredient actually sits.
 *
 * **The share is of the load, not of the tub.** `value / Mixer.loaded`, which is what the game's own
 * HUD divides by — dividing by `capacity` would report a perfectly good mix as short of everything on
 * any wagon that is not brim full.
 *
 * In-window and out-of-window are told apart by **where the marker sits relative to the band**, by the
 * trailing glyph, and by the words; colour only agrees with them. That is the standing rule here — two
 * states must never differ by hue alone.
 *
 * A verdict is only passed when the engine has passed one. While the wagon is empty or holds a single
 * material there is nothing wrong with an ingredient reading zero, so those rows carry the shortfall
 * hint but no warning.
 */
@Composable
private fun RatioBar(mixer: Mixer, ingredient: MixerIngredient, compact: Boolean) {
  val share = mixer.shareOf(ingredient)
  val inWindow = ingredient.holds(share)
  val judged = mixer.state == MixState.OUT_OF_RATIO
  val flagged = judged && !inWindow

  val min = ingredient.minPercentage / 100f
  val max = ingredient.maxPercentage / 100f

  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        ingredient.title ?: ingredient.fillTypes.firstOrNull() ?: ingredient.name,
        color = VdtColors.TextDark,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      val mass = ingredient.mass
      if (!compact && mass != null && mass > 0.0) {
        Text(formatTonnes(mass), color = VdtColors.DarkGray, fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
      }
      Text(
        "${(share * 100).roundToInt()}%",
        color = if (flagged) VdtColors.Amber else VdtColors.TextDark,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
      if (flagged) {
        Spacer(Modifier.width(3.dp))
        Icon(
          Icons.Filled.PriorityHigh,
          contentDescription = "outside its window",
          tint = VdtColors.Amber,
          modifier = Modifier.size(11.dp),
        )
      } else if (inWindow && share > 0f) {
        Spacer(Modifier.width(3.dp))
        Icon(
          Icons.Filled.Check,
          contentDescription = "in its window",
          tint = VdtColors.AccentText,
          modifier = Modifier.size(11.dp),
        )
      }
    }

    BoxWithConstraints(
      Modifier
        .fillMaxWidth()
        .height(BAR_HEIGHT)
        .clip(RoundedCornerShape(2.dp))
        .background(VdtColors.TrackGray)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(2.dp)),
    ) {
      val track = maxWidth
      // The recipe's window. Drawn filled rather than outlined so the marker reads as inside or
      // outside a solid block at a glance, at any tile size.
      Box(
        Modifier
          .offset(x = track * min)
          .width(track * (max - min))
          .fillMaxHeight()
          .background(if (inWindow) VdtColors.AccentText.copy(alpha = 0.35f) else VdtColors.Gray),
      )
      // Where this ingredient actually sits. Clamped so a wildly over-filled ingredient still shows
      // at the end of the track instead of being laid out off it.
      val at = (track * share.toFloat().coerceIn(0f, 1f)) - MARKER_WIDTH / 2
      Box(
        Modifier
          .offset(
            x = if (at < 0.dp) {
              0.dp
            } else if (at > track - MARKER_WIDTH) {
              track - MARKER_WIDTH
            } else {
              at
            },
          )
          .width(MARKER_WIDTH)
          .fillMaxHeight()
          .background(if (flagged) VdtColors.Amber else VdtColors.TextDark),
      )
    }

    // What to do about it, when there is something to do.
    val short = shortfall(mixer, ingredient)
    if (short != null) {
      Text(
        if (compact) "+${formatInt(short)} l" else "+${formatInt(short)} l to reach ${ingredient.minPercentage}%",
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        maxLines = 1,
      )
    }
  }
}

/**
 * Litres of [ingredient] to add to bring it up to its minimum share — the number the game never
 * shows and the whole reason to look at a mixer terminal.
 *
 * **Adding moves the denominator too**, which is what makes this arithmetic rather than a
 * subtraction: the share is of the load, so `(v + x) / (L + x) = min` solves to
 * `x = (min·L − v) / (1 − min)`. Taking the naive `min·L − v` under-reports by a third at a 25%
 * target and by three times at 75%, and would have the driver top up twice.
 *
 * Null when there is nothing to say: the ingredient is already inside its window, the tub is empty
 * (nothing is wrong with an empty wagon), or the recipe demands the whole load be this one thing,
 * which no amount of adding it can fix.
 *
 * Internal so it can be tested directly — like [mergeFillUnits], a mistake here reads as a plausible
 * number rather than an error.
 */
internal fun shortfall(mixer: Mixer, ingredient: MixerIngredient): Int? {
  if (mixer.value <= 0.0 || mixer.loaded <= 0.0) return null
  val min = ingredient.minPercentage / 100.0
  if (min <= 0.0 || min >= 1.0) return null
  if (mixer.shareOf(ingredient) >= min) return null
  val litres = ((min * mixer.loaded - ingredient.value) / (1 - min)).roundToInt()
  return if (litres > 0) litres else null
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/** Tonnes, dropping to kilograms below one — the same switch the game's own `formatMass` makes. */
internal fun formatTonnes(tonnes: Double): String =
  if (tonnes < 1.0) "${formatInt((tonnes * 1000).roundToInt())} kg" else "${format1(tonnes.toFloat())} t"

/** A mix countdown, in seconds with one decimal while it is short enough to watch. */
internal fun formatSeconds(ms: Int): String =
  if (ms >= 10_000) "${(ms / 1000.0).roundToInt()}s" else "${format1(ms / 1000f)}s"
