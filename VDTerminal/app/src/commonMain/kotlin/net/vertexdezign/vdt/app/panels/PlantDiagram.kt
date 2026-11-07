package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.components.ToolButton
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.BgaControl
import net.vertexdezign.vdt.model.BgaLevel
import net.vertexdezign.vdt.model.Construction
import net.vertexdezign.vdt.model.ConstructionPart
import net.vertexdezign.vdt.model.ProductionPoint
import kotlin.math.roundToInt

// A Pumps & Hoses biogas plant drawn as the process it is — feed stock on the left, the fermenters in
// the middle, what they make on the right — in the spirit of PnH BGA Control's own control screen.
// The plant page the construction entry in the Production list opens (see ProductionPanel.kt).
//
// It is drawn from two sources. The DLC's own readings are always there (the construction's parts:
// bunker and tank fills, the fermenter and cogeneration utilization, the torch) plus the two merged
// production points, whose lines are the plant's and the cogeneration's on/off. When BGA Control is
// installed its `control` block adds the process: temperature, biology, dry matter and pH, the feed
// rates, the mode and status words, and the four tanks the way that mod adds them up.
//
// The connectors are drawn behind the boxes, from the bounds each box reports once laid out, so the
// layout is free to wrap and reflow and the lines follow. A connector carrying flow is a solid line,
// an idle one dashed: shape, not hue (VDTerminal/README.md → "Design rules"). Left to right is fine
// here — this is a process diagram, not a machine seen from the side.

/** The boxes of the diagram, which the connectors join. */
internal enum class Node { SOLID, LIQUID, FERMENTER, CHP, TORCH, DIGESTATE, SEPARATOR }

/** One connector: material flowing [from] one box [to] another, solid while [active]. */
internal data class Edge(val from: Node, val to: Node, val active: Boolean)

/** The width from which the diagram is laid out in three columns rather than one. */
private val WIDE = 600.dp

/**
 * The plant page: [construction] drawn as a flow diagram. [points] are its production points — the
 * merged fermenter and cogeneration entries — whose lines carry the two on/off switches.
 */
