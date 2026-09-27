package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A tile whose instance id is its widget id — one of each per page, which keeps assertions readable. */
private fun tile(id: String) = Tile(id, id)

private fun row(vararg children: Pair<Float, LayoutNode>) = Split(Axis.Row, children.map { Child(it.first, it.second) })

private fun column(vararg children: Pair<Float, LayoutNode>) =
  Split(Axis.Column, children.map { Child(it.first, it.second) })

private const val GAP = 8f

/** The body an 11" iPad gives a page held landscape — the size the 12×7 grid was tuned to. */
private val IPAD = Rect(0f, 0f, 1194f, 696f)

private fun assertNear(expected: Float, actual: Float, message: String? = null) =
  assertTrue(abs(expected - actual) < 0.01f, "${message ?: ""} expected <$expected>, actual <$actual>")

/** Floors: 56dp square for anything unnamed (a portrait cell), bigger for the map. */
private fun frame(bounds: Rect = IPAD, floors: Map<String, Size> = emptyMap()) =
  LayoutFrame(bounds, GAP) { floors[it.widgetId] ?: Size(56f, 56f) }

/**
 * The user's Vehicle page from the plan: front column + map + rear column over a three-tile band,
 * each column a rig slot over two shortcuts.
 */
private val vehicle =
  column(
    5f / 8 to
      row(
        1f / 6 to column(2f / 3 to tile("front"), 1f / 3 to row(0.5f to tile("animals"), 0.5f to tile("diagnostics"))),
        2f / 3 to tile("map"),
        1f / 6 to column(2f / 3 to tile("rear"), 1f / 3 to row(0.5f to tile("production"), 0.5f to tile("storage"))),
      ),
    3f / 8 to row(1f / 3 to tile("lighting"), 1f / 3 to tile("combine"), 1f / 3 to tile("engine")),
  )

class SplitLayoutTest {
  @Test
  fun aSplitWithOneChildIsThatChild() {
    assertEquals(tile("a"), row(1f to tile("a")).normalize())
  }

  @Test
  fun aNestedSplitOnTheSameAxisIsFlattenedWithScaledWeights() {
    val nested = row(0.5f to tile("a"), 0.5f to row(0.5f to tile("b"), 0.5f to tile("c")))
    val flat = assertIs<Split>(nested.normalize())
    assertEquals(listOf("a", "b", "c"), flat.tiles.map { it.instanceId })
    assertEquals(listOf(0.5f, 0.25f, 0.25f), flat.children.map { it.weight })
  }

  @Test
  fun aNestedSplitAcrossTheAxisStays() {
    val nested = row(0.5f to tile("a"), 0.5f to column(0.5f to tile("b"), 0.5f to tile("c")))
    assertEquals(nested, nested.normalize())
  }

  @Test
  fun weightsAreRenormalizedAndABrokenOneKeepsItsTile() {
    val split = assertIs<Split>(row(2f to tile("a"), 2f to tile("b"), -1f to tile("c")).normalize())
    assertEquals(3, split.children.size)
    assertNear(1f, split.children.sumOf { it.weight.toDouble() }.toFloat())
    assertNear(split.children[0].weight, split.children[1].weight)
  }

  @Test
  fun aSplitWithNothingLeftIsAnEmptyPage() {
    assertIs<Empty>(row().normalize())
    // ...and the collapse runs bottom-up: a split whose only child was an empty split is gone too.
    assertEquals(tile("a"), column(0.5f to tile("a"), 0.5f to row()).normalize())
  }

  @Test
  fun theWorkedExampleCutsTheWayThePlanSays() {
    val rects = vehicle.measure(IPAD, GAP)
    val byId = vehicle.layOut(IPAD, GAP).associate { (it.node as Tile).instanceId to it.rect }
    assertEquals(10, rects.size)

    // Top band 5/8, bottom 3/8 of what's left after one gap.
    assertNear((696f - GAP) * 5 / 8, byId.getValue("map").height)
    assertNear((696f - GAP) * 3 / 8, byId.getValue("engine").height)
    // Front and rear the same width because they are the two 1/6 ends of one split...
    val slotWidth = (1194f - 2 * GAP) / 6
    assertNear(slotWidth, byId.getValue("front").width)
    assertNear(slotWidth, byId.getValue("rear").width)
    assertNear(4 * slotWidth, byId.getValue("map").width)
    // ...and each shortcut pair is an exact half of its column.
    assertNear((slotWidth - GAP) / 2, byId.getValue("animals").width)
    assertNear(byId.getValue("animals").width, byId.getValue("storage").width)
    // The bottom band's thirds don't line up with the top band's sixths, and don't have to.
    assertNear((1194f - 2 * GAP) / 3, byId.getValue("combine").width)
    // Everything inside the bounds, flush with the far edges.
    assertNear(1194f, byId.getValue("storage").right)
    assertNear(696f, byId.getValue("engine").bottom)
  }

