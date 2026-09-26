package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import net.vertexdezign.vdt.app.widgets.WidgetConfig
import kotlin.math.abs

// The edit operations on a [LayoutNode] tree, all pure: each returns a new, [normalize]d tree, or the
// input itself when the edit isn't allowed — the same "no-op means not allowed" contract the grid had,
// so edit mode can grey a control out by comparing the result with what it passed in.
//
// "Allowed" is mostly about size floors, which gate edits and never rendering (see [accept]). Floors
// are measured, so the operations that can break one take the [LayoutFrame] the arrangement is being
// edited in.

/** Space an [Empty] needs to show its add and close buttons, so a split can't leave an untappable sliver. */
val EMPTY_FLOOR = Size(88f, 48f)

/** How close a dragged divider must come to a candidate to snap onto it rather than to a twelfth. */
const val SNAP_THRESHOLD = 24f

/** What a band added at a page edge takes of the page, the rest scaled down to make room. */
const val EDGE_SHARE = 0.25f

/** Rounding slack for floor comparisons; far below anything visible. */
private const val EPS = 0.5f

/**
 * What an edit is measured against: the arrangement's [bounds], the [gap] between siblings, and each
 * leaf's floor. All lengths are dp, as plain floats, so none of this needs a `Density`.
 */
class LayoutFrame(
  val bounds: Rect,
  val gap: Float,
  val emptyFloor: Size = EMPTY_FLOOR,
  val tileFloor: (Tile) -> Size,
) {
  /**
   * The smallest size [node] can take and have every leaf in it clear its floor, **with the weights
   * inside it held where they are** — which is what a divider drag does to the subtrees either side
   * of it. So along a split's axis it is not simply the sum of the children's floors: a child with a
   * third of the weight needs the split to be three times its floor (plus gaps). Across the axis it is
   * the largest child's.
   */
  fun minSize(node: LayoutNode): Size = when (node) {
    is Tile -> tileFloor(node)

    is Empty -> emptyFloor

    is Split -> {
      val floors = node.children.map { minSize(it.node) }
      val along =
        node.children.indices.maxOf { floors[it].on(node.axis) / node.children[it].weight } +
          gap * (node.children.size - 1)
      val across = floors.maxOf { it.on(node.axis.cross) }
      if (node.axis == Axis.Row) Size(along, across) else Size(across, along)
    }
  }

  /**
   * [after] if it keeps every floor, else [before].
   *
   * A leaf passes if it clears its floor, or — for one that was already on the page — if it is no
   * smaller than it was in the dimension where it falls short. That second clause is what lets a page
   * arranged on a bigger screen still be edited on a smaller one: its undersized tiles render anyway
   * (floors gate edits, not rendering), and an edit is refused only for making one of them *worse*
   * or for putting something new below its floor.
   */
  internal fun accept(before: LayoutNode, after: LayoutNode): LayoutNode {
    if (after == before) return before
    val was = before.layOut(bounds, gap).associate { it.node.leafKey to it.rect }
    val holds =
      after.layOut(bounds, gap).all { leaf ->
        val floor = minSize(leaf.node)
        val old = was[leaf.node.leafKey]
        holds(leaf.rect.width, floor.width, old?.width) && holds(leaf.rect.height, floor.height, old?.height)
      }
    return if (holds) after else before
  }

  private fun holds(now: Float, floor: Float, before: Float?): Boolean =
    now + EPS >= floor || (before != null && now + EPS >= before)
}

/** Identity of a leaf across edits: tiles by instance, empties by their own id. */
private val LayoutNode.leafKey: String
  get() = when (this) {
    is Tile -> "tile:$instanceId"
    is Empty -> "empty:$id"
    is Split -> error("a split is not a leaf")
  }

/**
 * Trades the contents of two leaves. Onto an [Empty] that is a move. There is no "does it fit at the
 * new origin" failure the grid had — both leaves keep their rects — but a tile is still refused a
 * leaf smaller than its floor.
 */
