package net.vertexdezign.vdt.app.i18n

/** Widget titles and settings, the layout editor, the seeded pages and the small shared components. */
interface WidgetsStrings {
  /** Widget titles the apps do not already name. */
  val shortcut: String
  val telltales: String
  val clusterReadout: String
  val service: String
  val levelStrip: String
  val rigPosition: String

  /** Their settings. */
  val opens: String
  val lamps: String
  val navigationStrip: String
  val sectionBar: String
  val position: String
  val machine: String
  val auto: String
  val off: String
  val on: String
  val unavailable: String

  /** The picker and the config dialog. */
  val addWidget: String
  val noWidgetFits: String
  val nothingToChoose: String
  val cancel: String
  fun addTitle(widget: String): String
  val add: String
  val save: String

  /** Editing a page. */
  val deletePageTitle: String
  fun deletePageMessage(page: String): String
  val delete: String
  fun icon(name: String): String
  val show: String
  val display: String
  val deletePage: String
  val showManual: String
  val showInVehicle: String
  val showOnFoot: String
  val dragToSwap: String
  val configureWidget: String
  val removeWidget: String
  val splitRight: String
  val splitBelow: String
  val addWidgetAction: String
  val closeEmpty: String
  val dragDivider: String
  val makeEqual: String
  fun addBand(edge: String): String
  val edgeTop: String
  val edgeBottom: String
  val edgeStart: String
  val edgeEnd: String

  /** The seeded pages' names, until the player renames them. */
  val pageVehicle: String
  val pageFarm: String
  val pagePillar: String
  val pageNew: String

  /** Small components. */
  val fill: String
  fun backTo(label: String): String
  val moreAbove: String
  val moreBelow: String
  val switchOn: String
  val switchOff: String
}
