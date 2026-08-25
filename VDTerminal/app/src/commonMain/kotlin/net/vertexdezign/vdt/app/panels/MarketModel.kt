package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.components.FilterOption
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.PricesFillType
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.StorageData

// The Market app's model side, as pure functions: what the farm holds, gathered from the four channels
// that report holdings, what it is worth against the price board, and the vocabularies the two tabs
// filter by.
//
// Nothing here draws. The panels are tables over these rows, and keeping the arithmetic out of the
// composables is what lets the projection below be tested against a real capture rather than eyeballed
// on screen.
//
// Everything keys on the FILL TYPE, never on a placeable id -- which is what makes the whole join
// work unchanged on a multiplayer client, where a client mints its own uniqueId per session. Ids
// appear here only as the names inside a row's sources, where they are the placeable's name and not
// its identity.

/**
 * What form part of a holding takes — a pallet, a bale, a liter store.
 *
 * It describes a **source**, not a row: seed on a pallet and seed in a big bag are one commodity and
 * one line of the table, because "how much seed have I got" has one answer. Which containers that
 * answer is spread across is a property of where the stock sits, so it belongs on [StockSource].
 *
 * [BULK] is any liter store (silo, bunker, production buffer, the liters a pen holds); the other three
 * are the object vocabulary [net.vertexdezign.vdt.model.StoredObject.kind] and
 * [net.vertexdezign.vdt.model.LooseStock.kind] share. [OBJECT] is the fallback for an object group the
 * mod could not name — it is still countable stock, and folding it into [BULK] would print a count as
 * a liter figure.
 */
enum class StockForm(val label: String) {
  BULK("Bulk"),
  BALE("Bales"),
  PALLET("Pallets"),
  BIGBAG("Big bags"),
  OBJECT("Objects"),
  ;

  companion object {
    fun fromKind(kind: String): StockForm = when (kind.uppercase()) {
      "BALE" -> BALE
      "PALLET" -> PALLET
      "BIGBAG" -> BIGBAG
      else -> OBJECT
    }
  }
}

/** Where one part of a holding sits — the drill-in a row opens. */
enum class StockPlace(val label: String) {
  SILO("Silo"),
  STORE("Object storage"),
  BUNKER("Bunker"),
  OUTSIDE("Outside"),
  PRODUCTION("Production"),
  PEN("Animal pen"),
}

/**
 * One place a row's stock is, in one form, with how much of it is there. [note] carries the one thing
 * about that place a reader would otherwise have to go and look at — a bunker that is still closed,
 * bales still fermenting, a pen's output waiting for collection.
 */
data class StockSource(
  val place: StockPlace,
  /** Placeable name; empty for [StockPlace.OUTSIDE], which is not a place but the absence of one. */
  val name: String,
  val form: StockForm,
  val liters: Int,
  /** Objects, where they are counted; 0 for a liter store. */
  val count: Int = 0,
  /**
   * What this store holds when it is full, so a source line can say 30,000 of 100,000 rather than
   * 30,000 of nothing stated. **0 where there is no such number**, which is most of them: a bunker's
   * ceiling is the shape of its walls, a pen's condition bars carry no capacity, and bales in a field
   * have none by definition.
   */
  val capacity: Int = 0,
  val note: String = "",
)

/**
 * One line of the stock table: **one commodity**, wherever on the farm it is and whatever it is in.
 *
 * Everything merges into it — the silo, the bunker, the bales in the barn, the pallets in the rack,
 * the buffer inside the production point — because the question the table answers is what the farm
 * owns, and the board prices liters without caring what they arrived in. The split by container lives
 * in [sources], which is what a row opens to show.
 */
