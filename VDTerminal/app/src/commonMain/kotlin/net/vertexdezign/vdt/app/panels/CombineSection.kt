package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.components.format2
import net.vertexdezign.vdt.app.resources.Res
import net.vertexdezign.vdt.app.resources.isobus_combine
import net.vertexdezign.vdt.app.resources.isobus_forage_harvester
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Cutter
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.Harvest
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

/**
 * The harvesting rig, as the ISOBUS screen treats it: **one machine**.
 *
 * It is two on the wire. The `Combine` specialization sits on whatever threshes — a self-propelled
 * harvester, but also a towed or stationary one — and the `Cutter` specialization sits on the header
 * hitched in front of it. Neither half is worth a screen alone: the combine cannot say what is under
 * the header right now and the header cannot say what is in the tank, and the game moves its own
 * selection between them freely (the committed forage-harvester capture has the *header* selected
 * mid-pass).
 *
 * So both nodes carry [IsoBusMachine.hasSection] and both resolve to this pair. What changes when the
 * driver taps from one to the other on the rig diagram is which machine the *generic* controls above
 * address — not the screen underneath.
 */
internal data class CombineRig(val combine: IsoBusMachine?, val header: IsoBusMachine?) {
  val harvest: Harvest? get() = combine?.harvest
  val cutter: Cutter? get() = header?.cutter

  /**
   * The grain tank, or null when there is nothing that deserves the name.
   *
   * Two ways to have no tank. A **buffer combine** — a forage harvester, a beet or potato harvester —
   * holds what it cuts only until the pipe passes it on, and its fill unit is not a store: the
   * captured 9900i's only unit is its *silage-additive* tank, and drawing that as a grain level would
   * be a picture of the wrong thing entirely. And a machine whose tank the mod could not name at all.
   *
   * Where there is a tank, it is found by [Harvest.fillType] rather than by taking the first unit: a
   * harvester carrying additive, fuel-tank quirks and a grain bin has more than one, and the aspect
   * already says which material the tank is taking.
   */
  val tank: FillUnit?
    get() {
      val harvest = harvest ?: return null
      if (harvest.bufferCombine == true) return null
      val units = combine?.fillUnits.orEmpty()
      return units.firstOrNull { it.type != null && it.type == harvest.fillType }
        ?: units.firstOrNull()
    }

  /**
   * Everything the machine carries that is **not** the grain tank.
   *
   * The panel's generic fill-unit block stands down on any machine with a section, so that a mixer
   * wagon's tub is not drawn once in its section and again underneath it. A harvester is the case
   * that rule does not cover on its own: the section draws *one* of its units on the bin and a forage
   * harvester's silage-additive tank is not that one — on the captured 9900i it is the only unit
   * there is, so leaving this to the generic block would have dropped it off the screen entirely.
   */
  val otherUnits: List<FillUnit>
    get() {
      val drawn = tank
      return combine?.fillUnits.orEmpty().filter { it !== drawn }
    }

  /**
   * How wide the header cuts, in metres.
   *
   * Off the header's `CUTTER` work area, which is the only place it appears — [net.vertexdezign.vdt.model.WorkWidth]
   * is for tools with retractable sections and a header is not one. Deliberately not read off the
   * combine: its own areas are `COMBINESWATH` and `COMBINECHOPPER`, the straw behind the machine,
   * whose widths are a fraction of and wider than the cut respectively.
   */
  val cutWidth: Float?
    get() = header?.workAreas?.firstOrNull { it.type == "CUTTER" }?.width
      ?: header?.workAreas?.firstOrNull()?.width
}

/** The pair on this rig, or null when nothing on it harvests. */
internal fun combineRigOf(machines: List<IsoBusMachine>): CombineRig? {
  val combine = machines.firstOrNull { it.harvest != null }
  val header = machines.firstOrNull { it.cutter != null }
  if (combine == null && header == null) return null
  return CombineRig(combine, header)
}

/** Aspect ratio (w/h) of both harvester drawables — 1200×400, so exactly 3:1. */
private const val COMBINE_ART_ASPECT = 3f

/**
 * The grain bin on `isobus_combine.png`, as fractions of the art's box.
 *
 * Measured off the asset: the largest rectangle that stays on the raised tank's flank, clear of the
 * sloped front-left end and of the step down at its back. A combine seen from the side shows very
 * little bin — this is 9% of the art's height — which is exactly why the *figures* are set beneath
 * the machine and only the level itself is drawn here.
 */