@Composable
internal fun PlantDetail(
  construction: Construction,
  points: List<ProductionPoint>,
  onCommand: (ClientMessage) -> Unit,
) {
  val strings = LocalStrings.current
  val control = construction.control
  val parts = remember(construction) { construction.parts.associateBy { it.role } }
  val fermenterPoint = points.firstOrNull { it.construction?.role == "FERMENTER" }
  val chpPoint = points.firstOrNull { it.construction?.role == "POWERPLANT" }
  val running = fermenterPoint?.lines?.any { it.enabled } ?: false
  val chpOn = chpPoint?.lines?.any { it.enabled } ?: false

  // Which boxes there are: the DLC's parts, plus the two only BGA Control knows about.
  val hasLiquid = control != null && (control.stock.liquid.capacity > 0 || control.feed.liquid > 0)
  val hasSeparator = control?.separator != null
  val edges = remember(construction, running) { edgesOf(control, running, parts) }

  // The setpoints as the page holds them. Reset whenever the export brings different ones, so the
  // game's word always wins — but between two exports a second tap builds on the first rather than
  // on the stale export (the mod writes every 2 s, an MP client hears from its server every 5 s).
  val exported = control?.setpoints()
  var setpoints by remember(construction.id, exported) { mutableStateOf(exported) }
  val steering = setpoints?.let { current ->
    Steering(current) { next ->
      setpoints = next
      onCommand(
        ClientMessage.SetBgaSettings(
          construction.id,
          next.temperature,
          next.silage,
          next.liquid,
          next.agitator,
          next.recirculation,
          next.separator,
        ),
      )
    }
  }

  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(construction.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)

    val boxes: Map<Node, @Composable (Modifier) -> Unit> = buildMap {
      if (parts.containsKey("BUNKER") ||
        control != null
      ) {
        put(Node.SOLID) { m -> SolidBox(parts["BUNKER"], control, steering, modifier = m) }
      }
      if (hasLiquid) put(Node.LIQUID) { m -> LiquidBox(control, steering, modifier = m) }
      put(Node.FERMENTER) { m ->
        FermenterBox(
          parts["FERMENTER"],
          control,
          steering,
          running,
          onToggle = { on ->
            fermenterPoint?.lines?.forEach {
              onCommand(ClientMessage.SetProductionEnabled(fermenterPoint.id, it.id, on))
            }
          },
          modifier = m,
        )
      }
      if (parts.containsKey("POWERPLANT")) {
        put(Node.CHP) { m ->
          ChpBox(
            parts["POWERPLANT"],
            control,
            chpOn,
            canToggle = chpPoint != null,
            onToggle = { on ->
              chpPoint?.lines?.forEach { onCommand(ClientMessage.SetProductionEnabled(chpPoint.id, it.id, on)) }
            },
            modifier = m,
          )
        }
      }
      if (parts.containsKey("TORCH")) put(Node.TORCH) { m -> TorchBox(parts["TORCH"], control, modifier = m) }
      if (parts.containsKey("SILO") ||
        control != null
      ) {
        put(Node.DIGESTATE) { m -> DigestateBox(parts["SILO"], control, steering, modifier = m) }
      }
      if (hasSeparator) put(Node.SEPARATOR) { m -> SeparatorBox(control, steering, modifier = m) }
    }

    FlowDiagram(boxes, edges)

    if (control != null) {
      val numbers = strings.numbers
      Text(
        strings.production.plantCosts(numbers.integer(control.costs.operating), numbers.integer(control.costs.upkeep)),
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

/** Every connector the plant has, and whether material is moving along it right now. */
internal fun edgesOf(control: BgaControl?, running: Boolean, parts: Map<String, ConstructionPart>): List<Edge> {
  fun util(role: String) = (parts[role]?.utilization ?: 0) > 0
  return buildList {
    // A feed set against an empty tank moves nothing — a capture had the bunker at 0 L with the silage
    // feed still at 15 200 L/h — so a feed line flows only while there is stock behind it.
    add(
      Edge(
        Node.SOLID,
        Node.FERMENTER,
        running && (control?.let { it.feed.silage > 0 && it.stock.solid.level > 0 } ?: util("BUNKER")),
      ),
    )
    add(
      Edge(
        Node.LIQUID,
        Node.FERMENTER,
        running && control != null && control.feed.liquid > 0 && control.stock.liquid.level > 0,
      ),
    )
    add(Edge(Node.FERMENTER, Node.CHP, control?.let { it.methane > 0 } ?: (running && util("POWERPLANT"))))
    // The torch burns what the cogeneration cannot take — the in-game screen draws it off the CHP too.
    add(Edge(Node.CHP, Node.TORCH, control?.let { it.torch > 0 } ?: util("TORCH")))
    add(Edge(Node.FERMENTER, Node.DIGESTATE, control?.let { it.digestate > 0 } ?: (running && util("FERMENTER"))))
    add(Edge(Node.DIGESTATE, Node.SEPARATOR, control?.separator?.state == "OPERATING"))
  }
}

/**
 * Lays [boxes] out in three columns when there is room (inputs, fermenter, outputs) and in one
 * otherwise, and draws [edges] behind them between whichever boxes exist.
 */
@Composable
private fun FlowDiagram(boxes: Map<Node, @Composable (Modifier) -> Unit>, edges: List<Edge>) {
  val bounds = remember { mutableStateMapOf<Node, Rect>() }
  val origin = remember { mutableStateOf(Offset.Zero) }
  val line = VdtColors.DarkGray

  // Forget the bounds of a box that has gone (a separator sold), or a stale line points at nothing.
  val present = boxes.keys
  LaunchedEffect(present) { bounds.keys.retainAll(present) }

  val place: @Composable (Node, Modifier) -> Unit = { node, modifier ->
    boxes[node]?.invoke(modifier.onGloballyPositioned { bounds[node] = it.boundsInRoot() })
  }

  BoxWithConstraints(
    Modifier
      .fillMaxWidth()
      .onGloballyPositioned { origin.value = it.positionInRoot() }
      .drawBehind {
        edges.forEach { edge ->
          val a = bounds[edge.from]?.takeIf { edge.from in present }?.translate(-origin.value)
          val b = bounds[edge.to]?.takeIf { edge.to in present }?.translate(-origin.value)
          if (a != null && b != null) drawConnector(a, b, edge.active, line)
        }
      },
  ) {
    if (maxWidth >= WIDE) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          place(Node.SOLID, Modifier.fillMaxWidth())
          place(Node.LIQUID, Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1.3f)) {
          place(Node.FERMENTER, Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
          place(Node.CHP, Modifier.fillMaxWidth())
          place(Node.TORCH, Modifier.fillMaxWidth())
          place(Node.DIGESTATE, Modifier.fillMaxWidth())
          place(Node.SEPARATOR, Modifier.fillMaxWidth())
        }
      }
    } else {
      // One column: each row sits under the box that feeds it, so every connector runs downward.
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PairRow(present, Node.SOLID, Node.LIQUID, place)
        place(Node.FERMENTER, Modifier.fillMaxWidth())
        PairRow(present, Node.CHP, Node.DIGESTATE, place)
        PairRow(present, Node.TORCH, Node.SEPARATOR, place)
      }
    }
  }
}