data class StockRow(
  /** Stable identity for selection: the fill type, or the title for stock that has none. */
  val key: String,
  /** Fill type token; empty for stock the game gives no fill type at all (a crate, a vegetable pallet). */
  val type: String,
  val title: String,
  val liters: Int,
  /** Objects across every source; 0 when all of it is bulk. */
  val count: Int,
  /**
   * Bales still becoming something else. A fermenting bale reports the fill type it went in as, so
   * this row prices grass that will be silage in a day — the count is carried so the table can say so
   * rather than quietly mis-price it (see [net.vertexdezign.vdt.model.LooseStock.fermenting]).
   */
  val fermenting: Int,
  val sources: List<StockSource>,
) {
  /** The forms this stock is in, in enum order — the table's "Type" column. */
  val forms: List<StockForm> get() = sources.map { it.form }.distinct().sortedBy { it.ordinal }
}

/** The best offer on the board for one fill type: who pays most for it right now, and how that is moving. */
data class BestSale(
  /** Currency per 1000 l, the board's unit — great demand already folded in by the game. */
  val price: Float,
  val station: String,
  val trend: String,
  val greatDemand: Boolean,
  /** The demand's premium (1.1–1.4) and how long it runs; null when the client has only the flag. */
  val demandMultiplier: Float? = null,
  val demandHoursLeft: Int? = null,
)

/**
 * A row priced against the board.
 *
 * [bestPrice] is a **projection, not a quote**: the twelve-month curve the mod exports is the game's
 * own `economy.history`, which is the average effective price across every station that accepts the
 * fill type — so its peak is what the average station will pay in that month, not what the best one
 * will. Printing it beside [BestSale.price] would routinely show a "maximum" below today's price. What
 * is projected instead is today's best station along the curve's shape:
 * `price × months[best] / months[now]`, which keeps the two columns comparable and keeps the claim
 * honest: the shape is the market's, the level is this station's.
 *
 * Null throughout when the commodity has no curve, or a flat one — a fill type nobody buys still has
 * twelve identical numbers in its history, and picking a "best month" out of twelve equal ones is a
 * lie the table would print with a straight face.
 */
data class StockValuation(
  val sale: BestSale?,
  /** Currency at [BestSale.price] for the whole holding; null when nothing buys this. */
  val value: Float?,
  /** Period 1..12 the curve peaks in; 0 when there is no projection. */
  val bestMonth: Int,
  val bestPrice: Float?,
  val bestValue: Float?,
)

/** Liters priced at a per-1000-litre board price. */
fun valueOf(liters: Int, pricePer1000: Float): Float = liters / 1000f * pricePer1000

/**
 * Best offer per fill type across the whole board. Ties break on the station name so the column does
 * not flip between two stations paying the same on every 30 s write.
 */
fun bestSales(prices: PricesData?): Map<String, BestSale> {
  val best = mutableMapOf<String, BestSale>()
  prices?.stations?.forEach { station ->
    station.sell.forEach { row ->
      val current = best[row.type]
      val better = current == null ||
        row.price > current.price ||
        (row.price == current.price && station.name < current.station)
      if (better) {
        best[row.type] =
          BestSale(row.price, station.name, row.trend, row.greatDemand, row.demandMultiplier, row.demandHoursLeft)
      }
    }
  }
  return best
}

/**
 * Below this relative spread the curve counts as flat and no best month is claimed. A fill type no
 * selling station accepts is never re-averaged by the game, so its history stays at twelve copies of
 * the seed — and the ones that *are* traded move by tens of percent, well clear of this.
 */
private const val FLAT_CURVE = 1.01f

/** The twelve periods the game's year and its price history are both fixed at. */
private const val MONTHS = 12

