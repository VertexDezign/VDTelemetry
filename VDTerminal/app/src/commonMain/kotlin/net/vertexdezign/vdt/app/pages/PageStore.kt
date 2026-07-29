package net.vertexdezign.vdt.app.pages

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.app.layout.GridAspect
import net.vertexdezign.vdt.app.layout.GridLayout
import net.vertexdezign.vdt.app.layout.LayoutCell
import net.vertexdezign.vdt.app.panels.RigSlot
import net.vertexdezign.vdt.app.widgets.ClusterLevelsWidget
import net.vertexdezign.vdt.app.widgets.ClusterReadoutWidget
import net.vertexdezign.vdt.app.widgets.RigSlotWidget
import net.vertexdezign.vdt.app.widgets.ShortcutWidget
import net.vertexdezign.vdt.app.widgets.TelltaleWidget
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import net.vertexdezign.vdt.app.widgets.WidgetSettings
import kotlin.random.Random

/**
 * The user's [Page]s: the single source of truth for what pages exist and how each is laid out,
 * persisted to [Settings] as JSON on every change.
 *
 * On first run (or if the stored value is unreadable) the [seedPages] defaults are used, so the
 * terminal is useful out of the box; they are ordinary pages afterwards — editable and deletable
 * like any the user creates. Loading sanitizes away cells whose widget is no longer registered, so
 * removing a widget from the code can't break a page a user saved earlier, and re-expresses any
 * layout saved under an older grid in the current one (see [GridLayout.rescaledTo]).
 */
class PageStore(private val settings: Settings) {
  private val json = Json { ignoreUnknownKeys = true }

  private val _pages = MutableStateFlow(load())
  val pages: StateFlow<List<Page>> = _pages.asStateFlow()

  /** Replaces the page with [page]'s id; no-op if it's gone (e.g. deleted in another tab). */
  fun update(page: Page) = persist(_pages.value.map { if (it.id == page.id) page else it })

  fun remove(id: String) = persist(_pages.value.filterNot { it.id == id })

  /**
   * Re-adds any [seedPages] that are currently missing (matched by id), leaving existing pages
   * untouched. Gives the user a way back to the starter Vehicle/Farm dashboards after deleting them —
   * in particular after deleting every page.
   */
  fun restoreDefaults() {
    val existing = _pages.value.mapTo(mutableSetOf()) { it.id }
    persist(_pages.value + seedPages().filterNot { it.id in existing })
  }

  /**
   * Moves the page currently at [fromIndex] to [toIndex], shifting the pages in between. Out-of-range
   * indices or a no-op move (same slot) leave the list untouched. Order is the single knob that drives
   * both swipe order and auto-switch priority (the shell activates the *first* page matching a state),
   * so persisting it here is what makes reordering stick and change which page auto-shows.
   */
  fun reorder(fromIndex: Int, toIndex: Int) {
    val list = _pages.value
    if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
    val next = list.toMutableList()
    next.add(toIndex, next.removeAt(fromIndex))
    persist(next)
  }

  /** Moves the page with [id] by [delta] slots (negative = earlier), clamped to the ends. */
  fun move(id: String, delta: Int) {
    val from = _pages.value.indexOfFirst { it.id == id }
    if (from < 0) return
    // Long math so an extreme delta (e.g. Int.MAX_VALUE) can't overflow and wrap past the clamp.
    val target = (from.toLong() + delta).coerceIn(0L, _pages.value.lastIndex.toLong()).toInt()
    reorder(from, target)
  }

  /** Appends a fresh empty page and returns it, so the caller can open it. */
  fun create(): Page {
    val page =
      Page(
        id = "page-" + Random.nextLong(0, Long.MAX_VALUE).toString(36),
        title = "New Page",
        icon = PageIcon.Grid,
        autoShow = AutoShow.Never,
        landscape = GridLayout.empty(GridAspect.Landscape),
        portrait = GridLayout.empty(GridAspect.Portrait),
      )
    persist(_pages.value + page)
    return page
  }

  /**
   * Writes [list] and forgets the view state of every instance that just disappeared from it.
   *
   * Every mutation funnels through here, so one check covers removing a tile, deleting a whole page
   * and anything added later. Without it a widget's instance-scoped settings (see [WidgetSettings])
   * would outlive the tile that owned them, accumulating in storage for the rest of the install.
   */
  private fun persist(list: List<Page>) {
    val gone = instanceIds(_pages.value) - instanceIds(list)
    _pages.value = list
    settings.putString(KEY, json.encodeToString(ListSerializer, list))
    for (instanceId in gone) WidgetSettings.purge(settings, instanceId)
  }