fun LayoutNode.swap(a: NodePath, b: NodePath, frame: LayoutFrame): LayoutNode {
  if (a == b) return this
  val nodeA = nodeAt(a)?.takeIf { it !is Split } ?: return this
  val nodeB = nodeAt(b)?.takeIf { it !is Split } ?: return this
  return frame.accept(this, replaceAt(a, nodeB).replaceAt(b, nodeA))
}

/**
 * Halves the leaf at [path] along [axis] and puts an [Empty] after it (right of it for [Axis.Row],
 * below for [Axis.Column]). Along the parent's own axis that inserts a sibling — the leaf's weight
 * split between the two — rather than nesting a split the next [normalize] would flatten anyway.
 * Refused unless both halves clear their floors.
 */
fun LayoutNode.split(path: NodePath, axis: Axis, frame: LayoutFrame, emptyId: String = newEmptyId()): LayoutNode {
  val leaf = nodeAt(path)?.takeIf { it !is Split } ?: return this
  val parentPath = path.dropLast(1)
  val parent = if (path.isEmpty()) null else nodeAt(parentPath) as? Split
  val next =
    if (parent != null && parent.axis == axis) {
      // In weights, not halves of the weight: the new sibling brings a gap with it, and taking that
      // out of everyone would shrink every neighbour a little (enough to refuse the edit for a
      // neighbour already at its floor). So the others keep their exact extent and the leaf pays.
      val index = path.last()
      val rect = rectOf(parentPath, frame.bounds, frame.gap) ?: return this
      val content = rect.extentOn(axis) - frame.gap * parent.children.lastIndex
      val next = content - frame.gap
      val half = (parent.children[index].weight * content - frame.gap) / 2 / next
      if (next <= 0f || half <= 0f) return this
      val children = parent.children.map { it.copy(weight = it.weight * content / next) }.toMutableList()
      children[index] = Child(half, leaf)
      children.add(index + 1, Child(half, Empty(emptyId)))
      replaceAt(parentPath, parent.copy(children = children))
    } else {
      replaceAt(path, Split(axis, listOf(Child(0.5f, leaf), Child(0.5f, Empty(emptyId)))))
    }
  return frame.accept(this, next.normalize())
}

/**
 * Turns the tile at [path] into an [Empty] in place. The structure — and so the symmetry — stays, so
 * swapping one widget for another is remove + add without the neighbours shifting in between. No
 * floor to check: nothing moves.
 */
fun LayoutNode.removeToEmpty(path: NodePath, emptyId: String = newEmptyId()): LayoutNode {
  if (nodeAt(path) !is Tile) return this
  return replaceAt(path, Empty(emptyId))
}

/**
 * Deletes the [Empty] at [path]; its siblings share out its weight in proportion, and a split left
 * with one child collapses into it. This is how a region is *merged*. Everything around it only grows,
 * so no floor can break; the last leaf of a page can't be closed (an empty page is a single [Empty]).
 */
fun LayoutNode.closeEmpty(path: NodePath): LayoutNode {
  if (path.isEmpty() || nodeAt(path) !is Empty) return this
  val parentPath = path.dropLast(1)
  val parent = nodeAt(parentPath) as? Split ?: return this
  return replaceAt(parentPath, parent.copy(children = parent.children.filterIndexed { i, _ -> i != path.last() }))
    .normalize()
}

/** Gives every child of the split at [path] the same weight. Refused if that would squeeze one below its floor. */
fun LayoutNode.equalize(path: NodePath, frame: LayoutFrame): LayoutNode {
  val split = nodeAt(path) as? Split ?: return this
  val weight = 1f / split.children.size
  return frame.accept(this, replaceAt(path, split.copy(children = split.children.map { it.copy(weight = weight) })))
}

/** A side of the page, and the axis a band added there runs across it. */
enum class PageEdge(val axis: Axis, val leading: Boolean) {
  Top(Axis.Column, leading = true),
  Bottom(Axis.Column, leading = false),
  Left(Axis.Row, leading = true),
  Right(Axis.Row, leading = false),
}

/**
 * Adds an [Empty] band spanning the whole page along [edge], taking [share] of it and scaling what was
 * there down to make room — appended to the root when the root already runs on that axis, else
 * wrapping the root in a new split. The only way to get a full-width band after the fact.
 */
