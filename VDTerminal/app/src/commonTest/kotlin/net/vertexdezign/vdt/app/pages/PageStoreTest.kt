package net.vertexdezign.vdt.app.pages

import androidx.compose.ui.unit.dp
import com.russhwolf.settings.MapSettings
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.layout.Axis
import net.vertexdezign.vdt.app.layout.Empty
import net.vertexdezign.vdt.app.layout.GridAspect
import net.vertexdezign.vdt.app.layout.LayoutNode
import net.vertexdezign.vdt.app.layout.Split
import net.vertexdezign.vdt.app.layout.Tile
import net.vertexdezign.vdt.app.layout.layOut
import net.vertexdezign.vdt.app.layout.layoutFrame
import net.vertexdezign.vdt.app.layout.normalize
import net.vertexdezign.vdt.app.layout.pathOf
import net.vertexdezign.vdt.app.layout.removeToEmpty
import net.vertexdezign.vdt.app.layout.swap
import net.vertexdezign.vdt.app.layout.tiles
import net.vertexdezign.vdt.app.panels.RigSlot
import net.vertexdezign.vdt.app.widgets.RigSlotWidget
import net.vertexdezign.vdt.app.widgets.ShortcutWidget
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import net.vertexdezign.vdt.app.widgets.WidgetSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [PageStore] ordering: reorder/move semantics and that the new order survives a reload. */
class PageStoreTest {
  private fun ids(store: PageStore) = store.pages.value.map { it.id }

  @Test
  fun reorderMovesItemAndPersists() {
    val settings = MapSettings()
    val store = PageStore(settings)
    assertEquals(listOf("vehicle", "farm", "pillar"), ids(store)) // the seed order

    store.reorder(0, 1)
    assertEquals(listOf("farm", "vehicle", "pillar"), ids(store))

    // A fresh store over the same settings sees the persisted order.
    assertEquals(listOf("farm", "vehicle", "pillar"), ids(PageStore(settings)))
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
    val store = PageStore(MapSettings()) // [vehicle, farm, pillar]
    store.move("vehicle", Int.MAX_VALUE) // clamps to the last slot (must not overflow to the front)
    assertEquals(listOf("farm", "pillar", "vehicle"), ids(store))
    store.move("vehicle", Int.MIN_VALUE) // clamps back to the first
    assertEquals(listOf("vehicle", "farm", "pillar"), ids(store))
    store.move("missing", 1) // unknown id: no-op
    assertEquals(listOf("vehicle", "farm", "pillar"), ids(store))
  }
}

/**
 * The starter pages are hand-written trees, so nothing but this stops them drifting out of step with
 * a widget's declared floor or the app registry.
 *
 * Every case runs over **both** of a page's arrangements — the portrait one is the half nobody looks
 * at on a desk.
 */
class SeedPageTest {
  private val seeds = PageStore(MapSettings()).pages.value

  /** One page as laid out for one aspect; [toString] is what names it in a failure. */
  private data class Arrangement(val page: Page, val aspect: GridAspect, val layout: LayoutNode) {
    override fun toString(): String = "${page.id}/${aspect.name.lowercase()}"
  }

  private val arrangements =
    seeds.flatMap { page -> GridAspect.entries.map { Arrangement(page, it, page.layoutFor(it)) } }

  /**
   * The bodies the old grids were tuned to: an 11" iPad held landscape, and an iPhone 15 Pro standing
   * up once the shell and padding are gone. A seed is what a fresh install sees, so it has to clear
   * every floor on the devices it was written for, even though floors never stop a page rendering.
   */
  private fun bodyFor(aspect: GridAspect) = when (aspect) {
    GridAspect.Landscape -> layoutFrame(1194.dp, 696.dp)
    GridAspect.Portrait -> layoutFrame(377.dp, 777.dp)
  }

  @Test
  fun everySeedIsFullyFilled() {
    for (arrangement in arrangements) {
      val empties = arrangement.layout.layOut(bodyFor(arrangement.aspect).bounds, 8f).filter { it.node is Empty }
      assertTrue(empties.isEmpty(), "$arrangement has free space at ${empties.map { it.path }}")
    }
  }