private const val BIN_LEFT = 0.364f
private const val BIN_RIGHT = 0.565f
private const val BIN_TOP = 0.073f
private const val BIN_BOTTOM = 0.160f

/**
 * The colour of grain in the bin.
 *
 * A quantity, not a state: how high it stands is what carries it, and the panel prints the litres
 * under the machine besides — so this is free to be the colour grain actually is, against the green
 * it sits in. See `VDTerminal/README.md` → "Design rules".
 */
private val GRAIN = Color(0xFFD9A441)

/** The bin's own outline, so an empty tank still reads as a gauge rather than as a missing one. */
private val BIN_EDGE = Color(0xFF13300F)

/** Below this the art says less than the room it costs, and the section drops it for the figures. */
private val MIN_ART_HEIGHT = 64.dp

/** Wide enough for the three columns the brief asks for: info, machine, info. */
private val THREE_COLUMN_FROM = 520.dp

/** Past this the flanking columns are too narrow for a figure's value line, and the layout stacks. */
private val FLANK_MIN = 118.dp

/** Between the three columns. */
private val COLUMN_GAP = 10.dp

/**
 * The most of the section's width the machine may take.
 *
 * It is the middle of three columns and it is the reason to look, so it gets the majority — but not
 * so much of it that "43.83 t/h" starts wrapping in the flank beside it. Past this share the picture
 * is big enough and the width is worth more to the readouts.
 */
private const val ART_SHARE = 0.55f

/** What [TankLine] comes to, reserved out of the height before the art is given its cap. */
private val TANK_LINE_HEIGHT = 30.dp

/** What [BottomStrip] comes to, likewise: it is a sibling of the columns, not part of them. */
private val BOTTOM_STRIP_HEIGHT = 25.dp

/**
 * Below this the flanks give up their figures' sub-lines and drop a type size.
 *
 * Set by the right column at its fullest — two figures, the load bar and the straw control come to
 * about 145dp with the gaps — plus the headroom a third figure needs. A 3-row widget tile lands just
 * under it, which is the case that found this: the drum bar and the straw switch were being pushed
 * out of the bottom of the column and clipped.
 */
private val COMPACT_BELOW = 190.dp

/**
 * Between the readouts in a flank.
 *
 * The compact figure is the tighter of the two and still buys back about 9dp over the column, which
 * is roughly what the straw control costs — so the thing at the bottom of the stack keeps its room
 * rather than being the one pushed off the end.
 */
private val FLANK_GAP = 8.dp
private val FLANK_GAP_COMPACT = 5.dp

/**
 * The combine screen: the machine in the middle, what it is taking on the left, how it is going on
 * the right, and the header along the bottom.
 *
 * The layout is the one thing here that is not derived from the data — it is the shape a harvester's
 * own terminal has, and the shape the issue asked for. Everything inside it is: the bin is drawn only
 * where there is a tank, the performance column is empty of everything Combine XP would have supplied
 * when that mod is absent, and the straw control appears only on a machine that offers the choice.
 *
 * Measures itself and thins out rather than taking a "compact" flag, like every other panel here.
 */
