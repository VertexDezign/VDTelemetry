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
 * removing a widget from the code can't break a page a user saved earlier.
 */
class PageStore(private val settings: Settings) {
  private val json = Json { ignoreUnknownKeys = true }

  private val _pages = MutableStateFlow(load())
  val pages: StateFlow<List<Page>> = _pages.asStateFlow()

  /** Replaces the page with [page]'s id; no-op if it's gone (e.g. deleted in another tab). */
  fun update(page: Page) = persist(_pages.value.map { if (it.id == page.id) page else it })

  fun remove(id: String) = persist(_pages.value.filterNot { it.id == id })

  /** Appends a fresh empty page and returns it, so the caller can open it. */
  fun create(): Page {
    val page =
      Page(
        id = "page-" + Random.nextLong(0, Long.MAX_VALUE).toString(36),
        title = "New Page",
        icon = PageIcon.Grid,
        autoShow = AutoShow.Never,
        layout = GridLayout(columns = 3, rows = 2, cells = emptyList()),
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

  private fun sanitize(page: Page): Page =
    page.copy(layout = page.layout.copy(cells = page.layout.cells.filter { WidgetRegistry.byId(it.widgetId) != null }))

  private companion object {
    const val KEY = "vdt.pages"
    val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Page.serializer())
  }
}

/** The starter pages: today's Vehicle and Farm dashboards, reproduced as ordinary user pages. */
private fun seedPages(): List<Page> = listOf(
  Page(
    id = "vehicle",
    title = "Vehicle",
    icon = PageIcon.Tractor,
    autoShow = AutoShow.InVehicle,
    layout =
    GridLayout(
      columns = 3,
      rows = 2,
      cells =
      listOf(
        LayoutCell("map", col = 0, row = 0),
        LayoutCell("engine", col = 1, row = 0),
        LayoutCell("implements", col = 2, row = 0),
        LayoutCell("lighting", col = 0, row = 1),
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
      columns = 3,
      rows = 2,
      cells =
      listOf(
        LayoutCell("map", col = 0, row = 0, colSpan = 2, rowSpan = 2),
        LayoutCell("tasks", col = 2, row = 0),
        LayoutCell("cropRotation", col = 2, row = 1),
      ),
    ),
  ),
)