fun LayoutNode.addAtEdge(
  edge: PageEdge,
  frame: LayoutFrame,
  emptyId: String = newEmptyId(),
  share: Float = EDGE_SHARE,
): LayoutNode {
  val band = Child(share, Empty(emptyId))
  val rest =
    if (this is Split && axis == edge.axis) {
      children.map { it.copy(weight = it.weight * (1 - share)) }
    } else {
      listOf(Child(1 - share, this))
    }
  val children = if (edge.leading) listOf(band) + rest else rest + band
  return frame.accept(this, Split(edge.axis, children).normalize())
}

/** Puts [tile] into the [Empty] at [path], taking the whole of it. Refused if it doesn't clear the tile's floor. */
fun LayoutNode.place(path: NodePath, tile: Tile, frame: LayoutFrame): LayoutNode {
  if (nodeAt(path) !is Empty || pathOf(tile.instanceId) != null) return this
  return frame.accept(this, replaceAt(path, tile))
}

/** Replaces the settings of the tile [instanceId]; no-op if it's gone. Never moves anything. */
fun LayoutNode.reconfigure(instanceId: String, config: WidgetConfig): LayoutNode {
  val path = pathOf(instanceId) ?: return this
  val tile = nodeAt(path) as Tile
  return replaceAt(path, tile.copy(config = config))
}

/**
 * Where divider [index] of the split at [split] may go: far enough from each neighbour's far edge that
 * neither subtree drops below [LayoutFrame.minSize]. Always contains the divider's current position,
 * so a pair that is already undersized can be moved towards legal but not further from it. Null if
 * there is no such divider.
 */
fun LayoutNode.dividerRange(split: NodePath, index: Int, frame: LayoutFrame): ClosedFloatingPointRange<Float>? {
  val (node, rects) = splitRects(split, frame) ?: return null
  val (lo, hi) = floorStops(split, index, frame) ?: return null
  val current = rects[index].endOn(node.axis) + frame.gap / 2
  return minOf(lo, current)..maxOf(hi, current)
}

/**
 * Where divider [index] of the split at [split] leaves the child before it, and the child after it,
 * exactly at its floor. Unclamped — unlike [dividerRange]'s ends, which an already-undersized pair
 * stretches out to where the divider is now.
 */
private fun LayoutNode.floorStops(split: NodePath, index: Int, frame: LayoutFrame): Pair<Float, Float>? {
  val (node, rects) = splitRects(split, frame) ?: return null
  if (index !in 0 until node.children.lastIndex) return null
  val axis = node.axis
  val half = frame.gap / 2
  val lo = rects[index].startOn(axis) + frame.minSize(node.children[index].node).on(axis) + half
  val hi = rects[index + 1].endOn(axis) - frame.minSize(node.children[index + 1].node).on(axis) - half
  return lo to hi
}

/**
 * Moves divider [index] of the split at [split] so its centre sits at [position], clamped to
 * [dividerRange]: weight moves between the two children either side of it and nothing else changes.
 * No snapping here — pass the position through [snapDivider] first.
 */
fun LayoutNode.setDivider(split: NodePath, index: Int, position: Float, frame: LayoutFrame): LayoutNode {
  val (node, rects) = splitRects(split, frame) ?: return this
  val range = dividerRange(split, index, frame) ?: return this
  val axis = node.axis
  val p = position.coerceIn(range)
  if (p == rects[index].endOn(axis) + frame.gap / 2) return this
  val sizeA = p - frame.gap / 2 - rects[index].startOn(axis)
  val sizeB = rects[index + 1].endOn(axis) - (p + frame.gap / 2)
  if (sizeA + sizeB <= 0f) return this
  // Split the pair's existing weight rather than recomputing both from the extent, so the other
  // children's weights don't pick up rounding.
  val pair = node.children[index].weight + node.children[index + 1].weight
  val weightA = pair * sizeA / (sizeA + sizeB)
  val children = node.children.toMutableList()
  children[index] = children[index].copy(weight = weightA)
  children[index + 1] = children[index + 1].copy(weight = pair - weightA)
  return replaceAt(split, node.copy(children = children))
}