@Composable
internal fun CombineSection(
  rig: CombineRig,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier) {
    // Hoisted out of the scope: further in, these read as the enclosing Column's, not the box's.
    val bodyWidth = maxWidth
    val bodyHeight = maxHeight

    // The columns share what is left once the header strip has taken its row along the bottom; every
    // height decision below is against that, not against the whole section.
    val columnHeight = bodyHeight - BOTTOM_STRIP_HEIGHT
    // Short tiles thin the figures out rather than letting the column overflow. A Column that is
    // handed more content than it has room for does not scroll or shrink — it pushes the last
    // children off the bottom, and the last children here are the load bar and the straw control.
    val compact = columnHeight < COMPACT_BELOW

    // The machine is sized from BOTH axes rather than given a fixed share of the width. It is 3:1, so
    // past the width that makes it as tall as the section there is nothing more to see — a wider
    // picture would only push the readouts thinner. Whichever of the two bounds binds first is the
    // one that decides, and the flanks take what is left.
    val artWidth = minOf(
      (bodyWidth - COLUMN_GAP * 2) * ART_SHARE,
      (columnHeight - TANK_LINE_HEIGHT) * COMBINE_ART_ASPECT,
    )
    // Below its floor the picture is **dropped, not shrunk**, and its width goes back to the readouts
    // — the same call the mixer wagon's section makes, for the same reason: the machine is orientation
    // and the figures are the reason to look. A shrunk 3:1 machine is a green smear either way.
    val showArt = artWidth / COMBINE_ART_ASPECT >= MIN_ART_HEIGHT
    val flank =
      if (showArt) (bodyWidth - COLUMN_GAP * 2 - artWidth) / 2 else (bodyWidth - COLUMN_GAP) / 2
    // Two or three columns only while the flanks are wide enough to hold a figure's value on one line.
    // That is a stricter test than the width alone, and it is the one that actually decides.
    val threeColumn = bodyWidth >= THREE_COLUMN_FROM && flank >= FLANK_MIN

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (threeColumn) {
        // The three groups are **centred in their own height**, not stacked from the top. The picture
        // is 3:1 and the readouts either side of it are three figures deep, so on a full page there is
        // always more height here than content — spent at the bottom it reads as a screen that failed
        // to finish, and split above and below it reads as a screen laid out for the space it has.
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
          CropColumn(rig, compact, Modifier.width(flank).fillMaxHeight())
          if (showArt) {
            MachineColumn(rig, columnHeight - TANK_LINE_HEIGHT, Modifier.width(artWidth).fillMaxHeight())
          } else {
            // No picture, so the tank's figures still need a home; they lead the readouts instead.
            TankLine(rig)
          }
          WorkColumn(rig, target, onCommand, compact, Modifier.width(flank).fillMaxHeight())
        }
      } else {
        // Narrow: the same three groups, stacked. The machine keeps its place between them — it is
        // still the thing the two readouts are about — but gives up its height first, and then the
        // picture entirely.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          CropColumn(rig, compact, Modifier.fillMaxWidth())
          if (bodyHeight >= MIN_ART_HEIGHT * 3) {
            MachineColumn(rig, bodyHeight * 0.45f, Modifier.fillMaxWidth())
          } else {
            TankLine(rig)
          }
          WorkColumn(rig, target, onCommand, compact, Modifier.fillMaxWidth())
        }
      }

      BottomStrip(rig, strawRefusal(rig.harvest), Modifier.fillMaxWidth())
    }
  }
}

// ---------------------------------------------------------------------------
// Left: what is being taken
// ---------------------------------------------------------------------------

/**
 * What the machine is threshing, in the panel header — the mixer wagon's mix state in the same slot,
 * and for the same reason: it is the one fact you look up at the tile for without reading it.
 *
 * The glyph carries whether crop is moving, so the state is never told by tint alone: an arrow into
 * the tank while it is filling, a pause bar when nothing is.
 */
