package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.app.widgets.WidgetConfig
import kotlin.random.Random

/**
 * A page arrangement as a **split tree**: a region cut along one axis into children at ratios, each
 * child either cut again or holding one widget — the way a tiling window manager lays out windows.
 *
 * It replaces a cell grid because a grid makes symmetry something the user counts cells to get
 * right. Here it is structural: the two ends of one split are the same width because they carry the
 * same weight, and "make these three equal" is one action on one node. What a tree cannot express is
 * an interlocking arrangement no straight cut crosses edge to edge (four tiles pinwheeled round a
 * fifth) — accepted, since that has to be built on purpose and rarely looks balanced.
 *
 * Every mutation ends in [normalize], so a tree is always in canonical form; see there.
 */
@Serializable
sealed interface LayoutNode

/**
 * A region cut along [axis] into [children], each taking its [Child.weight] of the space left once
 * the gaps between them are taken out.
 *
 * More than two children on purpose: Lighting | Combine | Engine is one three-way split, so the tree
 * reads the way the page does and equalizing it is one action rather than a nested half-and-quarter.
 */
@Serializable
@SerialName("split")
data class Split(val axis: Axis, val children: List<Child>) : LayoutNode

@Serializable
data class Child(val weight: Float, val node: LayoutNode)

/** [Row] = children side by side, so its dividers are vertical lines; [Column] = stacked. */
@Serializable
enum class Axis {
  Row,
  Column,
  ;

  val cross: Axis get() = if (this == Row) Column else Row
}

/**
 * A placed widget: the widget *type* [widgetId] (resolved against `WidgetRegistry`), configured by
 * [config]. [instanceId] is the tile's identity, not [widgetId] — the same contract the grid's cells
 * had, minus the position, which the tree now owns.
 */
@Serializable
@SerialName("tile")
data class Tile(val instanceId: String, val widgetId: String, val config: WidgetConfig = emptyMap()) : LayoutNode

/**
 * Space deliberately left free — the add target in edit mode. A leaf of its own rather than an absent
 * child, so that removing a widget leaves the structure (and with it the symmetry) where it was.
 */
@Serializable
@SerialName("empty")
data class Empty(val id: String) : LayoutNode

/** Child indices from the root down to a node; the root is the empty path. */
typealias NodePath = List<Int>

/** A fresh [Empty] id; random for the same reason [newInstanceId] is — it must outlive its position. */
fun newEmptyId(): String = "e-" + Random.nextLong(0, Long.MAX_VALUE).toString(36)

/**
 * The canonical form every mutation ends in:
 *
 * - a split with one child is replaced by that child;
 * - a child split on the **same axis** as its parent is flattened into it, weights scaled — two nested
 *   row-splits draw exactly like one, and equalizing must see all the siblings the user sees;
 * - weights are renormalized to sum to 1 (a non-positive or non-finite weight, which only a damaged
 *   store can hold, counts as a sliver rather than dropping the tile it carries);
 * - a split with no children disappears, and an arrangement with nothing left is a single [Empty].
 */
fun LayoutNode.normalize(): LayoutNode = normalizeOrNull() ?: Empty(newEmptyId())

private const val SLIVER_WEIGHT = 0.001f

private fun LayoutNode.normalizeOrNull(): LayoutNode? = when (this) {
  is Tile, is Empty -> this

  is Split -> {
    val flat =
      buildList {
        for (child in children) {
          val node = child.node.normalizeOrNull() ?: continue
          val weight = child.weight.takeIf { it.isFinite() && it > 0f } ?: SLIVER_WEIGHT
          if (node is Split && node.axis == axis) {
            node.children.forEach { add(Child(weight * it.weight, it.node)) }
          } else {
            add(Child(weight, node))
          }
        }
      }
    when (flat.size) {
      0 -> null

      1 -> flat.single().node

      else -> {
        val sum = flat.sumOf { it.weight.toDouble() }.toFloat()
        Split(axis, flat.map { it.copy(weight = it.weight / sum) })
      }
    }
  }
}

fun LayoutNode.nodeAt(path: NodePath): LayoutNode? {
  var node: LayoutNode = this
  for (index in path) node = ((node as? Split)?.children?.getOrNull(index) ?: return null).node
  return node
}

/** [this] with the node at [path] replaced by [node]; unchanged if [path] leads nowhere. Not normalized. */
fun LayoutNode.replaceAt(path: NodePath, node: LayoutNode): LayoutNode {
  if (path.isEmpty()) return node
  val split = this as? Split ?: return this
  val index = path.first()
  val child = split.children.getOrNull(index) ?: return this
  val children = split.children.toMutableList()
  children[index] = child.copy(node = child.node.replaceAt(path.drop(1), node))
  return split.copy(children = children)
}

