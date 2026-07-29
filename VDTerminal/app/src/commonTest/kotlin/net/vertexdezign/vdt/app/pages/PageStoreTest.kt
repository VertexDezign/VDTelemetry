package net.vertexdezign.vdt.app.pages

import com.russhwolf.settings.MapSettings
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.layout.GridAspect
import net.vertexdezign.vdt.app.layout.GridLayout
import net.vertexdezign.vdt.app.panels.RigSlot
import net.vertexdezign.vdt.app.widgets.RigSlotWidget
import net.vertexdezign.vdt.app.widgets.ShortcutWidget
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import net.vertexdezign.vdt.app.widgets.WidgetSettings
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
 *
 * Every case runs over **both** of a page's arrangements. Hand-authoring doubled when portrait got
 * its own grid, and the portrait one is the half nobody looks at on a desk.
 */
class SeedPageTest {
  private val seeds = PageStore(MapSettings()).pages.value

  /** One page as laid out for one aspect; [toString] is what names it in a failure. */
  private data class Arrangement(val page: Page, val aspect: GridAspect, val layout: GridLayout) {
    override fun toString(): String = "${page.id}/${aspect.name.lowercase()}"
  }

  private val arrangements =
    seeds.flatMap { page -> GridAspect.entries.map { Arrangement(page, it, page.layoutFor(it)) } }

  @Test
  fun everySeedIsLaidOutOnItsAspectsGrid() {
    for (arrangement in arrangements) {
      assertEquals(arrangement.aspect.columns, arrangement.layout.columns, "$arrangement columns")
      assertEquals(arrangement.aspect.rows, arrangement.layout.rows, "$arrangement rows")
    }
  }

  @Test
  fun noSeededTileOverflowsTheGridOrOverlapsAnother() {
    for (arrangement in arrangements) {
      val cells = arrangement.layout.cells
      for (cell in cells) {
        assertTrue(
          cell.col >= 0 && cell.row >= 0 &&
            cell.col + cell.colSpan <= arrangement.aspect.columns &&
            cell.row + cell.rowSpan <= arrangement.aspect.rows,
          "$arrangement/${cell.widgetId} is outside the grid",
        )
      }
      for ((i, cell) in cells.withIndex()) {
        for (other in cells.drop(i + 1)) {
          assertTrue(!cell.overlaps(other), "$arrangement: ${cell.widgetId} overlaps ${other.widgetId}")
        }
      }
    }
  }

  @Test
  fun everySeededInstanceIdIsUniqueWithinItsArrangement() {
    for (arrangement in arrangements) {
      val ids = arrangement.layout.cells.map { it.instanceId }
      assertEquals(ids.size, ids.toSet().size, "$arrangement repeats an instance id: $ids")
    }
  }

  @Test
  fun aSeedsTwoArrangementsHoldTheSameTiles() {
    // Shared instance ids are what carry a tile's zoom, filters and rig position across a rotation. A
    // portrait page that re-lettered its tiles would look identical and quietly reset all of it.
    for (page in seeds) {
      assertEquals(
        page.landscape.cells.mapTo(mutableSetOf()) { it.instanceId },
        page.portrait.cells.mapTo(mutableSetOf()) { it.instanceId },
        "${page.id}: the two arrangements disagree about which tiles are on the page",
      )
    }
  }

  @Test
  fun everySeededShortcutNamesARegisteredApp() {
    // The dock is hand-authored config, so an app id renamed in code would otherwise show up as four
    // grey "Unavailable" tiles on a fresh install rather than as a failing build.
    for (arrangement in arrangements) {
      for (cell in arrangement.layout.cells.filter { it.widgetId == ShortcutWidget.id }) {
        val appId = cell.config[ShortcutWidget.APP_KEY]
        assertTrue(
          appId != null && AppRegistry.byId(appId) != null,
          "$arrangement/${cell.instanceId} points at unknown app $appId",
        )
      }
    }
  }

  @Test
  fun theVehicleSeedPlacesAllThreeRigPositionsInBothOrientations() {
    // The point of the rig-slot rework: front, the machine and rear are each their own tile, so the
    // page can put them where they actually sit rather than in one fixed two-column panel.
    for (arrangement in arrangements.filter { it.page.id == "vehicle" }) {
      val slots =
        arrangement.layout.cells
          .filter { it.widgetId == RigSlotWidget.id }
          .mapNotNull { it.config[RigSlotWidget.SLOT_KEY] }
      assertEquals(RigSlot.entries.map { it.name }.toSet(), slots.toSet(), "$arrangement")
    }
  }