  @Test
  fun everySeededWidgetIsRegisteredAndDrawnAtOrAboveItsFloor() {
    for (arrangement in arrangements) {
      val frame = bodyFor(arrangement.aspect)
      for (leaf in arrangement.layout.layOut(frame.bounds, frame.gap)) {
        val tile = leaf.node as? Tile ?: continue
        val widget = WidgetRegistry.byId(tile.widgetId)
        assertTrue(widget != null, "$arrangement places unknown widget ${tile.widgetId}")
        assertTrue(
          leaf.rect.width + 0.5f >= widget.minWidth.value && leaf.rect.height + 0.5f >= widget.minHeight.value,
          "$arrangement/${tile.instanceId} is ${leaf.rect.width}x${leaf.rect.height}dp, " +
            "under its ${widget.minWidth}x${widget.minHeight} floor",
        )
      }
    }
  }

  @Test
  fun everySeedIsInCanonicalForm() {
    // Hand-written trees are the one place a same-axis nesting or a stray weight could slip in.
    for (arrangement in arrangements) assertEquals(arrangement.layout.normalize(), arrangement.layout, "$arrangement")
  }

  @Test
  fun everySeededInstanceIdIsUniqueWithinItsArrangement() {
    for (arrangement in arrangements) {
      val ids = arrangement.layout.tiles.map { it.instanceId }
      assertEquals(ids.size, ids.toSet().size, "$arrangement repeats an instance id: $ids")
    }
  }

  @Test
  fun aSeedsTwoArrangementsHoldTheSameTiles() {
    // Shared instance ids are what carry a tile's zoom, filters and rig position across a rotation. A
    // portrait page that re-lettered its tiles would look identical and quietly reset all of it.
    for (page in seeds) {
      assertEquals(
        page.landscape.tiles.mapTo(mutableSetOf()) { it.instanceId },
        page.portrait.tiles.mapTo(mutableSetOf()) { it.instanceId },
        "${page.id}: the two arrangements disagree about which tiles are on the page",
      )
    }
  }

  @Test
  fun everySeededShortcutNamesARegisteredApp() {
    // The dock is hand-authored config, so an app id renamed in code would otherwise show up as four
    // grey "Unavailable" tiles on a fresh install rather than as a failing build.
    for (arrangement in arrangements) {
      for (tile in arrangement.layout.tiles.filter { it.widgetId == ShortcutWidget.id }) {
        val appId = tile.config[ShortcutWidget.APP_KEY]
        assertTrue(
          appId != null && AppRegistry.byId(appId) != null,
          "$arrangement/${tile.instanceId} points at unknown app $appId",
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
        arrangement.layout.tiles
          .filter { it.widgetId == RigSlotWidget.id }
          .mapNotNull { it.config[RigSlotWidget.SLOT_KEY] }
      assertEquals(RigSlot.entries.map { it.name }.toSet(), slots.toSet(), "$arrangement")
    }
  }

  @Test
  fun everySeededSlotNamesARigPosition() {
    for (arrangement in arrangements) {
      for (tile in arrangement.layout.tiles.filter { it.widgetId == RigSlotWidget.id }) {
        val slot = tile.config[RigSlotWidget.SLOT_KEY]
        assertTrue(
          RigSlot.entries.any { it.name == slot },
          "$arrangement/${tile.instanceId} points at unknown rig position $slot",
        )
      }
    }
  }

  @Test
  fun theVehicleSeedsFrontAndRearAreTheEndsOfOneSplit() {
    // So they stay the same width whatever the map between them is dragged to.
    val top = (seeds.first { it.id == "vehicle" }.landscape as Split).children.first().node as Split
    assertEquals(listOf("veh-front", "veh-map", "veh-rear"), top.tiles.map { it.instanceId })
    assertEquals(top.children.first().weight, top.children.last().weight)
  }
}

/**
 * A removed tile takes its instance-scoped view state with it. Without this a page's worth of zoom
 * and filter keys would survive every widget that ever sat on it, for the life of the install.
 *
 * "Removed" means gone from *both* arrangements, which is the whole subtlety: a tile you deleted while
 * the phone was upright is still on the page when you turn it back.
 */
class PageStoreInstanceCleanupTest {
  private fun LayoutNode.without(instanceId: String): LayoutNode = pathOf(instanceId)?.let { removeToEmpty(it) } ?: this

  private fun removeEverywhere(page: Page, instanceId: String): Page = GridAspect.entries.fold(page) { acc, aspect ->
    acc.withLayout(aspect, acc.layoutFor(aspect).without(instanceId))
  }

  @Test
  fun removingATileForgetsItsViewState() {
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "vehicle" }
    val map = page.landscape.tiles.first { it.widgetId == "map" }
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
    val map = page.landscape.tiles.first { it.widgetId == "map" }
    WidgetSettings(settings, map.instanceId).putFloat("zoom", 4f)

    store.update(page.withLayout(GridAspect.Landscape, page.landscape.without(map.instanceId)))

    assertEquals(4f, WidgetSettings(settings, map.instanceId).getFloat("zoom", 1f))
  }

  @Test
  fun deletingAPageForgetsEveryTileOnIt() {
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "farm" }
    val tiles = page.layouts.flatMap { it.tiles }
    for (tile in tiles) WidgetSettings(settings, tile.instanceId).putFloat("zoom", 4f)

    store.remove("farm")

    for (tile in tiles) {
      assertEquals(1f, WidgetSettings(settings, tile.instanceId).getFloat("zoom", 1f), tile.instanceId)
    }
  }

