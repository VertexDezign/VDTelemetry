package net.vertexdezign.vdt.app.pages

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.app.layout.Axis
import net.vertexdezign.vdt.app.layout.Child
import net.vertexdezign.vdt.app.layout.Empty
import net.vertexdezign.vdt.app.layout.GridAspect
import net.vertexdezign.vdt.app.layout.LayoutNode
import net.vertexdezign.vdt.app.layout.Split
import net.vertexdezign.vdt.app.layout.Tile
import net.vertexdezign.vdt.app.layout.mapLeaves
import net.vertexdezign.vdt.app.layout.newEmptyId
import net.vertexdezign.vdt.app.layout.normalize
import net.vertexdezign.vdt.app.layout.tiles
import net.vertexdezign.vdt.app.panels.RigSlot
import net.vertexdezign.vdt.app.widgets.ClusterLevelsWidget
import net.vertexdezign.vdt.app.widgets.ClusterReadoutWidget
import net.vertexdezign.vdt.app.widgets.RigSlotWidget
import net.vertexdezign.vdt.app.widgets.ShortcutWidget
import net.vertexdezign.vdt.app.widgets.TelltaleWidget
import net.vertexdezign.vdt.app.widgets.Widget
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import net.vertexdezign.vdt.app.widgets.WidgetSettings
import kotlin.random.Random

/**
 * The user's [Page]s: the single source of truth for what pages exist and how each is laid out,
 * persisted to [Settings] as JSON on every change.
 *
 * On first run (or if the stored value is unreadable) the [seedPages] defaults are used, so the
 * terminal is useful out of the box; they are ordinary pages afterwards — editable and deletable
 * like any the user creates. Pages saved under the old cell grid are converted to split trees on
 * first load (see [migrateV2Pages]), and loading turns tiles whose widget is no longer registered
 * into empty space, so removing a widget from the code can't break a page a user saved earlier.
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
        landscape = Empty(newEmptyId()),
        portrait = Empty(newEmptyId()),
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
    pages.flatMapTo(mutableSetOf()) { page -> page.layouts.flatMap { layout -> layout.tiles.map { it.instanceId } } }

  /**
   * v3 if present; else the grid-era v2, converted and written back as v3 at once (so the random ids
   * of the empties it makes are settled, rather than minted afresh on every load until the first
   * edit); else the seeds. v2 is left in storage untouched, as v1 was.
   */
  private fun load(): List<Page> {
    settings.getStringOrNull(KEY)?.let { raw ->
      return runCatching { json.decodeFromString(ListSerializer, raw) }.getOrNull()?.map(::sanitize) ?: seedPages()
    }
    val legacy = settings.getStringOrNull(LEGACY_KEY) ?: return seedPages()
    val migrated =
      migrateV2Pages(legacy, json) { println("VDT: page layout migration: $it") }?.map(::sanitize) ?: return seedPages()
    settings.putString(KEY, json.encodeToString(ListSerializer, migrated))
    return migrated
  }

  /**
   * Brings a stored page up to date, one [GridAspect] at a time. Per arrangement, a tile whose widget
   * no longer exists, or whose instance id repeats one already seen, becomes an [Empty] in place —
   * not dropped, which on a tree would reflow its neighbours. The structure stays; the user fills or
   * closes the gap.
   *
   * The duplicate check guards the one invariant the rest of the app leans on: instance ids are
   * unique **within an arrangement**. The renderer keys its tiles by them, and Compose can't tell two
   * tiles apart under one key — a hand-edited or half-written file would otherwise show up as a tile
   * that misbehaves rather than as bad data. Across the two arrangements a repeat is not a duplicate
   * but the point: that is the same tile, seen in the other orientation.
   *
   * Size floors are not checked: they gate edits, never rendering (see [Widget.minWidth]).
   */
  private fun sanitize(page: Page): Page = GridAspect.entries.fold(page) { acc, aspect ->
    acc.withLayout(aspect, sanitize(acc.layoutFor(aspect)))
  }

  private fun sanitize(layout: LayoutNode): LayoutNode {
    val seen = mutableSetOf<String>()
    return layout
      .mapLeaves { leaf ->
        if (leaf is Tile && (WidgetRegistry.byId(leaf.widgetId) == null || !seen.add(leaf.instanceId))) {
          Empty(newEmptyId())
        } else {
          leaf
        }
      }.normalize()
  }

  private companion object {
    /**
     * Bumped from `vdt.pages.v2` when pages turned from cell grids into split trees. A new key rather
     * than an in-place rewrite, as the v1 → v2 bump was: the old value stays in storage, so a layout
     * from before the change is still there to read if the conversion ever gets one wrong.
     */
    const val KEY = "vdt.pages.v3"

    /** The cell-grid pages; read once, by [migrateV2Pages], when there is nothing under [KEY] yet. */
    const val LEGACY_KEY = "vdt.pages.v2"
    val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Page.serializer())
  }
}