  @Test
  fun everySeededSlotNamesARigPosition() {
    for (arrangement in arrangements) {
      for (cell in arrangement.layout.cells.filter { it.widgetId == RigSlotWidget.id }) {
        val slot = cell.config[RigSlotWidget.SLOT_KEY]
        assertTrue(
          RigSlot.entries.any { it.name == slot },
          "$arrangement/${cell.instanceId} points at unknown rig position $slot",
        )
      }
    }
  }

  @Test
  fun everySeededWidgetIsRegisteredAndPlacedAtOrAboveItsFloor() {
    for (arrangement in arrangements) {
      for (cell in arrangement.layout.cells) {
        val widget = WidgetRegistry.byId(cell.widgetId)
        assertTrue(widget != null, "$arrangement places unknown widget ${cell.widgetId}")
        assertTrue(
          cell.colSpan >= widget.minColSpan && cell.rowSpan >= widget.minRowSpan,
          "$arrangement/${cell.widgetId} is ${cell.colSpan}x${cell.rowSpan}, " +
            "under its ${widget.minColSpan}x${widget.minRowSpan} floor",
        )
      }
    }
  }
}

/**
 * A removed tile takes its instance-scoped view state with it. Without this a page's worth of zoom
 * and filter keys would survive every widget that ever sat on it, for the life of the install.
 *
 * "Removed" means gone from *both* arrangements, which is the whole subtlety since portrait got its
 * own: a tile you deleted while the phone was upright is still on the page when you turn it back.
 */
class PageStoreInstanceCleanupTest {
  private fun removeEverywhere(page: Page, instanceId: String): Page = GridAspect.entries.fold(page) { acc, aspect ->
    val cell = acc.layoutFor(aspect).cells.firstOrNull { it.instanceId == instanceId } ?: return@fold acc
    acc.withLayout(aspect, acc.layoutFor(aspect).removeAt(cell.col, cell.row))
  }

  @Test
  fun removingATileForgetsItsViewState() {
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "vehicle" }
    val map = page.landscape.cells.first { it.widgetId == "map" }
    WidgetSettings(settings, map.instanceId).putFloat("zoom", 4f)

    store.update(removeEverywhere(page, map.instanceId))