/**
 * The period a commodity's curve peaks in, or **0 when there is no peak worth naming**: no curve at
 * all, or a flat one. A fill type no selling station accepts is never re-averaged by the game, so its
 * history stays at twelve copies of the seed — and calling the first of twelve equal numbers a "best
 * month" would put a confident label on noise.
 *
 * The mod exports its own [PricesFillType.bestMonth], and it is checked here rather than trusted: it
 * is derived from the same twelve numbers, and a disagreement would put the label on the wrong bar of
 * the chart beside it.
 *
 * **Expect this to sit a month either side of a table built from the seasonal factors**, and do not
 * treat that as a bug. The curve here is `economy.history` — what stations were *observed* paying —
 * while a fill type's `economy.factors` is the shape the game aims at, and the two are offset by
 * construction: `EconomyManager:getFillTypeSeasonalFactor` interpolates across `factors[P]` and
 * `factors[P + 1]` as a period runs, so a period's observed average is a blend of two factors rather
 * than one. Where the peak's neighbours are close, the blend puts the observed maximum in the
 * neighbouring period. Observed is the answer worth printing — it is what the game's own fluctuation
 * graph plots, and it is what somebody waiting for the peak will actually be paid.
 */
fun peakMonth(fillType: PricesFillType?): Int {
  val months = fillType?.months.orEmpty()
  if (months.size < MONTHS) return 0
  val peak = months.max()
  if (peak <= months.min() * FLAT_CURVE) return 0
  return fillType!!.bestMonth.takeIf { it in 1..MONTHS && months[it - 1] == peak }
    ?: (months.indexOf(peak) + 1)
}

/** Value one row against the board. [period] is the game's current period (1..12), 0 when unknown. */
fun valuate(row: StockRow, sale: BestSale?, fillType: PricesFillType?, period: Int): StockValuation {
  if (sale == null) return StockValuation(null, null, 0, null, null)
  val value = valueOf(row.liters, sale.price)
  val bestMonth = peakMonth(fillType)
  if (bestMonth == 0 || period !in 1..MONTHS) return StockValuation(sale, value, 0, null, null)
  val months = fillType!!.months
  val now = months[period - 1]
  if (now <= 0f) return StockValuation(sale, value, 0, null, null)
  val projected = sale.price * months[bestMonth - 1] / now
  return StockValuation(sale, value, bestMonth, projected, valueOf(row.liters, projected))
}

/**
 * Everything the farm holds, gathered from the four channels that report holdings, as one list of
 * rows. Sorted by liters descending, ties on the title, so the list is total and does not reshuffle
 * under the reader on the next write.
 *
 * The channels are disjoint by construction, which is the only reason this can add them up: a bale put
 * away in an object storage is not in [StorageData.looseBales], and a manure heap wired into a
 * husbandry is left out of [StorageData.storages] because the pen already reports its liters. Each of
 * those exclusions lives in the mod, and this function relies on all of them.
 *
 * [prices] is consulted only for a commodity's proper title: an object group's own title is the game's
 * dialog text with the liter count baked into it ("Rundballen (Heu 5.000 l)"), which cannot be a group
 * name. Valuation is [valuate]'s job, per row.
 */