  /**
   * Across *both* arrangements: a tile that is gone from the portrait page but still on the landscape
   * one is not gone, and purging its settings because the device happened to be turned would be a
   * silent data loss the user has no way to connect to what they did.
   */
  private fun instanceIds(pages: List<Page>): Set<String> =
    pages.flatMapTo(mutableSetOf()) { page -> page.layouts.flatMap { layout -> layout.cells.map { it.instanceId } } }

  private fun load(): List<Page> {
    val raw = settings.getStringOrNull(KEY) ?: return seedPages()
    return runCatching { json.decodeFromString(ListSerializer, raw) }
      .getOrNull()
      ?.map(::sanitize)
      ?: seedPages()
  }

  /**
   * Brings a stored page up to date, one [GridAspect] at a time — the arrangements are independent
   * data and each has to land on its own grid.
   *
   * Per arrangement: drop cells whose widget no longer exists or whose instance id repeats one
   * already seen, then re-express what's left on that aspect's current grid. A layout carries its own
   * dimensions, so a 3×2 arrangement scales up to fill 12×7 rather than being read as a corner of it,
   * and a portrait arrangement that arrived as the schema's derived default (12×7 halved) is already
   * on 6×12 and passes through untouched.
   *
   * The duplicate check guards the one invariant the rest of the app leans on: instance ids are
   * unique **within an arrangement**. `WidgetGrid` keys its tiles by them, and Compose can't tell two
   * tiles apart under one key — a hand-edited or half-written file would otherwise show up as a tile
   * that won't drag rather than as bad data. Across the two arrangements a repeat is not a duplicate
   * but the point: that is the same tile, seen in the other orientation.
   *
   * Scaling doesn't know about widget floors — a 1×1 on an old 6×6 page lands as 2×1, under the
   * minimum most widgets now declare — so each undersized tile is then grown back to its floor where
   * there's room. Best effort: [GridLayout.resize] refuses anything that would collide or overrun, so
   * a tile hemmed in by its neighbours simply stays small until the user rearranges the page.
   */
  private fun sanitize(page: Page): Page = GridAspect.entries.fold(page) { acc, aspect ->
    acc.withLayout(aspect, sanitize(acc.layoutFor(aspect), aspect))
  }

  private fun sanitize(layout: GridLayout, aspect: GridAspect): GridLayout {
    val known =
      layout.cells
        .filter { WidgetRegistry.byId(it.widgetId) != null }
        .distinctBy { it.instanceId }
    var cleaned = layout.copy(cells = known).rescaledTo(aspect.columns, aspect.rows)
    for (cell in cleaned.cells.toList()) {
      val widget = WidgetRegistry.byId(cell.widgetId) ?: continue
      if (cell.colSpan >= widget.minColSpan && cell.rowSpan >= widget.minRowSpan) continue
      cleaned =
        cleaned.resize(
          cell,
          colSpan = maxOf(cell.colSpan, widget.minColSpan),
          rowSpan = maxOf(cell.rowSpan, widget.minRowSpan),
          minColSpan = widget.minColSpan,
          minRowSpan = widget.minRowSpan,
        )
    }
    return cleaned
  }

  private companion object {
    /**
     * Bumped from `vdt.pages` when cells gained their [LayoutCell.instanceId]. A v1 payload has no
     * instance ids and can't be decoded into the current schema; a new key says so outright, instead
     * of leaning on [load]'s catch-all to quietly hand back the seeds. It also leaves the old value in
     * storage, so a layout from before the change is still there to read if it's ever wanted.
     */
    const val KEY = "vdt.pages.v2"
    val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Page.serializer())
  }
}