/**
 * The starter pages: the Vehicle and Farm dashboards and the pillar cluster, as ordinary user pages.
 *
 * Written out as trees by hand, both arrangements, rather than run through the grid converter — so
 * they read as the arrangements they are. Weights are written as the twelfths/cell counts they were
 * on the old grid; [split] normalizes them.
 *
 * The portrait ones could have been left to [Page]'s flipped default, but the landscape Vehicle page
 * is built around a *band* — front, where you are, back, read across the top — and that idea does not
 * survive being squeezed to half the width; it has to become a stack. The same tiles, in the order you
 * want them going down.
 *
 * Instance ids are shared between a page's two arrangements on purpose: the portrait "veh-map" is the
 * landscape "veh-map" seen the other way up, so it keeps its zoom, filters and layer selection when
 * the device turns. They are also hand-written and readable rather than generated — being stable
 * across installs makes them something you can name when reading a stored layout, which a random id
 * defeats.
 */
private fun split(axis: Axis, vararg parts: Pair<Int, LayoutNode>): LayoutNode =
  Split(axis, parts.map { (weight, node) -> Child(weight.toFloat(), node) }).normalize()

private fun row(vararg parts: Pair<Int, LayoutNode>) = split(Axis.Row, *parts)

private fun column(vararg parts: Pair<Int, LayoutNode>) = split(Axis.Column, *parts)

private fun tile(instanceId: String, widgetId: String) = Tile(instanceId, widgetId)

private fun shortcut(instanceId: String, appId: String) =
  Tile(instanceId, ShortcutWidget.id, mapOf(ShortcutWidget.APP_KEY to appId))

private fun slot(instanceId: String, slot: RigSlot) =
  Tile(instanceId, RigSlotWidget.id, mapOf(RigSlotWidget.SLOT_KEY to slot.name))

/**
 * A 2×2 block of shortcuts to the four apps that contribute no widget of their own — before shortcuts
 * existed they were only reachable two taps deep behind the launcher or by spending one of the four
 * pinned slots on the bar.
 */
private fun dock() = column(
  1 to row(1 to shortcut("veh-sc-production", "production"), 1 to shortcut("veh-sc-storage", "storage")),
  1 to row(1 to shortcut("veh-sc-animals", "animals"), 1 to shortcut("veh-sc-diagnostics", "diagnostics")),
)

private fun seedPages(): List<Page> = listOf(
  Page(
    id = "vehicle",
    title = "Vehicle",
    icon = PageIcon.Tractor,
    autoShow = AutoShow.InVehicle,
    landscape =
    column(
      // Top band: the rig laid out the way it sits — what's on the front, where you are, what's on
      // the back — with the map filling the space between the two ends. Front and rear are the two
      // ends of one split, so they stay the same width whatever the map is dragged to.
      4 to row(2 to slot("veh-front", RigSlot.FRONT), 8 to tile("veh-map", "map"), 2 to slot("veh-rear", RigSlot.REAR)),
      // Bottom band: the machine itself and its readouts, plus the dock. The vehicle slot sits under
      // the front one, so the three rig positions read down-then-across. Navigation used to be
      // permanent chrome in the bottom bar; it's a widget now, so the starter page places it.
      3 to
        row(
          2 to slot("veh-self", RigSlot.VEHICLE),
          4 to tile("veh-engine", "engine"),
          2 to tile("veh-lighting", "lighting"),
          2 to tile("veh-navigation", "navigation"),
          2 to dock(),
        ),
    ),
    // Portrait: the same tiles as a stack, widest thing first. The rig band survives the turn — three
    // slots still read left-to-right as front, you, back — but everything above it becomes full width,
    // which is the one thing a narrow screen is actually good at.
    portrait =
    column(
      4 to tile("veh-map", "map"),
      3 to tile("veh-engine", "engine"),
      3 to
        row(
          1 to slot("veh-front", RigSlot.FRONT),
          1 to slot("veh-self", RigSlot.VEHICLE),
          1 to slot("veh-rear", RigSlot.REAR),
        ),
      2 to row(1 to tile("veh-navigation", "navigation"), 1 to tile("veh-lighting", "lighting"), 1 to dock()),
    ),
  ),
  Page(
    id = "farm",
    title = "Farm",
    icon = PageIcon.Home,
    autoShow = AutoShow.OnFoot,
    landscape =
    row(
      8 to tile("farm-map", "map"),
      4 to column(4 to tile("farm-tasks", "tasks"), 3 to tile("farm-cropRotation", "cropRotation")),
    ),
    // Portrait: the map keeps the lion's share, but as the top half rather than the left two-thirds —
    // a map squeezed into a narrow column shows you a corridor, not a farm.
    portrait =
    column(
      6 to tile("farm-map", "map"),
      3 to tile("farm-tasks", "tasks"),
      3 to tile("farm-cropRotation", "cropRotation"),
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
    column(
      1 to tile("pillar-telltales", TelltaleWidget.id),
      6 to row(
        8 to tile("pillar-readout", ClusterReadoutWidget.id),
        4 to tile("pillar-levels", ClusterLevelsWidget.id),
      ),
    ),
    // Portrait is the one this page is for: a phone clamped to the pillar, top to bottom.
    portrait =
    column(
      2 to tile("pillar-telltales", TelltaleWidget.id),
      6 to tile("pillar-readout", ClusterReadoutWidget.id),
      4 to tile("pillar-levels", ClusterLevelsWidget.id),
    ),
  ),
)