fun buildStock(
  storage: StorageData?,
  production: ProductionData?,
  husbandry: HusbandriesData?,
  prices: PricesData?,
): List<StockRow> {
  val titles = prices?.fillTypes.orEmpty().associate { it.type to it.title }
  val builder = StockBuilder(titles)

  storage?.storages?.forEach { place ->
    if (place.kind == "object") {
      place.objects.forEach { obj ->
        builder.add(
          type = obj.type,
          // Weak on purpose: a stored group's title is the game's dialog text with the liter count
          // baked into it ("Rundballen (Gras 3.500 l)"), which cannot name a row that merges groups.
          fallbackTitle = obj.title,
          strongTitle = false,
          form = StockForm.fromKind(obj.kind),
          liters = obj.level * obj.count,
          count = obj.count,
          source = StockPlace.STORE to place.name,
        )
      }
    } else {
      place.fills.forEach { fill ->
        builder.add(
          type = fill.type,
          fallbackTitle = fill.title,
          form = StockForm.BULK,
          liters = fill.level,
          count = 0,
          capacity = fill.capacity,
          source = StockPlace.SILO to place.name,
        )
      }
    }
  }

  storage?.bunkerSilos?.forEach { bunker ->
    builder.add(
      type = bunker.type,
      fallbackTitle = bunker.title,
      form = StockForm.BULK,
      liters = bunker.level,
      count = 0,
      source = StockPlace.BUNKER to bunker.name,
      note = bunkerNote(bunker.state),
    )
  }

  (storage?.looseBales.orEmpty() + storage?.loosePallets.orEmpty()).forEach { loose ->
    builder.add(
      type = loose.type,
      fallbackTitle = loose.title,
      form = StockForm.fromKind(loose.kind),
      liters = loose.level,
      count = loose.count,
      source = StockPlace.OUTSIDE to "",
      note = if (loose.fermenting > 0) "${loose.fermenting} fermenting" else "",
      fermenting = loose.fermenting,
    )
  }

  production?.productionPoints?.forEach { point ->
    // **Outputs only.** A production point's storage is one shared store holding both what has been
    // delivered in and what has been made, but only the made half is stock: the loading station a
    // point is built around is authored for its outputs (the game logs a warning when an output is
    // missing from it), so the input liters are on their way into a machine rather than something a
    // trailer can come and fetch. A fill type that is both — the game allows a chain to consume what
    // it also makes — is in the set and counts.
    val outputs = point.lines.flatMap { line -> line.outputs.map { it.type } }.toSet()
    point.storage.filter { it.type in outputs }.forEach { fill ->
      builder.add(
        type = fill.type,
        fallbackTitle = fill.title,
        form = StockForm.BULK,
        liters = fill.level,
        count = 0,
        capacity = fill.capacity,
        source = StockPlace.PRODUCTION to point.name,
      )
    }
  }

  husbandry?.husbandries?.forEach { pen ->
    // Condition bars only, carrying a fill type, and **inverted** — the same outputs-only rule the
    // production points follow. A food bar is a *group* summed over several fill types and a bar with
    // no type is not liters anybody owns (see HusbandryCondition); of the rest, `inverted` is the
    // game's own flag for the bars that fill up waiting to be taken away (milk, manure, slurry, wool).
    // Straw and water are the pen's inputs — `PlaceableHusbandryStraw` sets `invertedBar = false` on
    // the straw it eats and `true` on the manure it makes — and an input is something you bought and
    // fed to an animal, not stock you can sell back.
    pen.conditions.filter { it.type.isNotBlank() && it.inverted }.forEach { condition ->
      builder.add(
        type = condition.type,
        fallbackTitle = condition.title,
        form = StockForm.BULK,
        liters = condition.value,
        count = 0,
        source = StockPlace.PEN to pen.name,
        note = "awaiting collection",
      )
    }
  }

  return builder.rows()
}

private fun bunkerNote(state: String): String = when (state) {
  "FILL" -> "still filling"
  "CLOSED" -> "fermenting"
  "FERMENTED" -> "ready"
  "DRAIN" -> "open"
  else -> ""
}

/** Accumulates the four walks into one row per commodity. */
private class StockBuilder(private val titles: Map<String, String>) {
  private val byKey = linkedMapOf<String, StockRow>()

  /** Rows currently named by an object group's dialog text, which a fill type's own title may replace. */
  private val weakTitles = mutableSetOf<String>()