@Composable
internal fun RowScope.HarvestChip(rig: CombineRig) {
  val harvest = rig.harvest
  val filling = harvest?.filling == true
  val name = rig.cutter?.title ?: harvest?.title ?: harvest?.fruitType ?: "Idle"
  val tint = if (filling) VdtColors.AccentText else VdtColors.DarkGray
  Icon(
    if (filling) Icons.Filled.ArrowDownward else Icons.Filled.Pause,
    contentDescription = null,
    tint = tint,
    modifier = Modifier.size(12.dp),
  )
  Text(name.uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/**
 * The crop, whether it is flowing, and whether the weather is about to stop it.
 *
 * The crop is named by the **fill type's** localized title, because that is the only name the mod can
 * localize: a fruit type has no printable name in the game's own data, only a token. Where the two
 * differ the machine is converting — maize into chaff — and the token is worth a line of its own,
 * since "Häckselgut" alone does not say what is being chopped.
 */
@Composable
private fun CropFigure(rig: CombineRig, compact: Boolean) {
  val harvest = rig.harvest
  // The header knows the crop under it *now*; the combine keeps the last valid one. Preferring the
  // header means the readout names the new crop as the machine drives into it, and falls back to what
  // the tank last took while the header is over bare ground.
  val fruit = rig.cutter?.fruitType ?: harvest?.fruitType
  val title = rig.cutter?.title ?: harvest?.title
  val fill = rig.cutter?.fillType ?: harvest?.fillType
  val converted = fruit != null && fill != null && fruit != fill
  Figure(
    label = "Crop",
    value = title ?: fruit ?: "—",
    // The conversion is the first thing to go on a short tile: the material in the tank is named
    // either way, and what it was cut from is context rather than a reading.
    sub = if (converted && !compact) "from $fruit" else null,
    compact = compact,
  )
}

/**
 * The left column: **the field** — what is coming off it, how much of it has been done, and whether
 * anything is stopping the pass.
 *
 * Worked hectares sit here rather than with the performance figures on the right, which is where they
 * started. Two reasons, and the second is why they moved. They belong here on the merits — a count of
 * ground covered is a fact about the field, where throughput and yield are facts about the machine —
 * and the right column had four things stacked on it while this one had two, so on a short tile the
 * *controls* at the bottom of that stack were the ones pushed off the end.
 */
@Composable
private fun CropColumn(rig: CombineRig, compact: Boolean, modifier: Modifier = Modifier) {
  val harvest = rig.harvest
  Column(
    modifier,
    verticalArrangement = Arrangement.spacedBy(
      if (compact) FLANK_GAP_COMPACT else FLANK_GAP,
      Alignment.CenterVertically,
    ),
  ) {
    CropFigure(rig, compact)
    harvest?.hectares?.let { total ->
      Figure(
        "Worked",
        "${format2(total.toFloat())} ha",
        harvest.hectaresSession?.takeIf { !compact }?.let { "+${format2(it.toFloat())} this session" },
        compact,
      )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      FlowChip(rig)
      RainChip(rig.harvest)
      MoistureChip(rig.harvest)
    }
    // Whatever else the machine is carrying — the additive tank, most often — since the panel's own
    // fill-unit block stands down wherever a section is drawn. See [CombineRig.otherUnits].
    val others = rig.otherUnits
    if (others.isNotEmpty()) {
      FillUnitsDisplay(others, Modifier.fillMaxWidth(), spacing = 4)
    }
  }
}

/**
 * Whether crop is entering the tank right now.
 *
 * `harvest.filling` and not the header's `working`, and not `isTurnedOn`: a combine driving over
 * stubble with the drum running is turned on and taking nothing, and a header at the edge of a stand
 * can be cutting a beat before the threshing drum passes anything on. This is the one that says the
 * machine is earning its diesel.
 */
@Composable
private fun FlowChip(rig: CombineRig) {
  val harvest = rig.harvest ?: return
  if (harvest.filling) {
    Chip(Icons.Filled.ArrowDownward, "Filling", VdtColors.AccentText)
  } else {
    Chip(Icons.Filled.Pause, "No flow", VdtColors.DarkGray)
  }
}

/**
 * The two rain states, which are one warning and one stop.
 *
 * The engine answers rain in two steps: the early warning fires at a tenth of the rainfall the stop
 * needs, which is roughly the half hour of notice a driver can act on — finish the run, or head for
 * the headland. Both are named in words and marked with the forecast's own rain glyph, so neither the
 * state nor which of the two it is depends on the tint.
 *
 * A machine whose XML lets it thresh in the rain answers false to both and shows nothing, which is
 * correct: there is no weather for it to mind.
 */
@Composable
private fun RainChip(harvest: Harvest?) {
  when {
    harvest?.rainBlocked == true -> Chip(WeatherIcons.Rain, "Rain — stopped", VdtColors.Red)
    harvest?.rainWarning == true -> Chip(WeatherIcons.Rain, "Rain coming", VdtColors.Amber)
    else -> Unit
  }
}

/** Combine XP's own "too damp for full speed", which is a different complaint from rain falling now. */
@Composable
private fun MoistureChip(harvest: Harvest?) {
  if (harvest?.combineXp?.highMoisture == true) {
    Chip(Icons.Filled.WaterDrop, "Damp crop", VdtColors.Amber)
  }
}

// ---------------------------------------------------------------------------
// Middle: the machine
// ---------------------------------------------------------------------------

/**
 * The picture, with the grain standing in the bin, and the tank's figures under it.
 *
 * [maxArt] caps the art rather than weighting it, so the picture keeps its natural 3:1 height on a
 * tall tile and gives it up on a short one. A weighted child would have stretched the *column* to the
 * section's full height, which is what put the group at the top instead of the middle of it.
 */
@Composable
private fun MachineColumn(rig: CombineRig, maxArt: Dp, modifier: Modifier = Modifier) {
  Column(
    modifier,
    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    MachineArt(rig, Modifier.fillMaxWidth().heightIn(max = maxArt.coerceAtLeast(0.dp)))
    TankLine(rig)
  }
}

/**
 * The harvester, with the level standing in its grain bin.
 *
 * Two machines rather than one generic silhouette, because the difference is the one a harvester
 * screen is *about*: a combine has a tank and a forage harvester does not, and `harvest.bufferCombine`
 * — the engine's own flag for it — picks between them. Everything else on this screen follows the
 * same split, so drawing one machine for both would have the picture contradicting the readout.
 *
 * The level is drawn rather than written, which is the one place this differs from the mixer wagon's
 * art: a mixer's contents are visible from the top and a grain tank's are not, so a rising fill is
 * what the machine actually looks like. It stays a *bar*, though — the bin is a tenth of the art's
 * height in a side view, so the litres go underneath ([TankLine]) where they can be read.
 */
@Composable
private fun MachineArt(rig: CombineRig, modifier: Modifier = Modifier) {
  val buffer = rig.harvest?.bufferCombine == true
  BoxWithConstraints(modifier) {
    if (maxHeight < MIN_ART_HEIGHT) return@BoxWithConstraints

    // The largest 3:1 box that fits, letterboxed on the long axis — same treatment as the mixer's.
    val boxW: Dp
    val boxH: Dp
    if (maxWidth / maxHeight >= COMBINE_ART_ASPECT) {
      boxH = maxHeight
      boxW = maxHeight * COMBINE_ART_ASPECT
    } else {
      boxW = maxWidth
      boxH = maxWidth / COMBINE_ART_ASPECT
    }

    Box(Modifier.size(boxW, boxH).align(Alignment.Center)) {
      Image(
        painterResource(if (buffer) Res.drawable.isobus_forage_harvester else Res.drawable.isobus_combine),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )

      val tank = rig.tank
      if (!buffer && tank != null) {
        val binW = boxW * (BIN_RIGHT - BIN_LEFT)
        val binH = boxH * (BIN_BOTTOM - BIN_TOP)
        val level = (tank.fillLevelPercentage.coerceIn(0, 100)) / 100f
        Box(
          Modifier
            .offset(x = boxW * BIN_LEFT, y = boxH * BIN_TOP)
            .width(binW)
            .height(binH)
            // Outlined whatever the level, so an empty tank reads as an empty gauge rather than as a
            // machine this panel forgot to draw one on.
            .border(1.dp, BIN_EDGE),
          contentAlignment = Alignment.BottomStart,
        ) {
          Box(Modifier.fillMaxWidth().height(binH * level).background(GRAIN))
        }
      }
    }
  }
}

/**
 * What is in the tank, in figures, directly under the machine that holds it.
 *
 * The percentage leads because it is what the level on the bin above is showing, and the litres
 * follow it — the same two numbers, one for glancing at and one for deciding on.
 *
 * A buffer combine says so instead of showing an empty pair. It genuinely holds nothing: what it cuts
 * goes straight up the spout, and a "0 l" would read as a machine that has stopped taking crop.
 */
@Composable
private fun TankLine(rig: CombineRig) {
  val tank = rig.tank
  if (tank == null) {
    val out = rig.harvest?.title ?: rig.cutter?.title
    Text(
      if (out != null) "No tank — $out straight out the spout" else "No tank — straight out the spout",
      color = VdtColors.DarkGray,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
    return
  }
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
    Text(
      "${tank.fillLevelPercentage}%",
      color = VdtColors.TextDark,
      fontSize = 18.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
    Text(
      "${formatInt(tank.value.roundToInt())} of ${formatInt(tank.capacity)} ${tank.unit}",
      color = VdtColors.DarkGray,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(bottom = 2.dp),
    )
  }
}

// ---------------------------------------------------------------------------
// Right: how it is going, and the one control a harvester has
// ---------------------------------------------------------------------------

/**
 * The performance figures and the straw choice.
 *
 * Throughput and yield come from FS25_CombineXP and simply are not there without it — the base game
 * measures neither, and inventing a figure from litres per second would be a number the driver could
 * not check against anything in the cab. Worked hectares are the engine's own and always present.
 */
@Composable
private fun WorkColumn(
  rig: CombineRig,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  val harvest = rig.harvest
  val xp = harvest?.combineXp
  Column(
    modifier,
    verticalArrangement = Arrangement.spacedBy(
      if (compact) FLANK_GAP_COMPACT else FLANK_GAP,
      Alignment.CenterVertically,
    ),
  ) {
    xp?.throughput?.let { Figure("Throughput", "${format2(it.toFloat())} t/h", null, compact) }
    xp?.yield?.let { Figure("Yield", "${format2(it.toFloat())} t/ha", null, compact) }

    LoadBar(rig)

    // The limiter's own ceiling, and only where it is real: the mod computes it server-side and never
    // streams it, so on a joined client it is absent rather than the load-time default.
    xp?.speedLimit?.let {
      Chip(Icons.Filled.Speed, "Limit ${format2(it.toFloat())} km/h", VdtColors.DarkGray)
    }

    StrawControl(harvest, target, onCommand, compact)
  }
}

/**
 * How hard the machine is being worked, as a bar.
 *
 * Two different measurements can answer it and they are not interchangeable, so the bar says which
 * one it is drawing. Combine XP's `load` is the **drum** against the machine's rated capacity, which
 * is the number that mod exists to publish and the one its own HUD shows. Without that mod the only
 * load anywhere is the **header's**, and that one is server-side — absent on a joined client, where a
 * hard zero would claim an idle header rather than an unknown one. Nothing is drawn when neither is
 * available, which is the honest state on a vanilla multiplayer client.
 */
@Composable
private fun LoadBar(rig: CombineRig) {
  val drum = rig.harvest?.combineXp?.load
  val head = rig.cutter?.load
  val (fraction, label) = when {
    drum != null -> drum.toFloat() to "DRUM"
    head != null -> head.toFloat() to "HEADER"
    else -> return
  }
  ProgressBar(
    fraction.coerceIn(0f, 1f),
    modifier = Modifier.fillMaxWidth(),
    leftLabel = label,
    rightLabel = "${(fraction * 100).roundToInt()}%",
  )
}

/**
 * Swath or chopper: the one thing a harvester operator changes mid-field, and so the one control this
 * screen adds to the generic strip above it.
 *
 * Drawn as the choice it is — both options always visible, the live one filled — rather than as a
 * button that toggles. A toggle would have to say which way it is about to go, and on a screen glanced
 * at from a moving cab "the state" and "what pressing does" are one thing too many.
 *
 * **The gate is `canToggleSwath`, not the two `*Available` flags.** The game binds its own key only on
 * a machine that has both a swath and a chopper, and then refuses anyway — with a blinking warning,
 * having changed nothing — on a crop that drops no windrow. Maize is the everyday case, and it is a
 * fact only the engine holds, so the mod exports its verdict rather than leaving it to be re-derived
 * here. When both flags are set and the verdict is still no, the crop is the only thing left it can
 * be, and the control says so.
 */
@Composable
private fun StrawControl(
  harvest: Harvest?,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  compact: Boolean = false,
) {
  // The refusal that goes with this control is drawn on the bottom strip, not here — see
  // [strawRefusal].
  if (harvest == null) return
  val hasSwath = harvest.swathAvailable == true
  val hasChopper = harvest.chopperAvailable == true
  // A machine with neither has no straw to place; one with only a chopper never had a choice to show.
  if (!hasSwath) return

  val send: ((Boolean) -> Unit)? =
    if (harvest.canToggleSwath == true && target != null) {
      { on -> onCommand(ClientMessage.SetSwath(target, on)) }
    } else {
      null
    }

  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    // The caption goes first on a short tile: the two buttons already say "Swath" and "Chop", which
    // is the only place in this panel where a label repeats what the control under it reads.
    if (!compact) {
      Text("STRAW", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      StrawOption("Swath", Icons.Filled.Grass, harvest.swathActive, send?.let { { it(true) } })
      if (hasChopper) {
        StrawOption("Chop", Icons.Filled.ContentCut, !harvest.swathActive, send?.let { { it(false) } })
      }
    }
  }
}

/**
 * Why the straw control is dead, when it is — or null when it is not.
 *
 * Both halves of the choice present and the engine still refusing leaves exactly one explanation, and
 * it is the useful one: the crop in the tank drops no windrow. See [Harvest.canToggleSwath].
 *
 * Computed apart from the control it explains because it is **drawn apart from it**, along the bottom
 * strip rather than under the buttons. It is a sentence, and a sentence under a control in a 200dp
 * column is the most expensive thing on the screen — it was clipped on the first 3-row tile it met.
 * The bottom strip is a row with nothing in its right half, which is where a sentence goes.
 *
 * Internal so it can be tested directly: the difference between `false` and **null** here is the
 * difference between an engine that refuses and an export from before mod version 22 that was never
 * asked, and only the first of those has a reason to give.
 */
internal fun strawRefusal(harvest: Harvest?): String? {
  if (harvest == null) return null
  if (harvest.swathAvailable != true || harvest.chopperAvailable != true) return null
  return if (harvest.canToggleSwath == false) "This crop leaves no straw" else null
}

/**
 * One half of the straw choice.
 *
 * The live one is told by **fill and weight** — dark ground, white bold type — against a plain
 * outlined box, so which is set survives being read in one ink. An option with no tap behind it keeps
 * the same two shapes and loses its border and its contrast, which is what "you cannot change this"
 * looks like everywhere else in the terminal.
 */
@Composable
private fun RowScope.StrawOption(label: String, icon: ImageVector, active: Boolean, onClick: (() -> Unit)?) {
  val shape = RoundedCornerShape(3.dp)
  val ink = when {
    active -> VdtColors.White
    onClick == null -> VdtColors.TextDisabled
    else -> VdtColors.DarkGray
  }
  var box = Modifier.weight(1f).clip(shape)
  box = if (active) {
    box.background(VdtColors.TextDark)
  } else {
    box.background(VdtColors.White).border(1.dp, VdtColors.PanelBorder, shape)
  }
  if (onClick != null) box = box.clickable(onClick = onClick)
  Row(
    box.padding(horizontal = 6.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
    Text(label, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

// ---------------------------------------------------------------------------
// Bottom: the header
// ---------------------------------------------------------------------------

/**
 * The strip along the bottom: the header on the left, and on the right whatever the column above had
 * no room to say.
 *
 * The header is here rather than in a column because it is the *other* machine — it has its own name,
 * its own condition and its own row on the rig diagram, and burying it in a flank would read as one
 * more figure about the combine. It never fills the row, which is what makes the right half the
 * natural home for a sentence: see [strawRefusal].
 *
 * "Cutting" here is the engine's own 300 ms window, not this frame. The underlying flag is written and
 * cleared inside work-area processing, so a poll lands on a false frame often enough to matter — the
 * first capture of a chopper mid-pass caught exactly that, the machine filling and the header reading
 * idle.
 */
@Composable
private fun BottomStrip(rig: CombineRig, note: String?, modifier: Modifier = Modifier) {
  val header = rig.header
  val cutter = header?.cutter
  if (cutter == null && note == null) return
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    // The header FILLS its weight, which is what puts the note against the far edge of the row rather
    // than against the last chip of the header. A non-filling weight sizes the header to its content
    // and leaves the note trailing it in the middle of the strip, reading as one more thing about the
    // header — which it is not: it is about the control diagonally above it.
    if (cutter != null) {
      HeaderRow(rig, header, cutter, Modifier.weight(1f))
    } else {
      Spacer(Modifier.weight(1f))
    }
    if (note != null) {
      Text(
        note,
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The header's own half of [BottomStrip]: what it is called, how wide it cuts, and what it is doing. */
@Composable
private fun HeaderRow(rig: CombineRig, header: IsoBusMachine, cutter: Cutter, modifier: Modifier = Modifier) {
  FlowRow(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      header.name,
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.align(Alignment.CenterVertically),
    )
    rig.cutWidth?.let {
      Text(
        "${format2(it)} m",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier.align(Alignment.CenterVertically),
      )
    }

    when {
      cutter.working && cutter.windrow -> Chip(Icons.Filled.Grass, "Picking up", VdtColors.AccentText)
      cutter.working -> Chip(Icons.Filled.ContentCut, "Cutting", VdtColors.AccentText)
      cutter.windrow -> Chip(Icons.Filled.Grass, "Windrow pickup", VdtColors.DarkGray)
      else -> Chip(Icons.Filled.Pause, "Not cutting", VdtColors.DarkGray)
    }

    // Only where it explains something: a header up and still taking crop looks like a fault in the
    // readout unless the machine is one of the drapers and pickups that declare they may.
    if (cutter.cutWhileRaised && header.lowered == false) {
      Chip(Icons.Filled.PriorityHigh, "Cuts raised", VdtColors.DarkGray)
    }

    // What the swath will be made of, and only while there is going to be one — this is a machine
    // constant, and it is the reason two headers on the same field leave different swaths.
    if (rig.harvest?.swathActive == true) {
      cutter.strawRatio?.let { Chip(Icons.Filled.Grass, "Straw ${(it * 100).roundToInt()}%", VdtColors.DarkGray) }
    }
  }
}
