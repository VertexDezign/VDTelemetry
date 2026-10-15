package net.vertexdezign.vdt.app.i18n

/** The Map app: the map's controls, its filter sheet and the field popup. */
interface MapStrings {
  val mapDescription: String
  val groundLayerDescription: String
  val player: String
  val imageUnavailable: String
  val filtersAndSearch: String
  val courseUp: String
  val followMachine: String
  val zoomIn: String
  val zoomOut: String

  /** The filter and search sheet. */
  val search: String
  fun field(name: String): String
  fun autoDriveHit(name: String): String
  val noMatches: String
  val fields: String
  val pois: String
  val autoDriveDestinations: String
  val contracts: String
  val guidanceCourse: String
  val vehicles: String
  val otherPlayers: String
  val groundLayer: String
  val none: String
  val resetFailed: String
  val clearing: String
  val tapAgain: String
  val resetCoverage: String
  val lines: String
  val allLines: String

  val poiUnloading: String
  val poiLoading: String
  val poiProduction: String
  val poiAnimals: String
  val poiOther: String
  val vehicleAi: String
  val vehiclePlayer: String
  val vehicleParked: String
  val layerCrops: String
  val layerGrowth: String
  val layerSoil: String

  /** The field popup. */
  val close: String
  val farmland: String
  val owner: String
  val area: String
  val crop: String
  val growth: String
  val stage: String
  val yieldBonus: String
  val fertilized: String
  val weeds: String
  val needsPlowing: String
  val needsLime: String
  val needsRolling: String
  val cropRotation: String
  val lastCrop: String
  val previousCrop: String
  val rotationYield: String
  val catchCrop: String
  val noCatchCrop: String
  val growing: String
  val readyToPrepare: String
  val readyToHarvest: String
  val cut: String
  val withered: String
  val notOwned: String
  val you: String
  fun farm(id: Int): String
}