  fun add(
    type: String,
    fallbackTitle: String,
    form: StockForm,
    liters: Int,
    count: Int,
    source: Pair<StockPlace, String>,
    capacity: Int = 0,
    note: String = "",
    fermenting: Int = 0,
    /** Whether [fallbackTitle] is the fill type's own title, and so fit to name the whole row. */
    strongTitle: Boolean = true,
  ) {
    // An empty store is not stock. Objects are kept on their count alone: a crate holds no fill type
    // and therefore no liters, and dropping it would lose stock the farm can see in the yard.
    if (liters <= 0 && count <= 0) return
    // A type-less group has only its own title to be identified by, so that is what it groups on.
    val key = if (type.isNotBlank()) type else "?$fallbackTitle"
    val catalogue = titles[type]?.takeIf { it.isNotBlank() }
    val title = catalogue ?: fallbackTitle.ifBlank { type }
    val strong = catalogue != null || strongTitle
    // The first source to arrive names the row, but a weak name gives way to a proper one later: with
    // no price board to look a title up in, whether a row reads "Gras" or "Rundballen (Gras 3.500 l)"
    // would otherwise depend on which of the two walks happened to reach it first.
    var row = byKey[key]
    if (row == null) {
      row = StockRow(key, type, title, 0, 0, 0, emptyList())
      if (!strong) weakTitles += key
    } else if (strong && key in weakTitles) {
      row = row.copy(title = title)
      weakTitles -= key
    }
    byKey[key] = row.copy(
      liters = row.liters + liters,
      count = row.count + count,
      fermenting = row.fermenting + fermenting,
      sources = row.sources.merge(
        StockSource(source.first, source.second, form, liters, count, capacity, note),
      ),
    )
  }

  /**
   * Fold a source into the list, adding it to the matching one if there is one. One object storage can
   * hold two groups of the same commodity in the same form — round bales of grass at two fill levels
   * are two groups to the game — and listing that building twice under one row would read as two
   * buildings.
   */
  private fun List<StockSource>.merge(source: StockSource): List<StockSource> {
    val index = indexOfFirst {
      it.place == source.place && it.name == source.name && it.form == source.form && it.note == source.note
    }
    if (index < 0) return this + source
    val existing = this[index]
    return toMutableList().also {
      it[index] = existing.copy(
        liters = existing.liters + source.liters,
        count = existing.count + source.count,
        capacity = existing.capacity + source.capacity,
      )
    }
  }

  fun rows(): List<StockRow> = byKey.values.sortedWith(
    compareByDescending<StockRow> { it.liters }.thenBy { it.title.lowercase() },
  )
}

// ---- The table's own list operations --------------------------------------------------------------

/** A row with its valuation attached — what the table actually lists. */
data class StockEntry(val row: StockRow, val value: StockValuation)

/** Price every row against the board in one pass. */
fun valuateAll(rows: List<StockRow>, prices: PricesData?): List<StockEntry> {
  val sales = bestSales(prices)
  val fillTypes = prices?.fillTypes.orEmpty().associateBy { it.type }
  val period = prices?.period ?: 0
  return rows.map { row -> StockEntry(row, valuate(row, sales[row.type], fillTypes[row.type], period)) }
}

/** How the table is ordered — one per sortable column. */
enum class StockSort(val label: String) {
  NAME("Name"),
  FORM("Type"),
  AMOUNT("Amount"),
  PRICE("Price"),
  VALUE("Value"),
  STATION("Station"),
  BEST_PRICE("Best price"),
  BEST_VALUE("Best value"),
  MONTH("Best month"),
}

/** The kinds of thing the stock table can be filtered by. Same kind ORs, different kinds AND. */
object StockFilter {
  const val CATEGORY: String = "Category"
  const val FORM: String = "Form"
  const val PLACE: String = "Where"
  const val COMMODITY: String = "Commodity"
  const val STATION: String = "Sells to"

  const val CROPS: String = "Crops"
  const val PRODUCTS: String = "Products"
  const val UNPRICED: String = "No buyer"
  const val DEMAND: String = "Great demand"
  const val FERMENTING: String = "Fermenting"
}

/**
 * What this stock can be filtered by, built from what is actually in it: the categories that have a
 * member, the forms and places it is in, every commodity by name, every station that would buy one.
 *
 * Nothing is offered that can only come back empty — the same rule the app's chip rows follow, applied
 * to a vocabulary that is far too long to spell out as chips.
 */