/**
 * The starter pages: the Vehicle and Farm dashboards, as ordinary user pages. Broadly the
 * arrangement these had when the grid was 3×2 — a fine grid is what lets a *widget* be small, not
 * what makes every tile small — with the extra room 12×7 gives spent on a dock of single-cell
 * shortcuts rather than on making the readout panels bigger than they need to be.
 *
 * Both arrangements are written out by hand. The portrait ones could have been left to [Page]'s
 * derived default, but the landscape Vehicle page is built around a *band* — front, where you are,
 * back, read across the top — and that idea does not survive being squeezed to half the width; it has
 * to become a stack. The same tiles, in the order you want them going down.
 *
 * Instance ids are shared between a page's two arrangements on purpose: the portrait "veh-map" is the
 * landscape "veh-map" seen the other way up, so it keeps its zoom, filters and layer selection when
 * the device turns. They are also hand-written and readable rather than generated — being stable
 * across installs makes them something you can name when reading a stored layout, which a random id
 * defeats.
 */
private fun shortcut(instanceId: String, appId: String, col: Int, row: Int) =
  LayoutCell(instanceId, ShortcutWidget.id, col, row, config = mapOf(ShortcutWidget.APP_KEY to appId))

private fun slot(instanceId: String, slot: RigSlot, col: Int, row: Int, colSpan: Int, rowSpan: Int) =
  LayoutCell(instanceId, RigSlotWidget.id, col, row, colSpan, rowSpan, mapOf(RigSlotWidget.SLOT_KEY to slot.name))

