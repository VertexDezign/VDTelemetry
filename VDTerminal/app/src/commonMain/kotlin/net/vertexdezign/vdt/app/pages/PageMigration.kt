package net.vertexdezign.vdt.app.pages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.app.layout.GridPlacement
import net.vertexdezign.vdt.app.layout.LayoutNode
import net.vertexdezign.vdt.app.layout.Tile
import net.vertexdezign.vdt.app.layout.gridToTree
import net.vertexdezign.vdt.app.widgets.WidgetConfig

/**
 * Reads pages stored under the cell grid (`vdt.pages.v2`) and converts each arrangement to a split
 * tree — see [gridToTree]. Returns null if [raw] isn't a readable v2 payload.
 *
 * The v2 shape is declared here, privately, rather than borrowed from the grid classes: this has to
 * keep reading old storage after the grid code itself is gone.
 *
 * A v2 page stored before portrait arrangements existed has none; the grid then showed its landscape
 * layout rescaled onto the portrait grid, which as ratios is the landscape tree itself — so that is
 * what it gets, not [Page]'s flipped default, which would rearrange a page the user already knew.
 */
internal fun migrateV2Pages(raw: String, json: Json, onLossy: (String) -> Unit = {}): List<Page>? {
  val stored =
    runCatching { json.decodeFromString(ListSerializer(V2Page.serializer()), raw) }.getOrNull() ?: return null
  return stored.map { page ->
    val landscape = page.layout.toTree { onLossy("page ${page.id}, landscape: $it") }
    Page(
      id = page.id,
      title = page.title,
      icon = page.icon,
      autoShow = page.autoShow,
      landscape = landscape,
      portrait = page.portrait?.toTree { onLossy("page ${page.id}, portrait: $it") } ?: landscape,
    )
  }
}

@Serializable
private data class V2Page(
  val id: String,
  val title: String,
  val icon: PageIcon,
  val autoShow: AutoShow,
  @SerialName("layout") val layout: V2Layout,
  val portrait: V2Layout? = null,
)

@Serializable
private data class V2Layout(val columns: Int, val rows: Int, val cells: List<V2Cell>) {
  fun toTree(onLossy: (String) -> Unit): LayoutNode = gridToTree(
    columns,
    rows,
    cells.map { GridPlacement(Tile(it.instanceId, it.widgetId, it.config), it.col, it.row, it.colSpan, it.rowSpan) },
    onLossy = onLossy,
  )
}

@Serializable
private data class V2Cell(
  val instanceId: String,
  val widgetId: String,
  val col: Int,
  val row: Int,
  val colSpan: Int = 1,
  val rowSpan: Int = 1,
  val config: WidgetConfig = emptyMap(),
)