/**
 * Two boxes side by side in the one-column layout, or one alone across the full width. Nothing when
 * neither exists.
 */
@Composable
private fun PairRow(present: Set<Node>, left: Node, right: Node, place: @Composable (Node, Modifier) -> Unit) {
  val shown = listOf(left, right).filter { it in present }
  if (shown.isEmpty()) return
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    shown.forEach { node -> place(node, Modifier.weight(1f)) }
  }
}

/**
 * One connector from box [a] to box [b]. It runs sideways when [b] lies to the right of [a] and
 * downward otherwise; straight where the two boxes overlap on the other axis, with one jog where
 * they don't. An arrowhead marks the end it flows to.
 */
private fun DrawScope.drawConnector(a: Rect, b: Rect, active: Boolean, color: Color) {
  val margin = 14.dp.toPx()
  val sideways = b.left >= a.right - 1f
  val points: List<Offset> =
    if (sideways) {
      val top = maxOf(a.top, b.top) + margin
      val bottom = minOf(a.bottom, b.bottom) - margin
      if (top <= bottom) {
        val y = (top + bottom) / 2
        listOf(Offset(a.right, y), Offset(b.left, y))
      } else {
        val y1 = a.center.y.coerceIn(a.top + margin, a.bottom - margin)
        val y2 = b.center.y.coerceIn(b.top + margin, b.bottom - margin)
        val x = (a.right + b.left) / 2
        listOf(Offset(a.right, y1), Offset(x, y1), Offset(x, y2), Offset(b.left, y2))
      }
    } else {
      val left = maxOf(a.left, b.left) + margin
      val right = minOf(a.right, b.right) - margin
      if (left <= right) {
        val x = (left + right) / 2
        listOf(Offset(x, a.bottom), Offset(x, b.top))
      } else {
        val x1 = a.center.x.coerceIn(a.left + margin, a.right - margin)
        val x2 = b.center.x.coerceIn(b.left + margin, b.right - margin)
        val y = (a.bottom + b.top) / 2
        listOf(Offset(x1, a.bottom), Offset(x1, y), Offset(x2, y), Offset(x2, b.top))
      }
    }
  val head = 7.dp.toPx()
  val path = Path().apply {
    moveTo(points[0].x, points[0].y)
    points.drop(1).dropLast(1).forEach { lineTo(it.x, it.y) }
    // Stop the line where the arrowhead starts, so the dashes don't run through it.
    val end = points.last()
    val beforeEnd = points[points.size - 2]
    val dir = (end - beforeEnd).let { it / it.getDistance().coerceAtLeast(1f) }
    val stop = end - dir * head
    lineTo(stop.x, stop.y)
  }
  val alpha = if (active) 1f else 0.6f
  drawPath(
    path,
    color.copy(alpha = alpha),
    style = Stroke(
      width = if (active) 2.5.dp.toPx() else 1.5.dp.toPx(),
      cap = StrokeCap.Round,
      pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
    ),
  )
  val end = points.last()
  val beforeEnd = points[points.size - 2]
  val dir = (end - beforeEnd).let { it / it.getDistance().coerceAtLeast(1f) }
  val normal = Offset(-dir.y, dir.x)
  val base = end - dir * head
  val arrow = Path().apply {
    moveTo(end.x, end.y)
    lineTo((base + normal * (head * 0.6f)).x, (base + normal * (head * 0.6f)).y)
    lineTo((base - normal * (head * 0.6f)).x, (base - normal * (head * 0.6f)).y)
    close()
  }
  drawPath(arrow, color.copy(alpha = alpha))
}