fun stockOptions(entries: List<StockEntry>, crops: Set<String>): List<FilterOption> = buildList {
  fun category(label: String, present: Boolean) {
    if (present) add(FilterOption(StockFilter.CATEGORY, label))
  }
  category(StockFilter.CROPS, entries.any { it.row.type in crops })
  category(StockFilter.PRODUCTS, entries.any { it.value.sale != null && it.row.type !in crops })
  category(StockFilter.UNPRICED, entries.any { it.value.sale == null })
  category(StockFilter.DEMAND, entries.any { it.value.sale?.greatDemand == true })
  category(StockFilter.FERMENTING, entries.any { it.row.fermenting > 0 })

  StockForm.entries
    .filter { form -> entries.any { entry -> entry.row.forms.contains(form) } }
    .forEach { add(FilterOption(StockFilter.FORM, it.label)) }

  StockPlace.entries
    .filter { place -> entries.any { entry -> entry.row.sources.any { it.place == place } } }
    .forEach { add(FilterOption(StockFilter.PLACE, it.label)) }

  entries.map { it.row.title }.distinct().sorted()
    .forEach { add(FilterOption(StockFilter.COMMODITY, it)) }

  entries.mapNotNull { it.value.sale?.station }.distinct().sorted()
    .forEach { add(FilterOption(StockFilter.STATION, it)) }
}

/** Whether one entry answers one token. */
private fun stockMatches(entry: StockEntry, token: FilterOption, crops: Set<String>): Boolean = when (token.kind) {
  StockFilter.CATEGORY -> when (token.value) {
    StockFilter.CROPS -> entry.row.type in crops
    StockFilter.PRODUCTS -> entry.value.sale != null && entry.row.type !in crops
    StockFilter.UNPRICED -> entry.value.sale == null
    StockFilter.DEMAND -> entry.value.sale?.greatDemand == true
    StockFilter.FERMENTING -> entry.row.fermenting > 0
    else -> false
  }

  StockFilter.FORM -> entry.row.forms.any { it.label == token.value }

  StockFilter.PLACE -> entry.row.sources.any { it.place.label == token.value }

  StockFilter.COMMODITY -> entry.row.title == token.value

  StockFilter.STATION -> entry.value.sale?.station == token.value

  // Typed rather than picked: it reaches everything the row is spelled out of, so half a word out of a
  // barn's name finds what is in it.
  else -> entry.row.title.contains(token.value, ignoreCase = true) ||
    entry.row.forms.any { it.label.contains(token.value, ignoreCase = true) } ||
    entry.value.sale?.station?.contains(token.value, ignoreCase = true) == true ||
    entry.row.sources.any {
      it.name.contains(token.value, ignoreCase = true) || it.place.label.contains(token.value, ignoreCase = true)
    }
}

/**
 * The rows the tokens ask for: **OR within a kind, AND across kinds**.
 *
 * That is the only reading under which two commodities and one storage mean what a reader expects —
 * "either of these two, and only what is in that barn". ANDing everything would make a second
 * commodity empty the table; ORing everything would make adding a storage *widen* it.
 */
fun stockFiltered(entries: List<StockEntry>, tokens: List<FilterOption>, crops: Set<String>): List<StockEntry> {
  if (tokens.isEmpty()) return entries
  val byKind = tokens.groupBy { it.kind }
  return entries.filter { entry ->
    byKind.values.all { group -> group.any { stockMatches(entry, it, crops) } }
  }
}

/**
 * Order the table. Ties break on the title so the order is **total** — a farm holds a dozen things
 * nobody buys, and without a tie-break the next write could reshuffle them under the reader's finger.
 *
 * A row with nothing to sort on — no buyer, so no price and no projection — sorts to the end whichever
 * way the sort runs, for the reason the fleet's does: "unknown" is not a low value, and floating it to
 * the top of an ascending price sort would file the unsellable where the cheap belongs.
 */