    assertEquals(1f, WidgetSettings(settings, map.instanceId).getFloat("zoom", 1f))
  }

  @Test
  fun removingATileFromOneOrientationKeepsItsStateForTheOther() {
    // The tile is still on the page — you are looking at the other arrangement of it. Purging here
    // would drop the map's zoom and layer because the user happened to tidy up in portrait.
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "vehicle" }
    val map = page.landscape.cells.first { it.widgetId == "map" }
    WidgetSettings(settings, map.instanceId).putFloat("zoom", 4f)

    store.update(page.withLayout(GridAspect.Landscape, page.landscape.removeAt(map.col, map.row)))

    assertEquals(4f, WidgetSettings(settings, map.instanceId).getFloat("zoom", 1f))
  }

  @Test
  fun deletingAPageForgetsEveryTileOnIt() {
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "farm" }
    val cells = page.layouts.flatMap { it.cells }
    for (cell in cells) WidgetSettings(settings, cell.instanceId).putFloat("zoom", 4f)

    store.remove("farm")

    for (cell in cells) {
      assertEquals(1f, WidgetSettings(settings, cell.instanceId).getFloat("zoom", 1f), cell.instanceId)
    }
  }

  @Test
  fun movingATileKeepsItsViewState() {
    // The purge keys on instances that left the layout, not on the cell changing — dragging or
    // resizing a tile rewrites its cell, and losing the zoom every time you nudged it would be worse
    // than never having persisted it.
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "farm" }
    val tasks = page.landscape.cells.first { it.widgetId == "tasks" }
    WidgetSettings(settings, tasks.instanceId).putFloat("zoom", 4f)

    val moved = page.landscape.resize(tasks, colSpan = tasks.colSpan, rowSpan = tasks.rowSpan - 1)
    store.update(page.withLayout(GridAspect.Landscape, moved))

    assertEquals(4f, WidgetSettings(settings, tasks.instanceId).getFloat("zoom", 1f))
  }

  @Test
  fun editingOneArrangementLeavesTheOtherAlone() {
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "farm" }
    val before = page.portrait

    val tasks = page.landscape.cells.first { it.widgetId == "tasks" }
    store.update(page.withLayout(GridAspect.Landscape, page.landscape.removeAt(tasks.col, tasks.row)))

    assertEquals(before, PageStore(settings).pages.value.first { it.id == "farm" }.portrait)
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

    val layout = PageStore(settings).pages.value.single().landscape
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

    val cells = PageStore(settings).pages.value.single().landscape.cells
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

    val cells = PageStore(settings).pages.value.single().landscape.cells
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

    assertEquals(mapOf("layer" to "soil"), PageStore(settings).pages.value.single().landscape.cells.single().config)
  }

  @Test
  fun aTileThatScalesUpBelowItsWidgetsFloorIsGrownBackToIt() {
    // 6x6 -> 12x7 doubles the width but barely stretches the height, so a 1x1 lands as 2x1 — under
    // every readout widget's floor.
    val settings = storedPage(6, 6, """{"instanceId":"i1","widgetId":"engine","col":0,"row":0}""")

    val engine = PageStore(settings).pages.value.single().landscape.cells.single()
    val widget = WidgetRegistry.byId("engine")!!
    assertTrue(engine.colSpan >= widget.minColSpan, "colSpan ${engine.colSpan} < ${widget.minColSpan}")
    assertTrue(engine.rowSpan >= widget.minRowSpan, "rowSpan ${engine.rowSpan} < ${widget.minRowSpan}")
  }

  @Test
  fun aStoredPageWithNoPortraitArrangementGetsOneDerivedFromItsLandscape() {
    // The whole reason the storage key did *not* have to be bumped for portrait: a page written
    // before it exists simply has no `portrait` key, so the schema's default fills one in and the
    // user keeps the layout they built.
    val settings =
      storedPage(
        12,
        7,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":8,"rowSpan":7},
        {"instanceId":"i2","widgetId":"tasks","col":8,"row":0,"colSpan":4,"rowSpan":4}""",
      )

    val page = PageStore(settings).pages.value.single()
    assertEquals(GridLayout.PORTRAIT_COLUMNS, page.portrait.columns)
    assertEquals(GridLayout.PORTRAIT_ROWS, page.portrait.rows)
    // Same tiles, same identities — so their per-instance state is the same state in both.
    assertEquals(
      page.landscape.cells.mapTo(mutableSetOf()) { it.instanceId },
      page.portrait.cells.mapTo(mutableSetOf()) { it.instanceId },
    )
    // 12 -> 6 is exact, so the map keeps precisely the two thirds of the width it had.
    assertEquals(4, page.portrait.cells.first { it.widgetId == "map" }.colSpan)
  }

  @Test
  fun aStoredPortraitArrangementIsKeptRatherThanRederived() {
    val settings = MapSettings().apply {
      putString(
        "vdt.pages.v2",
        """
        [{"id":"old","title":"Old","icon":"Grid","autoShow":"Never",
        "layout":{"columns":12,"rows":7,"cells":[
        {"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":8,"rowSpan":7}]},
        "portrait":{"columns":6,"rows":12,"cells":[
        {"instanceId":"i1","widgetId":"map","col":0,"row":6,"colSpan":6,"rowSpan":6}]}}]
        """.trimIndent().replace("\n", ""),
      )
    }

    val portrait = PageStore(settings).pages.value.single().portrait.cells.single()
    // Derivation would have put it at row 0 spanning 4 columns; the user put it in the bottom half.
    assertEquals(6, portrait.row)
    assertEquals(6, portrait.colSpan)
  }

  @Test
  fun aPortraitArrangementSavedOnAnOlderGridIsRescaledOnItsOwn() {
    val settings = MapSettings().apply {
      putString(
        "vdt.pages.v2",
        """
        [{"id":"old","title":"Old","icon":"Grid","autoShow":"Never",
        "layout":{"columns":12,"rows":7,"cells":[]},
        "portrait":{"columns":3,"rows":6,"cells":[
        {"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":3,"rowSpan":3}]}}]
        """.trimIndent().replace("\n", ""),
      )
    }

    val portrait = PageStore(settings).pages.value.single().portrait
    assertEquals(GridLayout.PORTRAIT_COLUMNS, portrait.columns)
    assertEquals(GridLayout.PORTRAIT_ROWS, portrait.rows)
    assertEquals(6, portrait.cells.single().colSpan) // full width before, full width after
    assertEquals(6, portrait.cells.single().rowSpan) // half the height before, half after
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
