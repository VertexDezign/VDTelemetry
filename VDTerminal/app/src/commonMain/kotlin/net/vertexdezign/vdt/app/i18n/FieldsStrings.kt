package net.vertexdezign.vdt.app.i18n

/** The Fields app: the field list, a field's detail, its tasks and the tile. */
interface FieldsStrings {
  val waitingMap: String
  val noFields: String
  val newTask: String
  val selectPrompt: String
  val search: String
  fun field(label: String): String

  /** The summary line: a figure, then what it counts. */
  val ownFields: String
  val hectares: String
  val readyToHarvest: String
  val needWork: String
  val forSale: String

  val viewAll: String
  val viewMine: String
  val viewForSale: String
  val viewNeedsWork: String
  val viewReady: String
  val viewContract: String
  val sortNumber: String
  val sortArea: String
  val sortCrop: String
  val sortStatus: String
  val sortPrice: String

  val sampling: String
  val badgeForSale: String
  val badgeOtherFarm: String
  val badgeContract: String
  val badgeContractOffer: String
  fun badgeTasks(count: Int): String
  fun onTheList(work: String): String
  val addTask: String

  /** The field's own line, and its detail sections. */
  val yours: String
  fun farm(id: Int): String
  fun perHectare(price: String): String
  val sectionGround: String
  val acrossField: String
  val atCentre: String
  val samplingGround: String
  val fewCellsMixed: String
  val fewCellsWhole: String
  val tooSmall: String
  val bareMulched: String
  val bare: String
  val sectionCrop: String
  val crop: String
  val none: String
  val mix: String
  val growth: String
  val yieldBonus: String
  val sectionAskingFor: String
  val noSowingWindow: String
  val sectionRotation: String
  val lastCrop: String
  val beforeThat: String
  val rotationYield: String
  val catchCrop: String
  val sectionTasks: String
  val dueNow: String
  val sectionContract: String
  val progress: String
  val forNpc: String
  val sectionCondition: String
  val needsPlowing: String
  val weeds: String
  fun weedsAtCentre(weed: String): String
  val mulched: String
  val samplingSoil: String
  val shareNone: String
  val shareWhole: String
  fun shareAtLeast(percent: Int): String

  /** The tile. */
  val noneOwned: String
  fun nothingOutstanding(count: Int): String
  fun needWorkCaps(count: Int): String
  fun more(count: Int): String

  /** A field's state in one word: the growth and crops breakdowns' vocabulary. */
  val cultivated: String
  val stubble: String
  val seedbed: String
  val plowed: String
  val growing: String
  val toppable: String
  val ready: String
  val cut: String
  val withered: String
  val planted: String
  val unknown: String
  val bareWord: String

  /** Field-task work: written into the TaskList detail ("F12 - Sow"), so parsing accepts every language. */
  val taskSow: String
  val taskFertilize: String
  val taskLime: String
  val taskPlow: String
  val taskCultivate: String
  val taskRoll: String
  val taskHarvest: String
  val taskWeed: String
  val taskSpray: String
  val taskMulch: String
  val taskStones: String
  val taskOther: String

  /** The alerts. */
  val alertWithered: String
  val alertHarvest: String
  fun alertFields(count: Int, names: String): String
}