// ---- Setpoints -----------------------------------------------------------------------------------

/** The six values BGA Control lets you set, in the app's units (the wire's, see SetBgaSettings). */
private data class Setpoints(
  val temperature: Int,
  val silage: Int,
  val liquid: Int,
  val agitator: Int,
  val recirculation: Int,
  val separator: Int?,
)

private fun BgaControl.setpoints() = Setpoints(
  temperature = temperature.target.roundToInt(),
  silage = feed.silage,
  liquid = feed.liquid,
  agitator = agitator,
  recirculation = recirculation,
  separator = separator?.flow,
)

/** The page's setpoints and the way to change one: [change] takes the whole new set. */
private class Steering(val setpoints: Setpoints, val change: (Setpoints) -> Unit)

// The steps and limits of BGA Control's own dialog: its buttons move the feeds by 200 L/h, the
// separator by 100 L/h, the agitator and recirculation by 10 % and the temperature by a degree.
private const val FEED_STEP = 200
private const val SEPARATOR_STEP = 100
private const val PERCENT_STEP = 10
private const val TEMPERATURE_MIN = 30
private const val TEMPERATURE_MAX = 55
private const val RECIRCULATION_MAX = 50
private const val SEPARATOR_MAX = 3000

/**
 * A setpoint with a lower and a raise button either side of its value. A button that would leave
 * [min]..[max] is disabled rather than hidden, so the row never shifts under a thumb mid-tap. [onSet]
 * gets the new value, already clamped.
 */
@Composable
private fun SetpointRow(
  label: String,
  shown: String,
  value: Int,
  step: Int,
  min: Int,
  max: Int,
  onSet: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = LocalStrings.current.production
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      label,
      color = VdtColors.DarkGray,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    ToolButton(
      Icons.Filled.Remove,
      p.decrease(label),
      onClick = { onSet((value - step).coerceIn(min, max)) },
      enabled = value > min,
      size = STEPPER_SIZE,
    )
    Text(
      shown,
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 4.dp),
    )
    ToolButton(
      Icons.Filled.Add,
      p.increase(label),
      onClick = { onSet((value + step).coerceIn(min, max)) },
      enabled = value < max,
      size = STEPPER_SIZE,
    )
  }
}

private val STEPPER_SIZE = 28.dp

// ---- The boxes -----------------------------------------------------------------------------------

/** A box of the diagram: an opaque surface (the connectors pass behind it), a title, a trailing slot. */
@Composable
private fun PlantBox(
  title: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface)
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        title.uppercase(),
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      trailing?.invoke()
    }
    content()
  }
}