/**
 * The positions divider [index] of the split at [split] snaps to, within [snapRange]:
 *
 * 1. twelfths of the split — in weight, so a page converted from the 12-column grid lands on them
 *    exactly and a three-way split's equal thirds are among them;
 * 2. the position that makes its two neighbours equal;
 * 3. every other divider on the page running the same way — the cross-band alignment the tree alone
 *    doesn't give (the bottom band's divider lining up with a column edge of the top band);
 * 4. the positions where a neighbour is exactly at its floor, i.e. as small as it can be and still be
 *    read. A widget whose natural size falls between two twelfths (the pillar's service tile: 63dp
 *    cut its second row off, 127dp was mostly empty) gets a stop where it fits, and the stop follows
 *    whichever widget is there.
 */
fun LayoutNode.snapCandidates(split: NodePath, index: Int, frame: LayoutFrame): List<Float> {
  val range = snapRange(split, index, frame) ?: return emptyList()
  val (node, rects) = splitRects(split, frame) ?: return emptyList()
  val equal = (rects[index].startOn(node.axis) + rects[index + 1].endOn(node.axis)) / 2
  val others =
    dividers(frame.bounds, frame.gap)
      .filter { it.axis == node.axis && !(it.split == split && it.index == index) }
      .map { it.position }
  val stops = floorStops(split, index, frame)?.toList().orEmpty()
  return (twelfths(split, index, frame) + equal + others + stops).filter { it in range }.distinct()
}

/**
 * Where divider [index] of the split at [split] comes to rest when dragged to [raw]. It always snaps —
 * there are no free positions, which is what keeps pages tidy without effort: the nearest
 * [snapCandidates] entry within [threshold] wins, else the nearest twelfth or floor stop. The floor
 * stops are what keep a drag from sticking: without them a divider pushed towards a neighbour's floor
 * would jump to the last twelfth that clears it, however far away that is.
 */
fun LayoutNode.snapDivider(
  split: NodePath,
  index: Int,
  raw: Float,
  frame: LayoutFrame,
  threshold: Float = SNAP_THRESHOLD,
): Float {
  val range = snapRange(split, index, frame) ?: return raw
  val near = snapCandidates(split, index, frame).filter { abs(it - raw) <= threshold }.minByOrNull { abs(it - raw) }
  if (near != null) return near
  val fallback = twelfths(split, index, frame) + listOf(range.start, range.endInclusive)
  return fallback.filter { it in range }.minBy { abs(it - raw) }
}

/**
 * Where a snap may land: between the two floor stops, so every candidate leaves both neighbours
 * readable — including the position the divider is at now, when that is what leaves one of them
 * short (a page arranged on a bigger screen). Only when the pair can't both fit anywhere does it fall
 * back to [dividerRange], which at least never makes either worse.
 */
private fun LayoutNode.snapRange(split: NodePath, index: Int, frame: LayoutFrame): ClosedFloatingPointRange<Float>? {
  val (lo, hi) = floorStops(split, index, frame) ?: return null
  return if (lo <= hi) lo..hi else dividerRange(split, index, frame)
}

private fun LayoutNode.twelfths(split: NodePath, index: Int, frame: LayoutFrame): List<Float> {
  val node = nodeAt(split) as? Split ?: return emptyList()
  val rect = rectOf(split, frame.bounds, frame.gap) ?: return emptyList()
  val content = rect.extentOn(node.axis) - frame.gap * node.children.lastIndex
  val offset = rect.startOn(node.axis) + index * frame.gap + frame.gap / 2
  return (1..11).map { k -> offset + content * k / 12 }
}

private fun LayoutNode.splitRects(path: NodePath, frame: LayoutFrame): Pair<Split, List<Rect>>? {
  val node = nodeAt(path) as? Split ?: return null
  val rect = rectOf(path, frame.bounds, frame.gap) ?: return null
  return node to node.childRects(rect, frame.gap)
}
