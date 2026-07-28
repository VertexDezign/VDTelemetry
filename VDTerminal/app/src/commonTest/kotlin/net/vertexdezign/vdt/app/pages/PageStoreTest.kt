package net.vertexdezign.vdt.app.pages

import com.russhwolf.settings.MapSettings
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.layout.GridLayout
import net.vertexdezign.vdt.app.widgets.ShortcutWidget
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [PageStore] ordering: reorder/move semantics and that the new order survives a reload. */
class PageStoreTest {
  private fun ids(store: PageStore) = store.pages.value.map { it.id }

  @Test
  fun reorderMovesItemAndPersists() {
    val settings = MapSettings()
    val store = PageStore(settings)
    assertEquals(listOf("vehicle", "farm"), ids(store)) // the seed order

    store.reorder(0, 1)
    assertEquals(listOf("farm", "vehicle"), ids(store))

    // A fresh store over the same settings sees the persisted order.
    assertEquals(listOf("farm", "vehicle"), ids(PageStore(settings)))
  }

  @Test
  fun reorderIgnoresNoOpAndOutOfRange() {
    val store = PageStore(MapSettings())
    val before = ids(store)
    store.reorder(0, 0) // same slot
    store.reorder(-1, 1) // from out of range
    store.reorder(0, 5) // to out of range
    assertEquals(before, ids(store))
  }

  @Test
  fun moveByIdClampsToEnds() {
    val store = PageStore(MapSettings()) // [vehicle, farm]
    store.move("vehicle", Int.MAX_VALUE) // clamps to the last slot (must not overflow to the front)
    assertEquals(listOf("farm", "vehicle"), ids(store))
    store.move("vehicle", Int.MIN_VALUE) // clamps back to the first
    assertEquals(listOf("vehicle", "farm"), ids(store))
    store.move("missing", 1) // unknown id: no-op
    assertEquals(listOf("vehicle", "farm"), ids(store))
  }
}

/**
 * The starter pages are hand-authored cell coordinates, so nothing but this stops them drifting out
 * of step with the grid constants or with a widget's declared floor — changing [GridLayout.ROWS]
 * silently left a dead row across both seeds until these were added.
 */
class SeedPageTest {
  private val seeds = PageStore(MapSettings()).pages.value

  @Test
  fun everySeedIsLaidOutOnTheCurrentGrid() {
    for (page in seeds) {
      assertEquals(GridLayout.COLUMNS, page.layout.columns, "${page.id} columns")
      assertEquals(GridLayout.ROWS, page.layout.rows, "${page.id} rows")
    }
  }

  @Test
  fun noSeededTileOverflowsTheGridOrOverlapsAnother() {
    for (page in seeds) {
      val cells = page.layout.cells
      for (cell in cells) {
        assertTrue(
          cell.col >= 0 && cell.row >= 0 &&
            cell.col + cell.colSpan <= GridLayout.COLUMNS &&
            cell.row + cell.rowSpan <= GridLayout.ROWS,
          "${page.id}/${cell.widgetId} is outside the grid",
        )
      }
      for ((i, cell) in cells.withIndex()) {
        for (other in cells.drop(i + 1)) {
          assertTrue(!cell.overlaps(other), "${page.id}: ${cell.widgetId} overlaps ${other.widgetId}")
        }
      }
    }
  }

  @Test
  fun everySeededInstanceIdIsUniqueWithinItsPage() {
    for (page in seeds) {
      val ids = page.layout.cells.map { it.instanceId }
      assertEquals(ids.size, ids.toSet().size, "${page.id} repeats an instance id: $ids")
    }
  }

  @Test
  fun everySeededShortcutNamesARegisteredApp() {
    // The dock is hand-authored config, so an app id renamed in code would otherwise show up as four
    // grey "Unavailable" tiles on a fresh install rather than as a failing build.
    for (page in seeds) {
      for (cell in page.layout.cells.filter { it.widgetId == ShortcutWidget.id }) {
        val appId = cell.config[ShortcutWidget.APP_KEY]
        assertTrue(
          appId != null && AppRegistry.byId(appId) != null,
          "${page.id}/${cell.instanceId} points at unknown app $appId",
        )
      }
    }
  }

  @Test
  fun everySeededWidgetIsRegisteredAndPlacedAtOrAboveItsFloor() {
    for (page in seeds) {
      for (cell in page.layout.cells) {
        val widget = WidgetRegistry.byId(cell.widgetId)
        assertTrue(widget != null, "${page.id} places unknown widget ${cell.widgetId}")
        assertTrue(
          cell.colSpan >= widget.minColSpan && cell.rowSpan >= widget.minRowSpan,
          "${page.id}/${cell.widgetId} is ${cell.colSpan}x${cell.rowSpan}, " +
            "under its ${widget.minColSpan}x${widget.minRowSpan} floor",
        )
      }
    }
  }
}