/** A label and its value on one line. */
@Composable
private fun ValueRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = VdtColors.DarkGray, fontSize = 11.sp, maxLines = 1)
    Text(
      value,
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/** A sentence that is a warning: the warning mark carries it, the bold weight backs it up. */
@Composable
private fun StateLine(text: String, warning: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (warning) {
      Icon(Icons.Filled.Warning, null, tint = VdtColors.TextDark, modifier = Modifier.padding(end = 4.dp).size(14.dp))
    }
    Text(
      text,
      color = VdtColors.TextDark,
      fontSize = 12.sp,
      fontWeight = if (warning) FontWeight.Bold else FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun LevelBar(label: String, level: BgaLevel) {
  val strings = LocalStrings.current
  ProgressBar(
    fraction = if (level.capacity > 0) level.level.toFloat() / level.capacity.toFloat() else 0f,
    leftLabel = label,
    rightLabel = strings.common.litres(strings.numbers.integer(level.level), strings.numbers.integer(level.capacity)),
  )
}

/** The DLC's utilization of a part, as a bar with its band word — what the plant shows without BGA Control. */
@Composable
private fun UtilizationBar(part: ConstructionPart?) {
  val strings = LocalStrings.current
  val percent = part?.utilization ?: return
  ProgressBar(
    fraction = percent / 100f,
    leftLabel = strings.production.utilization,
    rightLabel = "$percent% · ${utilizationWord(strings, percent, part.role)}",
  )
}

@Composable
private fun PartFills(part: ConstructionPart?) {
  val strings = LocalStrings.current
  part?.fills?.forEach { fill ->
    ProgressBar(
      fraction = if (fill.capacity > 0) fill.level.toFloat() / fill.capacity.toFloat() else 0f,
      leftLabel = fill.title,
      rightLabel = strings.common.litres(strings.numbers.integer(fill.level), strings.numbers.integer(fill.capacity)),
    )
  }
}

private fun titled(label: String, part: ConstructionPart?): String =
  if (part != null && part.count > 1) "$label (${part.count})" else label

@Composable
private fun SolidBox(
  part: ConstructionPart?,
  control: BgaControl?,
  steering: Steering?,
  modifier: Modifier = Modifier,
) {
  val strings = LocalStrings.current
  val p = strings.production
  PlantBox(titled(p.roleBunkers, part), modifier) {
    if (control != null && steering != null) {
      LevelBar(p.fill, control.stock.solid)
      val sp = steering.setpoints
      SetpointRow(
        p.feed,
        p.perHour(strings.numbers.integer(sp.silage)),
        sp.silage,
        FEED_STEP,
        0,
        control.feed.silageMax,
        onSet = {
          steering.change(sp.copy(silage = it))
        },
      )
    } else {
      PartFills(part)
      UtilizationBar(part)
    }
  }
}

@Composable
private fun LiquidBox(control: BgaControl, steering: Steering?, modifier: Modifier = Modifier) {
  val strings = LocalStrings.current
  val p = strings.production
  PlantBox(p.roleSlurry, modifier) {
    LevelBar(p.fill, control.stock.liquid)
    val sp = steering?.setpoints ?: return@PlantBox
    SetpointRow(
      p.feed,
      p.perHour(strings.numbers.integer(sp.liquid)),
      sp.liquid,
      FEED_STEP,
      0,
      control.feed.liquidMax,
      onSet = {
        steering.change(sp.copy(liquid = it))
      },
    )
  }
}

@Composable
private fun FermenterBox(
  part: ConstructionPart?,
  control: BgaControl?,
  steering: Steering?,
  running: Boolean,
  onToggle: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalStrings.current
  val p = strings.production
  val numbers = strings.numbers
  PlantBox(titled(p.roleFermenters, part), modifier, trailing = { EnableToggle(running, onToggle) }) {
    if (control == null) {
      UtilizationBar(part)
      return@PlantBox
    }
    StateLine(bgaStatusLabel(strings, control), warning = control.status in WARNING_STATUSES)
    Text(
      bgaModeLabel(strings, control.mode),
      color = VdtColors.DarkGray,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
    )
    ValueRow(p.efficiency, "${control.efficiency}%")
    ValueRow(p.temperature, p.degrees(numbers.fixed(control.temperature.actual, 1)))
    val sp = steering?.setpoints
    if (sp != null) {
      SetpointRow(
        p.setpoint,
        p.degrees(sp.temperature.toString()),
        sp.temperature,
        1,
        TEMPERATURE_MIN,
        TEMPERATURE_MAX,
        onSet = {
          steering.change(sp.copy(temperature = it))
        },
      )
      SetpointRow(
        p.agitator,
        "${sp.agitator}%",
        sp.agitator,
        PERCENT_STEP,
        0,
        100,
        onSet = {
          steering.change(sp.copy(agitator = it))
        },
      )
    }
    ValueRow(p.dryMatter, "${numbers.fixed(control.dryMatter, 1)}%")
    ValueRow(p.ph, numbers.fixed(control.ph, 1))
    LevelBar(p.fill, control.stock.fermenter)
    val biology = control.biology
    val dying = biology.trend == "DYING"
    StateLine(
      "${p.biology}: ${biology.activity}% · ${bgaTrendLabel(strings, biology.trend)}",
      warning = dying || biology.trend == "STRESSED",
    )
    // The 1.5 h the bacteria hold out before they start dying — the one thing the in-game screen
    // never says outright. Only while there is stress to count down.
    if (dying) {
      Text(p.biologyDying, color = VdtColors.TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    } else if (biology.stress > 0 && biology.stressLimit > biology.stress) {
      val left = (biology.stressLimit - biology.stress) / 60
      Text(p.biologyBuffer(p.hoursMinutes(left / 60, left % 60)), color = VdtColors.TextDark, fontSize = 11.sp)
    }
  }
}

@Composable
private fun ChpBox(
  part: ConstructionPart?,
  control: BgaControl?,
  on: Boolean,
  canToggle: Boolean,
  onToggle: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalStrings.current
  val p = strings.production
  val numbers = strings.numbers
  PlantBox(
    titled(p.roleCogeneration, part),
    modifier,
    trailing = if (canToggle) {
      { EnableToggle(on, onToggle) }
    } else {
      null
    },
  ) {
    if (control != null) {
      ValueRow(p.methane, p.perHour(numbers.integer(control.methane)))
      ProgressBar(
        fraction = control.chp.load / 100f,
        leftLabel = p.load,
        rightLabel = "${control.chp.load}%",
      )
      ValueRow(p.income, "${numbers.integer(control.chp.income)}/h")
    } else {
      UtilizationBar(part)
    }
  }
}

@Composable
private fun TorchBox(part: ConstructionPart?, control: BgaControl?, modifier: Modifier = Modifier) {
  val strings = LocalStrings.current
  val p = strings.production
  PlantBox(p.roleTorch, modifier) {
    val percent = control?.torch ?: part?.utilization
    if (percent == null) {
      Text(p.noReading, color = VdtColors.DarkGray, fontSize = 10.sp)
    } else {
      ValueRow(utilizationWord(strings, percent, "TORCH"), "$percent%")
    }
  }
}

@Composable
private fun DigestateBox(
  part: ConstructionPart?,
  control: BgaControl?,
  steering: Steering?,
  modifier: Modifier = Modifier,
) {
  val strings = LocalStrings.current
  val p = strings.production
  PlantBox(titled(p.roleDigestate, part), modifier) {
    if (control != null && steering != null) {
      LevelBar(p.fill, control.stock.digestate)
      ValueRow(p.produced, p.perHour(strings.numbers.integer(control.digestate)))
      val sp = steering.setpoints
      SetpointRow(
        p.recirculation,
        "${sp.recirculation}%",
        sp.recirculation,
        PERCENT_STEP,
        0,
        RECIRCULATION_MAX,
        onSet = {
          steering.change(sp.copy(recirculation = it))
        },
      )
    } else {
      PartFills(part)
    }
  }
}

@Composable
private fun SeparatorBox(control: BgaControl, steering: Steering?, modifier: Modifier = Modifier) {
  val strings = LocalStrings.current
  val p = strings.production
  val separator = control.separator ?: return
  PlantBox(p.roleSeparator, modifier) {
    val sp = steering?.setpoints
    val flow = sp?.separator ?: separator.flow
    if (sp != null) {
      SetpointRow(
        p.throughput,
        p.perHour(strings.numbers.integer(flow)),
        flow,
        SEPARATOR_STEP,
        0,
        SEPARATOR_MAX,
        onSet = {
          steering.change(sp.copy(separator = it))
        },
      )
    } else {
      ValueRow(p.throughput, p.perHour(strings.numbers.integer(flow)))
    }
    separator.state?.let { Text(separatorLabel(strings, it), color = VdtColors.DarkGray, fontSize = 11.sp) }
  }
}

// ---- Words ---------------------------------------------------------------------------------------

/** Statuses that mean something is wrong with the process, not merely which state it is in. */
private val WARNING_STATUSES =
  setOf("COLD", "SCUM_DRY", "HIGH_VISCOSITY", "HIGH_SHEAR", "ACID_CRASH", "AMMONIA_INHIBITION")

internal fun bgaStatusLabel(strings: Strings, control: BgaControl): String =
  bgaStatusWord(strings, control) ?: neutral(control.status)

internal fun bgaStatusWord(strings: Strings, control: BgaControl): String? {
  val p = strings.production
  return when (control.status) {
    "STOPPED" -> p.bgaStatusStopped
    "STARTUP" -> p.bgaStatusStartup(control.biology.activity)
    "COLD" -> p.bgaStatusCold
    "ECONOMICAL_STABLE" -> p.bgaStatusEconomicalStable
    "RECIRC_STABLE" -> p.bgaStatusRecircStable
    "DRY_OK" -> p.bgaStatusDryOk
    "THERMOPHILIC" -> p.bgaStatusThermophilic
    "MESOPHILIC_OPTIMAL" -> p.bgaStatusMesophilicOptimal
    "BUFFERING" -> p.bgaStatusBuffering
    "NO_FEED" -> p.bgaStatusNoFeed
    "SCUM_DRY" -> p.bgaStatusScumDry
    "HIGH_VISCOSITY" -> p.bgaStatusHighViscosity
    "HIGH_SHEAR" -> p.bgaStatusHighShear
    "ACID_CRASH" -> p.bgaStatusAcidCrash(strings.numbers.fixed(control.ph, 1))
    "AMMONIA_INHIBITION" -> p.bgaStatusAmmoniaInhibition
    else -> null
  }
}

/** BGA Control's process mode, in words; also the plant row's subtitle in the list. */
internal fun bgaModeLabel(strings: Strings, mode: String): String = bgaModeWord(strings, mode) ?: neutral(mode)

internal fun bgaModeWord(strings: Strings, mode: String): String? {
  val p = strings.production
  return when (mode) {
    "INACTIVE" -> p.bgaModeInactive
    "LIQUID_MONO" -> p.bgaModeLiquidMono
    "SOLID_MONO" -> p.bgaModeSolidMono
    "SOLID_MULTI" -> p.bgaModeSolidMulti
    "RECIRCULATION" -> p.bgaModeRecirculation
    "MULTI_SUBSTRATE" -> p.bgaModeMultiSubstrate
    "CO_FERMENTATION" -> p.bgaModeCoFermentation
    "MANURE_LIQUID" -> p.bgaModeManureLiquid
    "BEET_FERMENTATION" -> p.bgaModeBeetFermentation
    else -> null
  }
}

internal fun bgaTrendLabel(strings: Strings, trend: String): String = bgaTrendWord(strings, trend) ?: neutral(trend)

internal fun bgaTrendWord(strings: Strings, trend: String): String? {
  val p = strings.production
  return when (trend) {
    "DYING" -> p.bgaTrendDying
    "STRESSED" -> p.bgaTrendStressed
    "DECAY" -> p.bgaTrendDecay
    "DORMANT" -> p.bgaTrendDormant
    "STABLE" -> p.bgaTrendStable
    "GROWING" -> p.bgaTrendGrowing
    else -> null
  }
}

internal fun separatorLabel(strings: Strings, state: String): String = separatorWord(strings, state) ?: neutral(state)

internal fun separatorWord(strings: Strings, state: String): String? {
  val p = strings.production
  return when (state) {
    "INACTIVE" -> p.separatorInactive
    "OPERATING" -> p.separatorOperating
    "FULL" -> p.separatorFull
    "NO_SILO" -> p.separatorNoSilo
    "STANDBY" -> p.separatorStandby
    else -> null
  }
}

/** An unknown token — a newer mod than this terminal — readable rather than blank. */
private fun neutral(token: String): String = token.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