  @Test
  fun movingATileKeepsItsViewState() {
    // The purge keys on instances that left the layout, not on the tree changing around them —
    // swapping a tile rewrites its place, and losing the zoom every time you moved it would be worse
    // than never having persisted it.
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "farm" }
    val tasks = page.landscape.tiles.first { it.widgetId == "tasks" }
    WidgetSettings(settings, tasks.instanceId).putFloat("zoom", 4f)

    val frame = layoutFrame(1194.dp, 696.dp)
    val moved =
      page.landscape.swap(
        page.landscape.pathOf(tasks.instanceId)!!,
        page.landscape.pathOf("farm-cropRotation")!!,
        frame,
      )
    assertNotEquals(page.landscape, moved)
    store.update(page.withLayout(GridAspect.Landscape, moved))

    assertEquals(4f, WidgetSettings(settings, tasks.instanceId).getFloat("zoom", 1f))
  }

  @Test
  fun editingOneArrangementLeavesTheOtherAlone() {
    val settings = MapSettings()
    val store = PageStore(settings)
    val page = store.pages.value.first { it.id == "farm" }
    val before = page.portrait

    store.update(page.withLayout(GridAspect.Landscape, page.landscape.without("farm-tasks")))

    assertEquals(before, PageStore(settings).pages.value.first { it.id == "farm" }.portrait)
  }
}

/** What [PageStore] does with what it finds in storage: read it, migrate it, repair it, or fall back. */
class PageStoreLoadTest {
  private fun v2Page(columns: Int, rows: Int, cells: String, portrait: String = "") = MapSettings().apply {
    putString(
      "vdt.pages.v2",
      """
      [{"id":"old","title":"Old","icon":"Grid","autoShow":"Never",
      "layout":{"columns":$columns,"rows":$rows,"cells":[$cells]}$portrait}]
      """.trimIndent().replace("\n", ""),
    )
  }

  private fun MapSettings.loaded(): Page = PageStore(this).pages.value.single()

  @Test
  fun aGridPageIsConvertedToATreeOnFirstLoad() {
    val settings =
      v2Page(
        3,
        2,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":2,"rowSpan":2},
        {"instanceId":"i2","widgetId":"tasks","col":2,"row":0}""",
      )

    val tree = assertIs<Split>(settings.loaded().landscape)
    assertEquals(Axis.Row, tree.axis)
    // The map kept the two thirds of the width and the full height it had before.
    assertEquals(Tile("i1", "map"), tree.children[0].node)
    assertEquals(2f / 3, tree.children[0].weight, 0.001f)
    assertEquals(listOf("i1", "i2"), tree.tiles.map { it.instanceId })
  }

  @Test
  fun theConversionIsWrittenBackAndTheGridPagesLeftAlone() {
    val settings = v2Page(12, 7, """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":8,"rowSpan":7}""")
    val v2 = settings.getStringOrNull("vdt.pages.v2")
    val first = settings.loaded()

    assertTrue(settings.getStringOrNull("vdt.pages.v3") != null)
    assertEquals(v2, settings.getStringOrNull("vdt.pages.v2"))
    // Read back as v3, not converted again: the empty beside the map keeps its id.
    assertEquals(first, settings.loaded())
  }

  @Test
  fun v3WinsOverV2() {
    val settings = v2Page(12, 7, """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":8,"rowSpan":7}""")
    settings.putString(
      "vdt.pages.v3",
      """[{"id":"new","title":"New","icon":"Grid","autoShow":"Never","landscape":{"type":"tile","instanceId":"t","widgetId":"engine"}}]""",
    )
    assertEquals("new", settings.loaded().id)
  }