/** What [PageStore] does with what it finds in storage: rescale it, repair it, or fall back. */
class PageStoreLoadTest {
  private fun storedPage(columns: Int, rows: Int, cells: String) = MapSettings().apply {
    putString(
      "vdt.pages.v2",
      """
      [{"id":"old","title":"Old","icon":"Grid","autoShow":"Never",
      "layout":{"columns":$columns,"rows":$rows,"cells":[$cells]}}]
      """.trimIndent().replace("\n", ""),
    )
  }

  @Test
  fun aLayoutSavedOnADifferentGridIsRescaledOnLoad() {
    val settings =
      storedPage(
        3,
        2,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":2,"rowSpan":2},
        {"instanceId":"i2","widgetId":"tasks","col":2,"row":0}""",
      )

    val layout = PageStore(settings).pages.value.single().layout
    assertEquals(GridLayout.COLUMNS, layout.columns)
    assertEquals(GridLayout.ROWS, layout.rows)
    // The map kept the two thirds of the width and the full height it had before.
    assertEquals(8, layout.cells.first { it.widgetId == "map" }.colSpan)
    assertEquals(GridLayout.ROWS, layout.cells.first { it.widgetId == "map" }.rowSpan)
    assertEquals(8, layout.cells.first { it.widgetId == "tasks" }.col)
  }

  @Test
  fun cellsWhoseWidgetIsGoneAreDroppedBeforeRescaling() {
    val settings =
      storedPage(
        3,
        2,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0},
        {"instanceId":"i2","widgetId":"removedLongAgo","col":1,"row":0}""",
      )

    val cells = PageStore(settings).pages.value.single().layout.cells
    assertEquals(listOf("map"), cells.map { it.widgetId })
    assertTrue(cells.single().colSpan == 4) // and the survivor was still rescaled
  }

  @Test
  fun aRepeatedInstanceIdIsDroppedSoTheGridCanKeyOnIt() {
    // WidgetGrid keys its tiles by instance id and Compose can't tell two apart under one key, so a
    // hand-edited or half-written file has to lose the duplicate rather than render it.
    val settings =
      storedPage(
        12,
        7,
        """{"instanceId":"dup","widgetId":"map","col":0,"row":0,"colSpan":4,"rowSpan":4},
        {"instanceId":"dup","widgetId":"engine","col":4,"row":0,"colSpan":4,"rowSpan":4}""",
      )

    val cells = PageStore(settings).pages.value.single().layout.cells
    assertEquals(listOf("map"), cells.map { it.widgetId }) // the first one wins
  }

  @Test
  fun storedConfigSurvivesTheLoad() {
    val settings =
      storedPage(
        12,
        7,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":4,"rowSpan":4,
        "config":{"layer":"soil"}}""",
      )

    assertEquals(mapOf("layer" to "soil"), PageStore(settings).pages.value.single().layout.cells.single().config)
  }

  @Test
  fun aTileThatScalesUpBelowItsWidgetsFloorIsGrownBackToIt() {
    // 6x6 -> 12x7 doubles the width but barely stretches the height, so a 1x1 lands as 2x1 — under
    // every readout widget's floor.
    val settings = storedPage(6, 6, """{"instanceId":"i1","widgetId":"engine","col":0,"row":0}""")

    val engine = PageStore(settings).pages.value.single().layout.cells.single()
    val widget = WidgetRegistry.byId("engine")!!
    assertTrue(engine.colSpan >= widget.minColSpan, "colSpan ${engine.colSpan} < ${widget.minColSpan}")
    assertTrue(engine.rowSpan >= widget.minRowSpan, "rowSpan ${engine.rowSpan} < ${widget.minRowSpan}")
  }

  @Test
  fun aPayloadFromBeforeInstanceIdsIsIgnoredAndTheSeedsComeBack() {
    // v1 cells have no instance id, so they can't be decoded into the current schema. The key bump is
    // what makes that a clean start rather than a decode error path.
    val settings = MapSettings()
    settings.putString(
      "vdt.pages",
      """[{"id":"old","title":"Old","icon":"Grid","autoShow":"Never","layout":{"columns":12,"rows":7,
      "cells":[{"widgetId":"map","col":0,"row":0,"colSpan":4,"rowSpan":4}]}}]
      """.trimIndent().replace("\n", ""),
    )

    assertEquals(listOf("vehicle", "farm"), PageStore(settings).pages.value.map { it.id })
  }
}
