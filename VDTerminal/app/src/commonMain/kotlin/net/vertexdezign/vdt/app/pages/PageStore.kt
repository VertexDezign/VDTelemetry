package net.vertexdezign.vdt.app.pages

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.app.layout.GridLayout
import net.vertexdezign.vdt.app.layout.LayoutCell
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
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
        layout = GridLayout.empty(),
      )
    persist(_pages.value + page)
    return page
  }

  private fun persist(list: List<Page>) {
    _pages.value = list
    settings.putString(KEY, json.encodeToString(ListSerializer, list))
  }

  private fun load(): List<Page> {
    val raw = settings.getStringOrNull(KEY) ?: return seedPages()
    return runCatching { json.decodeFromString(ListSerializer, raw) }
      .getOrNull()
      ?.map(::sanitize)
      ?: seedPages()
  }

  /**
   * Brings a stored page up to date: drops cells whose widget no longer exists, then re-expresses the
   * layout on the current grid. Pages saved before the grid was frozen carry their own dimensions, so
   * a 3×2 arrangement scales up to 12×6 rather than being read as a corner of it.
   *
   * Scaling doesn't know about widget floors — a 1×1 on an old 6×6 page lands as 2×1, under the
   * minimum most widgets now declare — so each undersized tile is then grown back to its floor where
   * there's room. Best effort: [GridLayout.resize] refuses anything that would collide or overrun, so
   * a tile hemmed in by its neighbours simply stays small until the user rearranges the page.
   */
  private fun sanitize(page: Page): Page {
    val known = page.layout.cells.filter { WidgetRegistry.byId(it.widgetId) != null }
    var layout = page.layout.copy(cells = known).rescaledTo(GridLayout.COLUMNS, GridLayout.ROWS)
    for (cell in layout.cells.toList()) {
      val widget = WidgetRegistry.byId(cell.widgetId) ?: continue
      if (cell.colSpan >= widget.minColSpan && cell.rowSpan >= widget.minRowSpan) continue
      layout =
        layout.resize(
          cell,
          colSpan = maxOf(cell.colSpan, widget.minColSpan),
          rowSpan = maxOf(cell.rowSpan, widget.minRowSpan),
          minColSpan = widget.minColSpan,
          minRowSpan = widget.minRowSpan,
        )
    }
    return page.copy(layout = layout)
  }

  private companion object {
    const val KEY = "vdt.pages"
    val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Page.serializer())
  }
}

/**
 * The starter pages: the Vehicle and Farm dashboards, as ordinary user pages. Broadly the
 * arrangement these had when the grid was 3×2 — a fine grid is what lets a *widget* be small, not
 * what makes every tile small.
 */
private fun seedPages(): List<Page> = listOf(
  Page(
    id = "vehicle",
    title = "Vehicle",
    icon = PageIcon.Tractor,
    autoShow = AutoShow.InVehicle,
    layout =
    GridLayout(
      columns = GridLayout.COLUMNS,
      rows = GridLayout.ROWS,
      cells =
      listOf(
        // Top band, 4 rows: the three readouts you watch while driving.
        LayoutCell("map", col = 0, row = 0, colSpan = 4, rowSpan = 4),
        LayoutCell("engine", col = 4, row = 0, colSpan = 4, rowSpan = 4),
        LayoutCell("implements", col = 8, row = 0, colSpan = 4, rowSpan = 4),
        // Bottom band, 3 rows.
        LayoutCell("lighting", col = 0, row = 4, colSpan = 5, rowSpan = 3),
        // Heading / steering assist used to be permanent chrome in the bottom bar. It's a widget now,
        // so the starter page places it — otherwise a fresh install would simply lose it.
        LayoutCell("navigation", col = 5, row = 4, colSpan = 5, rowSpan = 3),
      ),
    ),
  ),
  Page(
    id = "farm",
    title = "Farm",
    icon = PageIcon.Home,
    autoShow = AutoShow.OnFoot,
    layout =
    GridLayout(
      columns = GridLayout.COLUMNS,
      rows = GridLayout.ROWS,
      cells =
      listOf(
        LayoutCell("map", col = 0, row = 0, colSpan = 8, rowSpan = 7),
        LayoutCell("tasks", col = 8, row = 0, colSpan = 4, rowSpan = 4),
        LayoutCell("cropRotation", col = 8, row = 4, colSpan = 4, rowSpan = 3),
      ),
    ),
  ),
)
