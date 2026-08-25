package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **prices** channel the mod writes to `prices.json` (separate file, 30 s
 * interval — see the mod's `src/collect/PricesExporter.lua`): the map's price board. One entry per
 * station placeable with what it pays for each fill type, what it charges for the ones it sells, and
 * the twelve-month price curve behind each commodity.
 *
 * It is the data the game's own "Prices" table draws, collected once instead of read station by
 * station in a menu.
 *
 * **Not farm-scoped, and carries no fill levels.** A price is the same number for every farm, and
 * what the local farm owns is already on the storage / husbandry / production channels — valuing
 * stock against this board is a join the app does ([StorageData] × [PricesData]), not a second stock
 * walk.
 *
 * **Price unit: currency per 1000 litres**, difficulty multiplier included — the unit the game
 * prints. The one exception is [PricesPallet.price], which is for a whole pallet. Valuing N litres
 * is therefore `N / 1000 * price`.
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults, so the mod can add fields ahead of the client.
 */
@Serializable
data class PricesData(
  val version: String = "",
  /** Current in-game period (1–12), for marking "now" on [PricesFillType.months]; 0 when unknown. */
  val period: Int = 0,
  /**
   * The economic-difficulty factor (3.0 easy / 1.8 normal / 1.0 hard) **already folded into** every
   * price here. Reported so the app can say which economy these numbers belong to, not so it can
   * apply it a second time.
   */
  val priceMultiplier: Float = 1f,
  /** One entry per fill type any station row names, so every row joins to exactly one. */
  val fillTypes: List<PricesFillType> = emptyList(),
  val stations: List<PricesStation> = emptyList(),
)

/** One station placeable, from the price board's point of view. */
@Serializable
data class PricesStation(
  /**
   * Stable id — the placeable's uniqueId, else a synthesized fallback. Note the multiplayer caveat
   * that applies to every placeable id in this project: a **client mints its own uniqueId per
   * session**, so the value is stable while connected but differs from the host's and changes on
   * rejoin. Fine for selection and joining within one session, not a key to persist.
   */
  val id: String = "",
  val name: String = "",
  /** Normalized `[0,1]` map coordinates, the same frame as [MapPoi]; null when unresolvable. */
  val posX: Float? = null,
  val posZ: Float? = null,
  /** Reachable only by train — the game flags these because you cannot simply drive there. */
  val isTrainStation: Boolean = false,
  /** Sells pallets over the counter (see [pallets]). */
  val isPalletStation: Boolean = false,
  /** What the station buys **from** the player, by the litre. */
  val sell: List<PricesSell> = emptyList(),
  /** What the station sells **to** the player by the litre — diesel, seed, fertilizer, lime, water. */
  val buy: List<PricesBuy> = emptyList(),
  /** What the station sells **to** the player by the pallet. */
  val pallets: List<PricesPallet> = emptyList(),
)

/** What one station pays for one fill type, per 1000 litres. */
@Serializable
data class PricesSell(
  /** Fill type internal name; joins to [PricesFillType.type]. */
  val type: String = "",
  /** Effective price right now, currency per 1000 l (difficulty and season already applied). */
  val price: Float = 0f,
  /**
   * Where the price is heading: `climbing`, `falling`, `steady` — the game's own arrow, derived from
   * the seasonal curve a few hours ahead. A string token rather than an enum so an unknown value
   * renders neutrally instead of breaking the parse.
   *
   * Independent of [greatDemand]: a commodity in great demand is still climbing or falling.
   */
  val trend: String = "steady",
  /** True while this station × fill type is the running great-demand pairing. */
  val greatDemand: Boolean = false,
  /** The great demand's price factor (1.1–1.4). Null unless [greatDemand]. */
  val demandMultiplier: Float? = null,
  /** In-game hours the great demand still runs. Null unless [greatDemand]. */
  val demandHoursLeft: Int? = null,
)

/** What one station charges for one fill type, per 1000 litres. No dynamics, so no trend. */
@Serializable
data class PricesBuy(
  /** Fill type internal name; joins to [PricesFillType.type]. */
  val type: String = "",
  val price: Float = 0f,
)

/** One pallet a counter shop sells. */
@Serializable
data class PricesPallet(
  /** Fill type internal name; joins to [PricesFillType.type]. */
  val type: String = "",
  /** Price for the **whole pallet**, not per 1000 l. */
  val price: Float = 0f,
)

/** One commodity on the board — the properties that belong to the fill type rather than a station. */
@Serializable
data class PricesFillType(
  /** Fill type internal name, the stable token (e.g. `WHEAT`). */
  val type: String = "",
  val title: String = "",
  /** Reference price per 1000 l at seasonal factor 1 — not a live price, and no station pays it. */
  val basePrice: Float = 0f,
  /** A harvestable fruit rather than a produced good — the split the game's own table makes. */
  val isCrop: Boolean = false,
  /** Listed by the game's own prices menu; a natural default filter for a commodity list. */
  val showOnPriceTable: Boolean = false,
  /**
   * The twelve monthly prices per 1000 l, index 0 = period 1. This is the game's running per-period
   * average (`fillType.economy.history`, seeded from the seasonal factors and re-averaged hourly) —
   * what its fluctuation graph plots — so it reflects the prices this save has actually seen, not a
   * static curve. Empty when the fill type has no economy.
   */
  val months: List<Float> = emptyList(),
  /** Period (1–12) of the highest entry in [months]; 0 when there is no curve. */
  val bestMonth: Int = 0,
  /** That highest entry, currency per 1000 l; 0 when there is no curve. */
  val bestPrice: Float = 0f,
)