  @Test
  fun aTileWhoseWidgetIsGoneLeavesItsSpaceEmpty() {
    // Not dropped: on a tree that would hand its space to the neighbours and reflow the page.
    val settings =
      v2Page(
        3,
        2,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"rowSpan":2},
        {"instanceId":"i2","widgetId":"removedLongAgo","col":1,"row":0,"colSpan":2,"rowSpan":2}""",
      )

    val tree = assertIs<Split>(settings.loaded().landscape)
    assertEquals(listOf("map"), tree.tiles.map { it.widgetId })
    assertIs<Empty>(tree.children[1].node)
    assertEquals(2f / 3, tree.children[1].weight, 0.001f)
  }

  @Test
  fun aRepeatedInstanceIdBecomesEmptySoTheRendererCanKeyOnIt() {
    // Tiles are keyed by instance id and Compose can't tell two apart under one key, so a
    // hand-edited or half-written file has to lose the duplicate rather than render it.
    val settings =
      v2Page(
        12,
        7,
        """{"instanceId":"dup","widgetId":"map","col":0,"row":0,"colSpan":4,"rowSpan":7},
        {"instanceId":"dup","widgetId":"engine","col":4,"row":0,"colSpan":8,"rowSpan":7}""",
      )

    val tree = settings.loaded().landscape
    assertEquals(listOf("map"), tree.tiles.map { it.widgetId }) // the first one wins
  }

  @Test
  fun storedConfigSurvivesTheLoad() {
    val settings =
      v2Page(
        12,
        7,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":12,"rowSpan":7,
        "config":{"layer":"soil"}}""",
      )

    assertEquals(mapOf("layer" to "soil"), settings.loaded().landscape.tiles.single().config)
  }

  @Test
  fun aGridPageWithNoPortraitArrangementKeepsTheOneItWasShown() {
    // The grid showed such a page's landscape layout rescaled onto the portrait grid, and as ratios
    // that is the landscape tree itself — not the flipped default, which would rearrange it.
    val settings =
      v2Page(
        12,
        7,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":8,"rowSpan":7},
        {"instanceId":"i2","widgetId":"tasks","col":8,"row":0,"colSpan":4,"rowSpan":4}""",
      )

    val page = settings.loaded()
    assertEquals(page.landscape, page.portrait)
  }

  @Test
  fun aStoredPortraitArrangementIsConvertedOnItsOwn() {
    val settings =
      v2Page(
        12,
        7,
        """{"instanceId":"i1","widgetId":"map","col":0,"row":0,"colSpan":8,"rowSpan":7}""",
        portrait =
        ""","portrait":{"columns":6,"rows":12,"cells":[
        {"instanceId":"i1","widgetId":"map","col":0,"row":6,"colSpan":6,"rowSpan":6}]}""",
      )

    val portrait = assertIs<Split>(settings.loaded().portrait)
    // The user put it in the bottom half.
    assertEquals(Axis.Column, portrait.axis)
    assertIs<Empty>(portrait.children[0].node)
    assertEquals(Tile("i1", "map"), portrait.children[1].node)
  }

  @Test
  fun aV3PageStoredWithOneArrangementGetsTheOtherFlipped() {
    val settings = MapSettings()
    settings.putString(
      "vdt.pages.v3",
      """[{"id":"p","title":"P","icon":"Grid","autoShow":"Never","landscape":{"type":"split","axis":"Row",
      "children":[{"weight":0.5,"node":{"type":"tile","instanceId":"a","widgetId":"map"}},
      {"weight":0.5,"node":{"type":"tile","instanceId":"b","widgetId":"engine"}}]}}]
      """.trimIndent().replace("\n", ""),
    )

    val page = settings.loaded()
    assertEquals(Axis.Column, (page.portrait as Split).axis)
    assertEquals(page.landscape.tiles, page.portrait.tiles)
  }

  @Test
  fun aPayloadFromBeforeInstanceIdsIsIgnoredAndTheSeedsComeBack() {
    // v1 cells have no instance id; nothing reads that key any more, so it is a clean start.
    val settings = MapSettings()
    settings.putString(
      "vdt.pages",
      """[{"id":"old","title":"Old","icon":"Grid","autoShow":"Never","layout":{"columns":12,"rows":7,
      "cells":[{"widgetId":"map","col":0,"row":0,"colSpan":4,"rowSpan":4}]}}]
      """.trimIndent().replace("\n", ""),
    )

    assertEquals(listOf("vehicle", "farm", "pillar"), PageStore(settings).pages.value.map { it.id })
  }

  @Test
  fun anUnreadableV2PayloadFallsBackToTheSeeds() {
    val settings = MapSettings()
    settings.putString("vdt.pages.v2", "not json")
    assertEquals(listOf("vehicle", "farm", "pillar"), PageStore(settings).pages.value.map { it.id })
  }
}