  @Test
  fun dividersSitInTheGapsBetweenSiblings() {
    val dividers = vehicle.dividers(IPAD, GAP)
    // 1 band divider + 2 in the top row + 2 in the bottom row + 1 + 1 in each column + 1 in each pair.
    assertEquals(9, dividers.size)
    val band = dividers.first()
    assertEquals(emptyList(), band.split)
    assertEquals(Axis.Column, band.axis)
    assertNear((696f - GAP) * 5 / 8 + GAP / 2, band.position)
    assertNear(GAP, band.rect.height)
    assertNear(1194f, band.rect.width)
  }

  @Test
  fun pathsAddressNodesAndReplaceRebuildsOnlyTheirSpine() {
    val path = vehicle.pathOf("storage")
    assertEquals(listOf(0, 2, 1, 1), path)
    assertEquals(tile("storage"), vehicle.nodeAt(path!!))
    val replaced = vehicle.replaceAt(path, Empty("e"))
    assertEquals(Empty("e"), replaced.nodeAt(path))
    // The bottom band is untouched — the same instance, not a copy.
    assertSame((vehicle as Split).children[1], (replaced as Split).children[1])
  }

  @Test
  fun aTreeRoundTripsThroughJson() {
    val json = Json { ignoreUnknownKeys = true }
    val tree: LayoutNode =
      row(0.25f to Tile("m", "map", mapOf("zoom" to "2")), 0.75f to column(0.5f to Empty("e1"), 0.5f to tile("x")))
    val encoded = json.encodeToString(LayoutNode.serializer(), tree)
    assertTrue("\"type\":\"split\"" in encoded, encoded)
    assertEquals(tree, json.decodeFromString(LayoutNode.serializer(), encoded))
  }
}