fun stockSorted(entries: List<StockEntry>, sort: StockSort, ascending: Boolean): List<StockEntry> =
  entries.sortedWith { a, b ->
    val primary = when (sort) {
      StockSort.NAME -> 0

      StockSort.FORM -> compareValues(a.row.formOrdinal(), b.row.formOrdinal()).flip(ascending)

      StockSort.AMOUNT -> compareOptionalStock(a.row.liters.toFloat(), b.row.liters.toFloat(), ascending)

      StockSort.PRICE -> compareOptionalStock(a.value.sale?.price, b.value.sale?.price, ascending)

      StockSort.VALUE -> compareOptionalStock(a.value.value, b.value.value, ascending)

      StockSort.STATION -> compareOptionalText(a.value.sale?.station, b.value.sale?.station, ascending)

      StockSort.BEST_PRICE -> compareOptionalStock(a.value.bestPrice, b.value.bestPrice, ascending)

      StockSort.BEST_VALUE -> compareOptionalStock(a.value.bestValue, b.value.bestValue, ascending)

      StockSort.MONTH -> compareOptionalStock(
        a.value.bestMonth.takeIf { it > 0 }?.toFloat(),
        b.value.bestMonth.takeIf { it > 0 }?.toFloat(),
        ascending,
      )
    }
    if (primary != 0) {
      primary
    } else {
      val byName = a.row.title.lowercase().compareTo(b.row.title.lowercase())
      // Only the name sort reverses its tie-break; elsewhere the title is the stable spelling of
      // "equal", and reversing it would make the table jump when nothing changed.
      if (sort == StockSort.NAME && !ascending) -byName else byName
    }
  }

/** A mixed row sorts by the first form it is in, which is the one its "Type" column leads with. */
private fun StockRow.formOrdinal(): Int = forms.firstOrNull()?.ordinal ?: StockForm.entries.size

private fun Int.flip(ascending: Boolean): Int = if (ascending) this else -this

/** Compare two optional numbers, with "no value" sorting to the end whichever way the sort runs. */
private fun compareOptionalStock(a: Float?, b: Float?, ascending: Boolean): Int = when {
  a == null && b == null -> 0
  a == null -> 1
  b == null -> -1
  ascending -> a.compareTo(b)
  else -> b.compareTo(a)
}

private fun compareOptionalText(a: String?, b: String?, ascending: Boolean): Int = when {
  a == null && b == null -> 0
  a == null -> 1
  b == null -> -1
  else -> a.lowercase().compareTo(b.lowercase()).flip(ascending)
}

/** The table's bottom line. */
data class StockTotals(
  val rows: Int,
  val value: Float,
  val bestValue: Float,
  /** Rows no station buys, which are in none of the two sums above. */
  val unpriced: Int,
)

/**
 * Add the listed rows up.
 *
 * A row with a price but no projection contributes its **current** value to [bestValue] as well: its
 * commodity has a flat curve, so the best month really is worth what today is, and dropping it would
 * make the two totals compare different baskets. A row nothing buys is in neither sum and is counted
 * separately instead — putting a zero in for it would read as "worth nothing" rather than "unpriced".
 */
fun stockTotals(entries: List<StockEntry>): StockTotals {
  var value = 0f
  var best = 0f
  var unpriced = 0
  entries.forEach { entry ->
    val now = entry.value.value
    if (now == null) {
      unpriced++
    } else {
      value += now
      best += entry.value.bestValue ?: now
    }
  }
  return StockTotals(entries.size, value, best, unpriced)
}

// ---- The price board's own filtering ---------------------------------------------------------------

/** The kinds of thing the price board can be filtered by. Same kind ORs, different kinds AND. */
object PriceFilter {
  const val CATEGORY: String = "Category"
  const val COMMODITY: String = "Commodity"
  const val STATION: String = "Station"

