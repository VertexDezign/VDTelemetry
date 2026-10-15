package net.vertexdezign.vdt.app.i18n

/** The Market app: the stock table, the price board and their filters. */
interface MarketStrings {
  val tabStock: String
  val tabPrices: String
  val waitingBoard: String
  val trendClimbing: String
  val trendFalling: String
  val trendSteady: String

  /** What stock is in, and where it sits. */
  val formBulk: String
  val formBales: String
  val formPallets: String
  val formBigBags: String
  val formObjects: String
  val placeSilo: String
  val placeStore: String
  val placeBunker: String
  val placeOutside: String
  val placeProduction: String
  val placePen: String
  val noteStillFilling: String
  val noteFermenting: String
  val noteReady: String
  val noteOpen: String
  val noteAwaitingCollection: String
  fun noteBalesFermenting(count: Int): String

  /** Filter kinds and the categories under them. */
  val filterCategory: String
  val filterForm: String
  val filterWhere: String
  val filterCommodity: String
  val filterSellsTo: String
  val filterStation: String
  val categoryCrops: String
  val categoryProducts: String
  val categoryNoBuyer: String
  val categoryDemand: String
  val categoryFermenting: String
  val categorySoldHere: String
  val categoryBoughtHere: String
  val categoryPallets: String

  /** The stock table. */
  val waitingStock: String
  val holdsNothing: String
  val stockFilterHint: String
  val noBoard: String
  fun pricesPer1000(multiplier: String): String
  val columnName: String
  val columnType: String
  val columnAmount: String
  val columnPrice: String
  val columnValue: String
  val columnSellsTo: String
  val columnBestPrice: String
  val columnBestValue: String
  val columnMonth: String
  fun fermentingBadge(count: Int): String
  val noBuyer: String
  val demandBadge: String
  fun noStationBuys(title: String): String
  val flatYear: String
  fun places(count: Int): String

  /** The opened row's sentences. Figures arrive formatted. */
  fun bestOffer(price: String, station: String, trend: String): String
  fun greatDemand(premium: String, hoursLeft: Int?): String
  fun worth(value: String): String
  fun projection(month: String, gain: String, station: String, price: String, value: String): String
  fun total(rows: Int, unpriced: Int): String

  /** The price board. */
  val boardEmpty: String
  val boardFilterHint: String
  fun boardUnit(multiplier: String): String
  val commodities: String
  val selectCommodity: String
  val buyOnly: String
  val crop: String
  val reference: String
  val bestOfferLabel: String
  val noStationBuysThis: String
  val peakMonth: String
  fun marketAverage(price: String): String
  val noSeasonalMovement: String
  fun sellsTo(count: Int): String
  fun buyFrom(count: Int): String
  val palletsNote: String
  val nothingTrades: String
  val train: String
  val now: String
  val peak: String
}