/** Every tile, in reading order (depth first, first child first). */
val LayoutNode.tiles: List<Tile>
  get() = when (this) {
    is Tile -> listOf(this)
    is Empty -> emptyList()
    is Split -> children.flatMap { it.node.tiles }
  }

fun LayoutNode.pathOf(instanceId: String): NodePath? = when (this) {
  is Tile -> if (this.instanceId == instanceId) emptyList() else null

  is Empty -> null

  is Split ->
    children.withIndex().firstNotNullOfOrNull { (index, child) ->
      child.node.pathOf(instanceId)?.let {
        listOf(index) +
          it
      }
    }
}

/** A leaf with where it lands — one entry of [layOut]. */
data class PlacedLeaf(val path: NodePath, val node: LayoutNode, val rect: Rect)

/**
 * A line between two neighbouring children of the split at [split]: children [index] and
 * [index] + 1. [position] is the line's centre along [axis] (an x for a [Axis.Row] split, a y for a
 * column), [rect] the gap it sits in — the divider's hit target and what edit mode draws.
 */
data class Divider(val split: NodePath, val index: Int, val axis: Axis, val position: Float, val rect: Rect)

/** Where every leaf lands inside [bounds], with [gap] between siblings. */
fun LayoutNode.measure(bounds: Rect, gap: Float): Map<NodePath, Rect> = layOut(bounds, gap).associate {
  it.path to
    it.rect
}

/**
 * Every leaf and its rect, in reading order. Pure geometry, so the renderer, divider hit targets,
 * snapping and the floor checks all work from the same numbers — and all of it is unit-testable
 * without a Compose UI test.
 */
fun LayoutNode.layOut(bounds: Rect, gap: Float): List<PlacedLeaf> = buildList {
  walk(emptyList(), bounds, gap) { path, node, rect -> if (node !is Split) add(PlacedLeaf(path, node, rect)) }
}

/** Every divider on the page, outermost split first. */
fun LayoutNode.dividers(bounds: Rect, gap: Float): List<Divider> = buildList {
  walk(emptyList(), bounds, gap) { path, node, rect ->
    if (node !is Split) return@walk
    val rects = node.childRects(rect, gap)
    for (i in 0 until rects.lastIndex) {
      val gapRect =
        when (node.axis) {
          Axis.Row -> Rect(rects[i].right, rect.top, rects[i + 1].left, rect.bottom)
          Axis.Column -> Rect(rect.left, rects[i].bottom, rect.right, rects[i + 1].top)
        }
      val position = if (node.axis == Axis.Row) gapRect.center.x else gapRect.center.y
      add(Divider(path, i, node.axis, position, gapRect))
    }
  }
}

/** The rect of the node at [path], leaf or split; null if [path] leads nowhere. */
fun LayoutNode.rectOf(path: NodePath, bounds: Rect, gap: Float): Rect? {
  var node: LayoutNode = this
  var rect = bounds
  for (index in path) {
    val split = node as? Split ?: return null
    node = split.children.getOrNull(index)?.node ?: return null
    rect = split.childRects(rect, gap)[index]
  }
  return rect
}

private fun LayoutNode.walk(path: NodePath, rect: Rect, gap: Float, visit: (NodePath, LayoutNode, Rect) -> Unit) {
  visit(path, this, rect)
  if (this !is Split) return
  childRects(rect, gap).forEachIndexed { i, childRect -> children[i].node.walk(path + i, childRect, gap, visit) }
}

/**
 * The split's own arithmetic: take `gap × (n − 1)` off the extent along [Split.axis] and hand out the
 * rest by weight. Everything else about geometry goes through here.
 */
internal fun Split.childRects(rect: Rect, gap: Float): List<Rect> {
  val start = rect.startOn(axis)
  val content = (rect.extentOn(axis) - gap * (children.size - 1)).coerceAtLeast(0f)
  var cursor = start
  return children.map { child ->
    val length = content * child.weight
    val childRect =
      when (axis) {
        Axis.Row -> Rect(cursor, rect.top, cursor + length, rect.bottom)
        Axis.Column -> Rect(rect.left, cursor, rect.right, cursor + length)
      }
    cursor += length + gap
    childRect
  }
}

internal fun Rect.startOn(axis: Axis): Float = if (axis == Axis.Row) left else top

internal fun Rect.endOn(axis: Axis): Float = if (axis == Axis.Row) right else bottom

internal fun Rect.extentOn(axis: Axis): Float = if (axis == Axis.Row) width else height

internal fun Size.on(axis: Axis): Float = if (axis == Axis.Row) width else height