private fun seedPages(): List<Page> = listOf(
  Page(
    id = "vehicle",
    title = "Vehicle",
    icon = PageIcon.Tractor,
    autoShow = AutoShow.InVehicle,
    landscape =
    GridLayout(
      columns = GridLayout.COLUMNS,
      rows = GridLayout.ROWS,
      cells =
      listOf(
        // Top band, 4 rows: the rig laid out the way it sits — what's on the front, where you are,
        // what's on the back — with the map filling the space between the two ends.
        slot("veh-front", RigSlot.FRONT, col = 0, row = 0, colSpan = 2, rowSpan = 4),
        LayoutCell("veh-map", "map", col = 2, row = 0, colSpan = 8, rowSpan = 4),
        slot("veh-rear", RigSlot.REAR, col = 10, row = 0, colSpan = 2, rowSpan = 4),
        // Bottom band, 3 rows: the machine itself and its readouts, plus the dock. The vehicle slot
        // sits under the front one, so the three rig positions read down-then-across.
        slot("veh-self", RigSlot.VEHICLE, col = 0, row = 4, colSpan = 2, rowSpan = 3),
        LayoutCell("veh-engine", "engine", col = 2, row = 4, colSpan = 4, rowSpan = 3),
        LayoutCell("veh-lighting", "lighting", col = 6, row = 4, colSpan = 2, rowSpan = 3),
        // Heading / steering assist used to be permanent chrome in the bottom bar. It's a widget now,
        // so the starter page places it — otherwise a fresh install would simply lose it.
        LayoutCell("veh-navigation", "navigation", col = 8, row = 4, colSpan = 2, rowSpan = 3),
        // A 2×2 dock of single-cell shortcuts in the corner, showing what they're for: these four
        // apps contribute no widget of their own, so before this they were only reachable two taps
        // deep behind the launcher or by spending one of the four pinned slots on the bar.
        shortcut("veh-sc-production", "production", col = 10, row = 4),
        shortcut("veh-sc-storage", "storage", col = 11, row = 4),
        shortcut("veh-sc-animals", "animals", col = 10, row = 5),
        shortcut("veh-sc-diagnostics", "diagnostics", col = 11, row = 5),
      ),
    ),
    // Portrait: the same tiles as a stack, widest thing first. The rig band survives the turn — three
    // slots of two columns still read left-to-right as front, you, back — but everything above it
    // becomes full width, which is the one thing a narrow screen is actually good at.
    portrait =
    GridLayout(
      columns = GridLayout.PORTRAIT_COLUMNS,
      rows = GridLayout.PORTRAIT_ROWS,
      cells =
      listOf(
        LayoutCell("veh-map", "map", col = 0, row = 0, colSpan = 6, rowSpan = 4),
        LayoutCell("veh-engine", "engine", col = 0, row = 4, colSpan = 6, rowSpan = 3),
        slot("veh-front", RigSlot.FRONT, col = 0, row = 7, colSpan = 2, rowSpan = 3),
        slot("veh-self", RigSlot.VEHICLE, col = 2, row = 7, colSpan = 2, rowSpan = 3),
        slot("veh-rear", RigSlot.REAR, col = 4, row = 7, colSpan = 2, rowSpan = 3),
        // The last two rows carry what the bottom band carried, with the 2×2 shortcut dock keeping
        // its corner: four apps that contribute no widget, still one tap away.
        LayoutCell("veh-navigation", "navigation", col = 0, row = 10, colSpan = 2, rowSpan = 2),
        LayoutCell("veh-lighting", "lighting", col = 2, row = 10, colSpan = 2, rowSpan = 2),
        shortcut("veh-sc-production", "production", col = 4, row = 10),
        shortcut("veh-sc-storage", "storage", col = 5, row = 10),
        shortcut("veh-sc-animals", "animals", col = 4, row = 11),
        shortcut("veh-sc-diagnostics", "diagnostics", col = 5, row = 11),
      ),
    ),
  ),
  Page(
    id = "farm",
    title = "Farm",
    icon = PageIcon.Home,
    autoShow = AutoShow.OnFoot,
    landscape =
    GridLayout(
      columns = GridLayout.COLUMNS,
      rows = GridLayout.ROWS,
      cells =
      listOf(
        LayoutCell("farm-map", "map", col = 0, row = 0, colSpan = 8, rowSpan = 7),
        LayoutCell("farm-tasks", "tasks", col = 8, row = 0, colSpan = 4, rowSpan = 4),
        LayoutCell("farm-cropRotation", "cropRotation", col = 8, row = 4, colSpan = 4, rowSpan = 3),
      ),
    ),
    // Portrait: the map keeps the lion's share, but as the top half rather than the left two-thirds —
    // a map squeezed into a narrow column shows you a corridor, not a farm.
    portrait =
    GridLayout(
      columns = GridLayout.PORTRAIT_COLUMNS,
      rows = GridLayout.PORTRAIT_ROWS,
      cells =
      listOf(
        LayoutCell("farm-map", "map", col = 0, row = 0, colSpan = 6, rowSpan = 6),
        LayoutCell("farm-tasks", "tasks", col = 0, row = 6, colSpan = 6, rowSpan = 3),
        LayoutCell("farm-cropRotation", "cropRotation", col = 0, row = 9, colSpan = 6, rowSpan = 3),
      ),
    ),
  ),
  // The A-pillar cluster, seeded so the feature exists without the user assembling it. It is an
  // ordinary page — three ordinary widgets stacked in the order a tractor's own pillar display uses:
  // lamps at the top, the numbers you drive by in the middle, levels along the bottom.
  //
  // AutoShow.Never, unlike the other two. This is the page you pin a second device to
  // (`?display=pillar`, see DisplayStore), and a page that also grabbed the tablet whenever you
  // climbed into a cab would be fighting the Vehicle page for the screen you are actually holding.
  Page(
    id = "pillar",
    title = "Pillar",
    icon = PageIcon.Dashboard,
    autoShow = AutoShow.Never,
    // Landscape is the lesser of the two here — the cluster is a tall thing — so it lays the readout
    // and the levels side by side under the band rather than pretending to be a column.
    landscape =
    GridLayout(
      columns = GridLayout.COLUMNS,
      rows = GridLayout.ROWS,
      cells =
      listOf(
        LayoutCell("pillar-telltales", TelltaleWidget.id, col = 0, row = 0, colSpan = 12, rowSpan = 1),
        LayoutCell("pillar-readout", ClusterReadoutWidget.id, col = 0, row = 1, colSpan = 8, rowSpan = 6),
        LayoutCell("pillar-levels", ClusterLevelsWidget.id, col = 8, row = 1, colSpan = 4, rowSpan = 6),
      ),
    ),
    // Portrait is the one this page is for: a phone clamped to the pillar, top to bottom.
    portrait =
    GridLayout(
      columns = GridLayout.PORTRAIT_COLUMNS,
      rows = GridLayout.PORTRAIT_ROWS,
      cells =
      listOf(
        LayoutCell("pillar-telltales", TelltaleWidget.id, col = 0, row = 0, colSpan = 6, rowSpan = 2),
        LayoutCell("pillar-readout", ClusterReadoutWidget.id, col = 0, row = 2, colSpan = 6, rowSpan = 6),
        LayoutCell("pillar-levels", ClusterLevelsWidget.id, col = 0, row = 8, colSpan = 6, rowSpan = 4),
      ),
    ),
  ),
)