  const val CROPS: String = "Crops"
  const val SELL: String = "Sold here"
  const val BUY: String = "Bought here"
  const val PALLETS: String = "Pallets"
  const val DEMAND: String = "Great demand"
}

/** What one commodity is traded as, gathered once so the filter does not re-walk the stations per row. */
data class BoardFacts(
  val sells: Boolean,
  val buys: Boolean,
  val pallets: Boolean,
  val greatDemand: Boolean,
  val stations: Set<String>,
)

/** Index the board by fill type: which stations trade it, and in which direction. */
fun boardFacts(data: PricesData): Map<String, BoardFacts> {
  val sells = mutableSetOf<String>()
  val buys = mutableSetOf<String>()
  val pallets = mutableSetOf<String>()
  val demand = mutableSetOf<String>()
  val stations = mutableMapOf<String, MutableSet<String>>()
  data.stations.forEach { station ->
    fun note(type: String) {
      stations.getOrPut(type) { mutableSetOf() } += station.name
    }
    station.sell.forEach {
      sells += it.type
      if (it.greatDemand) demand += it.type
      note(it.type)
    }
    station.buy.forEach {
      buys += it.type
      note(it.type)
    }
    station.pallets.forEach {
      pallets += it.type
      note(it.type)
    }
  }
  return data.fillTypes.associate { fillType ->
    fillType.type to BoardFacts(
      sells = fillType.type in sells,
      buys = fillType.type in buys,
      pallets = fillType.type in pallets,
      greatDemand = fillType.type in demand,
      stations = stations[fillType.type].orEmpty(),
    )
  }
}

/** What this board can be filtered by — categories with a member, every commodity, every station. */
fun priceOptions(data: PricesData, facts: Map<String, BoardFacts>): List<FilterOption> = buildList {
  fun category(label: String, present: Boolean) {
    if (present) add(FilterOption(PriceFilter.CATEGORY, label))
  }
  category(PriceFilter.CROPS, data.fillTypes.any { it.isCrop })
  category(PriceFilter.SELL, facts.values.any { it.sells })
  category(PriceFilter.BUY, facts.values.any { it.buys })
  category(PriceFilter.PALLETS, facts.values.any { it.pallets })
  category(PriceFilter.DEMAND, facts.values.any { it.greatDemand })

  data.fillTypes.map { it.title }.distinct().sorted()
    .forEach { add(FilterOption(PriceFilter.COMMODITY, it)) }

  data.stations.map { it.name }.distinct().sorted()
    .forEach { add(FilterOption(PriceFilter.STATION, it)) }
}

private fun priceMatches(fillType: PricesFillType, facts: BoardFacts?, token: FilterOption): Boolean =
  when (token.kind) {
    PriceFilter.CATEGORY -> when (token.value) {
      PriceFilter.CROPS -> fillType.isCrop
      PriceFilter.SELL -> facts?.sells == true
      PriceFilter.BUY -> facts?.buys == true
      PriceFilter.PALLETS -> facts?.pallets == true
      PriceFilter.DEMAND -> facts?.greatDemand == true
      else -> false
    }

    PriceFilter.COMMODITY -> fillType.title == token.value

    PriceFilter.STATION -> facts?.stations?.contains(token.value) == true

    else -> fillType.title.contains(token.value, ignoreCase = true) ||
      facts?.stations?.any { it.contains(token.value, ignoreCase = true) } == true
  }

/** The commodities the tokens ask for — same OR-within-kind, AND-across-kinds rule as [stockFiltered]. */
fun priceFiltered(
  fillTypes: List<PricesFillType>,
  facts: Map<String, BoardFacts>,
  tokens: List<FilterOption>,
): List<PricesFillType> {
  if (tokens.isEmpty()) return fillTypes
  val byKind = tokens.groupBy { it.kind }
  return fillTypes.filter { fillType ->
    byKind.values.all { group -> group.any { priceMatches(fillType, facts[fillType.type], it) } }
  }
}