class SplitEditsTest {
  @Test
  fun swapTradesTwoLeavesInPlace() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    assertEquals(row(0.5f to tile("b"), 0.5f to tile("a")), tree.swap(listOf(0), listOf(1), frame()))
  }

  @Test
  fun swapOntoAnEmptyIsAMove() {
    val tree = row(0.5f to tile("a"), 0.5f to Empty("e"))
    assertEquals(row(0.5f to Empty("e"), 0.5f to tile("a")), tree.swap(listOf(0), listOf(1), frame()))
  }

  @Test
  fun swapIsRefusedWhenATileWouldLandBelowItsFloor() {
    val tree = row(0.8f to tile("map"), 0.2f to tile("a"))
    val floors = mapOf("map" to Size(400f, 300f))
    assertSame(tree, tree.swap(listOf(0), listOf(1), frame(floors = floors)))
  }

  @Test
  fun splitAlongTheParentsAxisInsertsASibling() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    val split = assertIs<Split>(tree.split(listOf(0), Axis.Row, frame(), emptyId = "e"))
    assertEquals(listOf(tile("a"), Empty("e"), tile("b")), split.children.map { it.node })
    // "a" and its new empty share its old rect exactly; "b" doesn't pay for the extra gap.
    val before = tree.measure(IPAD, GAP)
    val after = split.measure(IPAD, GAP)
    assertNear(before.getValue(listOf(1)).width, after.getValue(listOf(2)).width)
    assertNear(after.getValue(listOf(0)).width, after.getValue(listOf(1)).width)
    assertNear(before.getValue(listOf(0)).right, after.getValue(listOf(1)).right)
  }

  @Test
  fun splitAcrossTheParentsAxisNests() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    val split = tree.split(listOf(1), Axis.Column, frame(), emptyId = "e")
    assertEquals(row(0.5f to tile("a"), 0.5f to column(0.5f to tile("b"), 0.5f to Empty("e"))), split)
  }

  @Test
  fun splittingTheRootLeafWrapsIt() {
    assertEquals(row(0.5f to tile("a"), 0.5f to Empty("e")), tile("a").split(emptyList(), Axis.Row, frame(), "e"))
  }

  @Test
  fun splitIsRefusedWhenAHalfWouldBeTooSmall() {
    // 120dp wide: halves of 56 clear the tile's floor but not the empty's 88.
    val tree = row(0.1f to tile("a"), 0.9f to tile("b"))
    val narrow = frame(bounds = Rect(0f, 0f, 1208f, 400f))
    assertSame(tree, tree.split(listOf(0), Axis.Row, narrow))
    // Stacking it instead is fine: 400dp tall halves easily.
    assertNotEquals<LayoutNode>(tree, tree.split(listOf(0), Axis.Column, narrow))
  }

  @Test
  fun removeLeavesAnEmptyAndTheStructureStanding() {
    val removed = vehicle.removeToEmpty(vehicle.pathOf("combine")!!, emptyId = "e")
    assertEquals(vehicle.measure(IPAD, GAP), removed.measure(IPAD, GAP))
    assertEquals(Empty("e"), removed.nodeAt(listOf(1, 1)))
    // Only tiles can be removed.
    assertSame(removed, removed.removeToEmpty(listOf(1, 1)))
  }

  @Test
  fun closingAnEmptyGivesItsWeightToItsSiblingsInProportion() {
    val tree = row(0.2f to tile("a"), 0.2f to Empty("e"), 0.6f to tile("b"))
    val closed = assertIs<Split>(tree.closeEmpty(listOf(1)))
    assertEquals(listOf("a", "b"), closed.tiles.map { it.instanceId })
    assertNear(0.25f, closed.children[0].weight)
    assertNear(0.75f, closed.children[1].weight)
  }

  @Test
  fun closingTheLastSiblingMergesTheRegion() {
    val tree = row(0.5f to tile("a"), 0.5f to column(0.5f to tile("b"), 0.5f to Empty("e")))
    // The column collapses into "b", which then sits in the row directly.
    assertEquals(row(0.5f to tile("a"), 0.5f to tile("b")), tree.closeEmpty(listOf(1, 1)))
  }

  @Test
  fun anEmptyPageCantCloseItsLastLeafAndATileIsntClosable() {
    val empty = Empty("e")
    assertSame(empty, empty.closeEmpty(emptyList()))
    val tree = row(0.5f to tile("a"), 0.5f to Empty("e"))
    assertSame(tree, tree.closeEmpty(listOf(0)))
  }

  @Test
  fun equalizeGivesEveryChildTheSameWeight() {
    val tree = row(0.5f to tile("a"), 0.3f to tile("b"), 0.2f to tile("c"))
    val equal = assertIs<Split>(tree.equalize(emptyList(), frame()))
    equal.children.forEach { assertNear(1f / 3, it.weight) }
  }

  @Test
  fun equalizeIsRefusedWhenItWouldSqueezeATile() {
    val tree = row(0.8f to tile("map"), 0.2f to tile("a"))
    assertSame(tree, tree.equalize(emptyList(), frame(floors = mapOf("map" to Size(800f, 56f)))))
  }

  @Test
  fun anEdgeBandWrapsARootOnTheOtherAxis() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    val banded = tree.addAtEdge(PageEdge.Bottom, frame(), emptyId = "e")
    assertEquals(column(0.75f to tree, 0.25f to Empty("e")), banded)
  }

  @Test
  fun anEdgeBandJoinsARootOnTheSameAxis() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    val banded = assertIs<Split>(tree.addAtEdge(PageEdge.Left, frame(), emptyId = "e"))
    assertEquals(listOf(0.25f, 0.375f, 0.375f), banded.children.map { it.weight })
    assertEquals(Empty("e"), banded.children.first().node)
  }

  @Test
  fun placeFillsAnEmptyThatFitsAndNothingElse() {
    val tree = row(0.8f to tile("a"), 0.2f to Empty("e"))
    val map = Tile("m", "map")
    assertEquals(row(0.8f to tile("a"), 0.2f to map), tree.place(listOf(1), map, frame()))
    // Too big for the space.
    assertSame(tree, tree.place(listOf(1), map, frame(floors = mapOf("map" to Size(400f, 300f)))))
    // Not an empty; an instance that is already placed.
    assertSame(tree, tree.place(listOf(0), map, frame()))
    assertSame(tree, tree.place(listOf(1), tile("a"), frame()))
  }

  @Test
  fun reconfigureChangesOnlyTheSettings() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    val next = tree.reconfigure("b", mapOf("k" to "v"))
    assertEquals(mapOf("k" to "v"), (next.nodeAt(listOf(1)) as Tile).config)
    assertSame(tree, tree.reconfigure("gone", mapOf("k" to "v")))
  }

  @Test
  fun aPageAlreadyBelowItsFloorsCanStillBeEdited() {
    // Arranged on a bigger screen: "a" is under its 400dp floor here and renders anyway.
    val tree = row(0.25f to tile("a"), 0.75f to tile("b"))
    val f = frame(bounds = Rect(0f, 0f, 1000f, 600f), floors = mapOf("a" to Size(400f, 56f)))
    // Splitting its neighbour leaves "a" where it was, so it's allowed...
    assertNotEquals<LayoutNode>(tree, tree.split(listOf(1), Axis.Row, f))
    // ...growing "a" is allowed, shrinking it further is not.
    val divider = tree.dividers(f.bounds, GAP).single()
    assertNotEquals<LayoutNode>(tree, tree.setDivider(emptyList(), 0, divider.position + 100, f))
    assertSame(tree, tree.setDivider(emptyList(), 0, divider.position - 100, f))
  }

  @Test
  fun aDividerMovesWeightBetweenItsNeighboursOnly() {
    val tree = row(1f / 3 to tile("a"), 1f / 3 to tile("b"), 1f / 3 to tile("c"))
    val f = frame(bounds = Rect(0f, 0f, 616f, 400f)) // 600 of content: 200 each
    val moved = assertIs<Split>(tree.setDivider(emptyList(), 0, 304f, f)) // a: 300, b: 100
    assertNear(0.5f, moved.children[0].weight)
    assertNear(1f / 6, moved.children[1].weight)
    assertNear(1f / 3, moved.children[2].weight)
  }

  @Test
  fun aDividerIsClampedAtTheSubtreeFloorWithItsWeightsHeld() {
    // The right child holds a pair at 1/4 : 3/4, so for its narrow side to keep 56dp the pair needs
    // 56 × 4 + 8 = 232dp, not 56 + 56 + 8.
    val pair = row(0.25f to tile("b"), 0.75f to tile("c"))
    val tree = row(0.5f to tile("a"), 0.5f to column(0.5f to pair, 0.5f to tile("d")))
    val f = frame(bounds = Rect(0f, 0f, 1000f, 400f))
    val r = tree.dividerRange(emptyList(), 0, f)!!
    assertNear(1000f - 232f - GAP / 2, r.endInclusive)
    assertNear(56f + GAP / 2, r.start)
  }

  @Test
  fun aDividerSnapsToAnotherBandsDividerFirst() {
    // The top band's first column edge is deliberately off the twelfths.
    val tree =
      column(
        0.5f to row(0.15f to tile("front"), 0.85f to tile("map")),
        0.5f to row(1f / 3 to tile("lighting"), 1f / 3 to tile("combine"), 1f / 3 to tile("engine")),
      )
    val f = frame()
    val content = 1194f - 2 * GAP
    val topEdge = (1194f - GAP) * 0.15f + GAP / 2
    // Dragged to 185: the top band's edge (≈4 away) beats the 2/12 line (≈15 away).
    assertNear(topEdge, tree.snapDivider(listOf(1), 0, 185f, f))
    // Nowhere near anything: the nearest twelfth.
    assertNear(content / 12 + GAP / 2, tree.snapDivider(listOf(1), 0, 145f, f))
    // Near the midpoint of lighting + combine: the equal-neighbours position.
    val equal = (content * 2 / 3 + GAP) / 2
    assertTrue(tree.snapCandidates(listOf(1), 0, f).any { abs(it - equal) < 0.01f })
  }

  @Test
  fun aDividerStopsWhereItsNeighbourJustFits() {
    // A phone standing up: the twelfths are ~63dp apart, and a tile needing 80dp sits between two.
    val tree = column(1f / 12 to tile("service"), 11f / 12 to tile("readout"))
    val f = frame(bounds = Rect(0f, 0f, 377f, 769f), floors = mapOf("service" to Size(120f, 80f)))
    val justFits = 80f + GAP / 2
    assertTrue(tree.snapCandidates(emptyList(), 0, f).any { abs(it - justFits) < 0.01f })
    // Dragged towards the too-small twelfth, it stops where the tile fits rather than jumping on to
    // the next twelfth up.
    assertNear(justFits, tree.snapDivider(emptyList(), 0, 70f, f))
  }

  @Test
  fun snapCandidatesStayInsideTheFloors() {
    val tree = row(0.5f to tile("a"), 0.5f to tile("b"))
    val f = frame(bounds = Rect(0f, 0f, 1208f, 400f), floors = mapOf("a" to Size(500f, 56f)))
    val range = tree.dividerRange(emptyList(), 0, f)!!
    assertTrue(tree.snapCandidates(emptyList(), 0, f).all { it in range })
    assertTrue(tree.snapDivider(emptyList(), 0, 0f, f) >= 500f)
  }
}
