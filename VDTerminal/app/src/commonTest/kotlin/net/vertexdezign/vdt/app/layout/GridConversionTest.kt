package net.vertexdezign.vdt.app.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun at(id: String, col: Int, row: Int, colSpan: Int = 1, rowSpan: Int = 1) =
  GridPlacement(Tile(id, id), col, row, colSpan, rowSpan)

private fun row(vararg parts: Pair<Int, LayoutNode>) = Split(
  Axis.Row,
  parts.map {
    Child(it.first.toFloat(), it.second)
  },
)

private fun column(vararg parts: Pair<Int, LayoutNode>) =
  Split(Axis.Column, parts.map { Child(it.first.toFloat(), it.second) })

private fun tile(id: String) = Tile(id, id)

/** Empties get random ids; comparing structure means ignoring them. */
private fun LayoutNode.withBlankEmpties() = mapLeaves { if (it is Empty) Empty("e") else it }

private fun assertSameTree(expected: LayoutNode, actual: LayoutNode) =
  assertEquals(expected.normalize().withBlankEmpties(), actual.withBlankEmpties())

class GridConversionTest {
  @Test
  fun theOldVehicleSeedCutsIntoItsTwoBands() {
    // The 12×7 landscape Vehicle page as the grid seeded it.
    val cells =
      listOf(
        at("front", 0, 0, 2, 4),
        at("map", 2, 0, 8, 4),
        at("rear", 10, 0, 2, 4),
        at("self", 0, 4, 2, 3),
        at("engine", 2, 4, 4, 3),
        at("lighting", 6, 4, 2, 3),
        at("navigation", 8, 4, 2, 3),
        at("production", 10, 4),
        at("storage", 11, 4),
        at("animals", 10, 5),
        at("diagnostics", 11, 5),
      )
    // Cutting columns first (at 2 and 10) would give as many leaves, but three strips instead of two:
    // the bands win. And the dock keeps its free row as one empty below it, rather than a column of
    // empties down each side.
    val expected =
      column(
        4 to row(2 to tile("front"), 8 to tile("map"), 2 to tile("rear")),
        3 to
          row(
            2 to tile("self"),
            4 to tile("engine"),
            2 to tile("lighting"),
            2 to tile("navigation"),
            2 to
              column(
                1 to row(1 to tile("production"), 1 to tile("storage")),
                1 to row(1 to tile("animals"), 1 to tile("diagnostics")),
                1 to Empty("e"),
              ),
          ),
      )
    assertSameTree(expected, gridToTree(12, 7, cells))
  }

  @Test
  fun freeSpaceBecomesOneEmptyPerRegion() {
    assertSameTree(row(8 to tile("map"), 4 to Empty("e")), gridToTree(12, 7, listOf(at("map", 0, 0, 8, 7))))
    // Not one empty per cell: 84 of them on a blank page.
    assertEquals(Empty("e"), gridToTree(12, 7, emptyList()).withBlankEmpties())
  }

  @Test
  fun aTileFillingTheGridIsTheWholeTree() {
    assertEquals(tile("map"), gridToTree(12, 7, listOf(at("map", 0, 0, 12, 7))))
  }

  @Test
  fun aPinwheelIsCutAnywayAndSaysSo() {
    // Five tiles wound round a centre: no line crosses the grid without cutting a tile.
    val cells =
      listOf(
        at("a", 0, 0, colSpan = 2),
        at("b", 2, 0, rowSpan = 2),
        at("c", 1, 2, colSpan = 2),
        at("d", 0, 1, rowSpan = 2),
        at("e", 1, 1),
      )
    val lossy = mutableListOf<String>()
    val tree = gridToTree(3, 3, cells, onLossy = { lossy += it })
    assertEquals(setOf("a", "b", "c", "d", "e"), tree.tiles.map { it.instanceId }.toSet())
    assertEquals(1, lossy.size, "$lossy")
    // Every line crosses one tile; the first row line goes through "b", which is split evenly and so
    // stays on the top side.
    assertTrue("b" in lossy.single(), lossy.single())
  }

  @Test
  fun tilesOutsideTheGridAreClippedOrDropped() {
    val tree = gridToTree(3, 2, listOf(at("a", 0, 0, 5, 2), at("gone", 7, 7)))
    assertEquals(tile("a"), tree)
  }

  @Test
  fun configRidesAlong() {
    val placed = GridPlacement(Tile("m", "map", mapOf("layer" to "soil")), 0, 0, 12, 7)
    assertEquals(Tile("m", "map", mapOf("layer" to "soil")), gridToTree(12, 7, listOf(placed)))
  }
}
