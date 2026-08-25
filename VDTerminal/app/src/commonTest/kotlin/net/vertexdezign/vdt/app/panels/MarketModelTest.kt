package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.components.FilterOption
import net.vertexdezign.vdt.model.BunkerSilo
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.Husbandry
import net.vertexdezign.vdt.model.HusbandryCondition
import net.vertexdezign.vdt.model.LooseStock
import net.vertexdezign.vdt.model.PricesBuy
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.PricesFillType
import net.vertexdezign.vdt.model.PricesSell
import net.vertexdezign.vdt.model.PricesStation
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.ProductionFill
import net.vertexdezign.vdt.model.ProductionIo
import net.vertexdezign.vdt.model.ProductionLine
import net.vertexdezign.vdt.model.ProductionPoint
import net.vertexdezign.vdt.model.StandaloneStorage
import net.vertexdezign.vdt.model.StorageData
import net.vertexdezign.vdt.model.StoredObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Market app's stock join: what the farm holds, gathered off four channels, and what the board
 * makes of it.
 *
 * Built as model objects rather than from a committed capture — the app module is wasm-only and has no
 * file access in its tests — but the *numbers* are lifted straight out of
 * `examples/json/prices/vanilla.json` and the committed storage captures, so the projection is checked
 * against a curve the game actually produced rather than one shaped to make the arithmetic tidy.
 */
class MarketModelTest {
  // Barley as the vanilla capture has it: the twelve-month history, period 6 (the trough), and the
  // best station paying 277.91 per 1000 l.
  private val barleyMonths =
    listOf(313f, 335f, 310f, 294f, 266f, 257f, 269f, 310f, 338f, 354f, 379f, 338f)

  private fun barley(months: List<Float> = barleyMonths) = PricesFillType(
    type = "BARLEY",
    title = "Gerste",
    basePrice = 313f,
    isCrop = true,
    showOnPriceTable = true,
    months = months,
    bestMonth = 11,
    bestPrice = 379f,
  )

  private fun board(
    fillTypes: List<PricesFillType> = listOf(barley()),
    stations: List<PricesStation> = listOf(
      PricesStation(id = "mill", name = "Getreidemühle", sell = listOf(PricesSell("BARLEY", 277.91f, "falling"))),
    ),
    period: Int = 6,
  ) = PricesData(version = "1", period = period, priceMultiplier = 1f, fillTypes = fillTypes, stations = stations)

  private fun row(
    type: String = "BARLEY",
    form: StockForm = StockForm.BULK,
    liters: Int = 12_400,
    title: String = "Gerste",
  ) = StockRow(
    key = type,
    type = type,
    title = title,
    liters = liters,
    count = 0,
    fermenting = 0,
    sources = listOf(StockSource(StockPlace.SILO, "Silo", form, liters)),
  )

  // ---- buildStock ---------------------------------------------------------------------------------

  @Test
  fun `one commodity merges across every source it sits in, and each says how full it is`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Silo",
          kind = "fill",
          fills = listOf(ProductionFill("BARLEY", "Gerste", 30_000, 100_000)),
        ),
        StandaloneStorage(
          id = "s2",
          name = "Hofsilo",
          kind = "fill",
          fills = listOf(ProductionFill("BARLEY", "Gerste", 5_000, 50_000)),
        ),
      ),
    )

    val rows = buildStock(storage, null, null, board())

    assertEquals(1, rows.size)
    assertEquals(35_000, rows[0].liters)
    assertEquals("Gerste", rows[0].title)
    assertEquals(listOf("Silo", "Hofsilo"), rows[0].sources.map { it.name })
    // The capacity rides along so the drill-in can say 30,000 of 100,000 rather than 30,000 of nothing.
    assertEquals(listOf(30_000 to 100_000, 5_000 to 50_000), rows[0].sources.map { it.liters to it.capacity })
  }

  @Test
  fun `a production point contributes what it makes, not what it was fed`() {
    // The committed fixture's biogas bunker: silage delivered in, fermenter silage made out of it.
    val production = ProductionData(
      productionPoints = listOf(
        ProductionPoint(
          id = "p1",
          name = "Bunker Mittel",
          lines = listOf(
            ProductionLine(
              id = "silage",
              name = "Silage",
              inputs = listOf(ProductionIo("SILAGE", "Silage", 400)),
              outputs = listOf(ProductionIo("FERMENTERSILAGE", "Fermentersilage", 400)),
            ),
          ),
          storage = listOf(
            ProductionFill("SILAGE", "Silage", 12_000, 60_000),
            ProductionFill("FERMENTERSILAGE", "Fermentersilage", 3_000, 50_000),
          ),
        ),
      ),
    )

    val rows = buildStock(null, production, null, null)

    // The 12,000 l of silage are on their way into a machine; nothing can drive up and fetch them.
    assertEquals(listOf("FERMENTERSILAGE"), rows.map { it.type })
    assertEquals(3_000, rows[0].liters)
  }

  @Test
  fun `a fill type a chain both eats and makes still counts`() {
    val production = ProductionData(
      productionPoints = listOf(
        ProductionPoint(
          id = "p1",
          name = "Bäckerei",
          lines = listOf(
            ProductionLine(
              id = "flour",
              name = "Mehl",
              inputs = listOf(ProductionIo("WHEAT", "Weizen", 400)),
              outputs = listOf(ProductionIo("FLOUR", "Mehl", 400)),
            ),
            ProductionLine(
              id = "bread",
              name = "Brot",
              inputs = listOf(ProductionIo("FLOUR", "Mehl", 200)),
              outputs = listOf(ProductionIo("BREAD", "Brot", 100)),
            ),
          ),
          storage = listOf(
            ProductionFill("WHEAT", "Weizen", 8_000, 50_000),
            ProductionFill("FLOUR", "Mehl", 4_000, 20_000),
            ProductionFill("BREAD", "Brot", 900, 10_000),
          ),
        ),
      ),
    )

    val rows = buildStock(null, production, null, null)

    // Flour is an input to the bread line and the output of the flour line — it is stock either way.
    assertEquals(setOf("FLOUR", "BREAD"), rows.map { it.type }.toSet())
  }

  @Test
  fun `bulk and bales of the same commodity are one row, split only in its sources`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Silo",
          kind = "fill",
          fills = listOf(ProductionFill("STRAW", "Stroh", 20_000, 100_000)),
        ),
      ),
      looseBales = listOf(LooseStock("STRAW", "Stroh", kind = "BALE", shape = "ROUND", count = 7, level = 50_178)),
    )

    val rows = buildStock(storage, null, null, null)

    assertEquals(1, rows.size)
    // "How much straw have I got" has one answer; which containers it is in belongs to the sources.
    assertEquals(70_178, rows[0].liters)
    assertEquals(7, rows[0].count)
    assertEquals(listOf(StockForm.BULK, StockForm.BALE), rows[0].forms)
    assertEquals(
      listOf(StockForm.BULK to 20_000, StockForm.BALE to 50_178),
      rows[0].sources.map { it.form to it.liters },
    )
  }

  @Test
  fun `every container one commodity comes in lands on the same row`() {
    // Seed as the modded capture has it: a pallet and a big bag in two different racks.
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Palettenregal",
          kind = "object",
          objects = listOf(
            StoredObject(
              index = 1,
              title = "Palette (Saatgut 500 l)",
              count = 1,
              type = "SEEDS",
              level = 500,
              kind = "PALLET",
            ),
          ),
        ),
        StandaloneStorage(
          id = "s2",
          name = "Offene Garage",
          kind = "object",
          objects = listOf(
            StoredObject(
              index = 1,
              title = "Bigbag (Saatgut 922 l)",
              count = 1,
              type = "SEEDS",
              level = 922,
              kind = "BIGBAG",
            ),
          ),
        ),
      ),
    )

    val rows = buildStock(storage, null, null, null)

    assertEquals(1, rows.size)
    assertEquals(1_422, rows[0].liters)
    assertEquals(2, rows[0].count)
    assertEquals(listOf(StockForm.PALLET, StockForm.BIGBAG), rows[0].forms)
    assertEquals(listOf("Palettenregal", "Offene Garage"), rows[0].sources.map { it.name })
  }

  @Test
  fun `two groups of one commodity in one store are one source line`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Futterhalle",
          kind = "object",
          objects = listOf(
            StoredObject(
              index = 1,
              title = "Rundballen (Heu 5.000 l)",
              count = 183,
              type = "DRYGRASS_WINDROW",
              level = 5_000,
              kind = "BALE",
              shape = "ROUND",
            ),
            StoredObject(
              index = 2,
              title = "Rundballen (Heu 3.245 l)",
              count = 5,
              type = "DRYGRASS_WINDROW",
              level = 3_245,
              kind = "BALE",
              shape = "ROUND",
            ),
          ),
        ),
      ),
    )

    val rows = buildStock(storage, null, null, null)

    // One building, listed once: the game's two groups are two fill levels, not two places to drive to.
    assertEquals(1, rows[0].sources.size)
    assertEquals(188, rows[0].sources[0].count)
    assertEquals(931_225, rows[0].sources[0].liters)
  }

  @Test
  fun `a stored bale and a bale on the ground land on one row, with the sources kept apart`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Ballenlager",
          kind = "object",
          objects = listOf(
            StoredObject(
              index = 1,
              title = "Rundballen (Gras 3.500 l)",
              count = 2,
              type = "GRASS_WINDROW",
              level = 3_500,
              kind = "BALE",
              shape = "ROUND",
            ),
          ),
        ),
      ),
      looseBales = listOf(
        LooseStock("GRASS_WINDROW", "Gras", kind = "BALE", shape = "ROUND", count = 1, level = 3_500, fermenting = 1),
      ),
    )

    val rows = buildStock(storage, null, null, null)

    assertEquals(1, rows.size)
    val row = rows[0]
    // The stored group's level is per object, the loose group's is the whole group's — the two units
    // this join has to get right, or 2 stored bales read as 3,500 l between them.
    assertEquals(10_500, row.liters)
    assertEquals(3, row.count)
    assertEquals(1, row.fermenting)
    // A group's own title has the liter count baked into it, so the fill type's title wins the row.
    assertEquals("Gras", row.title)
    assertEquals(listOf(StockPlace.STORE, StockPlace.OUTSIDE), row.sources.map { it.place })
  }

  @Test
  fun `stock the game gives no fill type keeps its own title and its count`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Lager",
          kind = "object",
          objects = listOf(
            StoredObject(index = 1, title = "Palette (Gemüsepalette)", count = 2, kind = "PALLET"),
            StoredObject(index = 2, title = "Palette (Transportkiste)", count = 1, kind = "PALLET"),
          ),
        ),
      ),
    )

    val rows = buildStock(storage, null, null, null)

    assertEquals(2, rows.size)
    assertTrue(rows.all { it.type.isEmpty() && it.liters == 0 })
    assertEquals(listOf("Palette (Gemüsepalette)", "Palette (Transportkiste)"), rows.map { it.title }.sorted())
  }

  @Test
  fun `a pen contributes what it produces, not the straw it eats`() {
    val husbandry = HusbandriesData(
      husbandries = listOf(
        Husbandry(
          id = "h1",
          name = "Kuhstall",
          food = listOf(HusbandryCondition(title = "Totalmischration (100%)", value = 30_480)),
          conditions = listOf(
            HusbandryCondition(title = "Milch", type = "MILK", value = 5_818, inverted = true),
            HusbandryCondition(title = "Stroh", type = "STRAW", value = 20_059),
            // A pallet-output bar: liters waiting to become an egg pallet, which nobody owns yet.
            HusbandryCondition(title = "Eier", value = 400, inverted = true),
          ),
        ),
      ),
    )

    val rows = buildStock(null, null, husbandry, null)

    // Milk is the pen's output and counts; the straw is what it eats, and an input is not stock you
    // can sell back. The egg bar carries no fill type at all, so it is nobody's liters yet.
    assertEquals(listOf("MILK"), rows.map { it.type })
    assertEquals("awaiting collection", rows[0].sources[0].note)
  }

  @Test
  fun `an empty store is not stock`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Silo",
          kind = "fill",
          fills = listOf(ProductionFill("BARLEY", "Gerste", 0, 100_000), ProductionFill("OAT", "Hafer", 12, 100_000)),
        ),
      ),
      bunkerSilos = listOf(
        BunkerSilo(id = "b1", name = "Bunker", state = "FILL", type = "CHAFF", title = "Häckselgut", level = 0),
      ),
    )

    val rows = buildStock(storage, null, null, null)

    assertEquals(listOf("OAT"), rows.map { it.type })
  }

  @Test
  fun `a bunker says what state it is in`() {
    val storage = StorageData(
      bunkerSilos = listOf(
        BunkerSilo(
          id = "b1",
          name = "Großes Bunkersilo",
          state = "CLOSED",
          type = "SILAGE",
          title = "Silage",
          level = 520_486,
        ),
      ),
    )

    val rows = buildStock(storage, null, null, null)

    assertEquals("fermenting", rows[0].sources[0].note)
    assertEquals(StockPlace.BUNKER, rows[0].sources[0].place)
  }

  // ---- The board ----------------------------------------------------------------------------------

  @Test
  fun `the best offer is the highest, and a tie is broken by name so the column does not flicker`() {
    val data = board(
      stations = listOf(
        PricesStation(id = "b", name = "Bahnhof", sell = listOf(PricesSell("BARLEY", 250f, "climbing"))),
        PricesStation(id = "z", name = "Zeta", sell = listOf(PricesSell("BARLEY", 277.91f, "falling"))),
        PricesStation(id = "a", name = "Alpha", sell = listOf(PricesSell("BARLEY", 277.91f, "climbing"))),
      ),
    )

    val best = assertNotNull(bestSales(data)["BARLEY"])

    assertEquals("Alpha", best.station)
    assertEquals(277.91f, best.price)
  }

  @Test
  fun `great demand rides along, premium and countdown included`() {
    val data = board(
      stations = listOf(
        PricesStation(
          id = "m",
          name = "Mühle",
          sell = listOf(
            PricesSell("BARLEY", 340f, "climbing", greatDemand = true, demandMultiplier = 1.2f, demandHoursLeft = 6),
          ),
        ),
      ),
    )

    val best = assertNotNull(bestSales(data)["BARLEY"])

    assertTrue(best.greatDemand)
    assertEquals(1.2f, best.demandMultiplier)
    assertEquals(6, best.demandHoursLeft)
  }

  // ---- Valuation ----------------------------------------------------------------------------------

  @Test
  fun `the peak is projected onto the best station, not quoted off the market average`() {
    val sale = assertNotNull(bestSales(board())["BARLEY"])

    val valuation = valuate(row(), sale, barley(), period = 6)

    assertEquals(3446.08f, assertNotNull(valuation.value), 0.05f)
    assertEquals(11, valuation.bestMonth)
    // 277.91 x 379/257 — the station's level, the market's shape. The curve's own peak is 379, which
    // is BELOW today's 277.91-paying station once you remember it is an average across stations.
    assertEquals(409.84f, assertNotNull(valuation.bestPrice), 0.05f)
    assertEquals(5081.97f, assertNotNull(valuation.bestValue), 0.5f)
  }

  @Test
  fun `a flat curve claims no best month`() {
    // Silage in the same capture: twelve identical numbers, because its seasonal factors never move.
    val silage = PricesFillType(
      type = "SILAGE",
      title = "Silage",
      basePrice = 121f,
      months = List(12) { 121f },
      bestMonth = 1,
      bestPrice = 121f,
    )
    val sale = BestSale(123.6f, "Viehhändler", "falling", greatDemand = false)

    val valuation = valuate(row("SILAGE", liters = 60_000), sale, silage, period = 6)

    assertEquals(0, peakMonth(silage))
    assertEquals(7_416f, assertNotNull(valuation.value), 0.01f)
    assertEquals(0, valuation.bestMonth)
    assertNull(valuation.bestPrice)
    assertNull(valuation.bestValue)
  }

  @Test
  fun `stock nothing buys is priced at nothing, not at zero`() {
    val valuation = valuate(row("GRASS_FERMENTED", liters = 201_000), null, null, period = 6)

    assertNull(valuation.sale)
    assertNull(valuation.value)
    assertNull(valuation.bestValue)
    assertEquals(0, valuation.bestMonth)
  }

  @Test
  fun `an exported best month that disagrees with its own curve loses to the curve`() {
    // The label goes on a bar of the chart beside it, so the two have to name the same bar.
    val bogus = barley().copy(bestMonth = 3)

    val valuation = valuate(row(), bestSales(board())["BARLEY"], bogus, period = 6)

    assertEquals(11, peakMonth(bogus))
    assertEquals(11, valuation.bestMonth)
  }

  @Test
  fun `a period the board could not read leaves the projection alone`() {
    val valuation = valuate(row(), bestSales(board())["BARLEY"], barley(), period = 0)

    assertNotNull(valuation.value)
    assertEquals(0, valuation.bestMonth)
    assertNull(valuation.bestPrice)
  }

  // ---- The table's list operations -----------------------------------------------------------------

  private fun entries(): List<StockEntry> {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Silo",
          kind = "fill",
          fills = listOf(ProductionFill("BARLEY", "Gerste", 12_400, 100_000)),
        ),
        StandaloneStorage(
          id = "s2",
          name = "Ballenlager",
          kind = "object",
          objects = listOf(
            StoredObject(
              index = 1,
              title = "Rundballen (Grassilage 3.000 l)",
              count = 50,
              type = "GRASS_FERMENTED",
              level = 3_000,
              kind = "BALE",
              shape = "ROUND",
            ),
          ),
        ),
      ),
    )
    return valuateAll(buildStock(storage, null, null, board()), board())
  }

  @Test
  fun `totals leave the unpriced out of the money and count them instead`() {
    val totals = stockTotals(entries())

    assertEquals(2, totals.rows)
    assertEquals(1, totals.unpriced)
    assertEquals(3446.08f, totals.value, 0.05f)
    assertEquals(5081.97f, totals.bestValue, 0.5f)
  }

  @Test
  fun `a priced row with a flat curve still carries its value into the best-value total`() {
    val flat = PricesFillType(type = "SILAGE", title = "Silage", months = List(12) { 121f }, bestPrice = 121f)
    val data = board(
      fillTypes = listOf(flat),
      stations = listOf(PricesStation(id = "v", name = "Viehhändler", sell = listOf(PricesSell("SILAGE", 123.6f)))),
    )
    val rows = listOf(row("SILAGE", liters = 60_000))

    val totals = stockTotals(valuateAll(rows, data))

    assertEquals(totals.value, totals.bestValue)
    assertEquals(0, totals.unpriced)
  }

  @Test
  fun `rows with nothing to sort on go last whichever way the sort runs`() {
    val ascending = stockSorted(entries(), StockSort.VALUE, ascending = true)
    val descending = stockSorted(entries(), StockSort.VALUE, ascending = false)

    assertEquals("GRASS_FERMENTED", ascending.last().row.type)
    assertEquals("GRASS_FERMENTED", descending.last().row.type)
  }

  // ---- The source line -------------------------------------------------------------------------------

  @Test
  fun `a source line says the form, the count, the liters and what the store holds full`() {
    assertEquals(
      "30,000 / 100,000 l",
      sourceAmount(StockSource(StockPlace.SILO, "Silo", StockForm.BULK, 30_000, capacity = 100_000)),
    )
    // A bunker's ceiling is the shape of its walls, so there is no "of" to print.
    assertEquals(
      "520,486 l",
      sourceAmount(StockSource(StockPlace.BUNKER, "Bunkersilo", StockForm.BULK, 520_486)),
    )
    assertEquals(
      "Bales ×183 · 915,000 l",
      sourceAmount(StockSource(StockPlace.STORE, "Futterhalle", StockForm.BALE, 915_000, count = 183)),
    )
    // A crate holds no fill type, so it has a count and nothing else.
    assertEquals(
      "Pallets ×2",
      sourceAmount(StockSource(StockPlace.STORE, "Lager", StockForm.PALLET, 0, count = 2)),
    )
  }

  // ---- Filtering -----------------------------------------------------------------------------------

  private val crops = setOf("BARLEY")

  @Test
  fun `an option nothing can answer is not offered`() {
    val onlyUnpriced = valuateAll(
      listOf(row("GRASS_FERMENTED", StockForm.BALE, 150_000, title = "Grassilage")),
      board(fillTypes = emptyList(), stations = emptyList()),
    )

    val offered = stockOptions(onlyUnpriced, crops)

    assertEquals(
      listOf(StockFilter.UNPRICED, StockForm.BALE.label, StockPlace.SILO.label, "Grassilage"),
      offered.map { it.label },
    )
    // The stock in `entries()` has a crop in it, so that category is on offer there and not here.
    assertTrue(stockOptions(entries(), crops).any { it.label == StockFilter.CROPS })
    assertTrue(offered.none { it.label == StockFilter.CROPS })
  }

  @Test
  fun `no tokens is everything`() {
    assertEquals(entries().size, stockFiltered(entries(), emptyList(), crops).size)
  }

  @Test
  fun `two tokens of one kind widen, two kinds narrow`() {
    val all = entries()
    val barley = FilterOption(StockFilter.COMMODITY, "Gerste")
    // Nothing on the board names GRASS_FERMENTED, so the row keeps the group's own dialog text.
    val silage = FilterOption(StockFilter.COMMODITY, "Rundballen (Grassilage 3.000 l)")
    val unpriced = FilterOption(StockFilter.CATEGORY, StockFilter.UNPRICED)

    // Same kind: either of the two.
    assertEquals(2, stockFiltered(all, listOf(barley, silage), crops).size)
    // Different kinds: both at once, so the priced one drops out.
    assertEquals(
      listOf("Rundballen (Grassilage 3.000 l)"),
      stockFiltered(all, listOf(barley, silage, unpriced), crops).map { it.row.title },
    )
    // And a pair that can never both hold comes back empty rather than widening.
    assertTrue(stockFiltered(all, listOf(barley, unpriced), crops).isEmpty())
  }

  @Test
  fun `a form token matches a row that is only partly in that form`() {
    val storage = StorageData(
      storages = listOf(
        StandaloneStorage(
          id = "s1",
          name = "Silo",
          kind = "fill",
          fills = listOf(ProductionFill("STRAW", "Stroh", 20_000, 100_000)),
        ),
      ),
      looseBales = listOf(LooseStock("STRAW", "Stroh", kind = "BALE", shape = "ROUND", count = 7, level = 50_178)),
    )
    val mixed = valuateAll(buildStock(storage, null, null, null), null)

    assertEquals(1, stockFiltered(mixed, listOf(FilterOption(StockFilter.FORM, StockForm.BALE.label)), crops).size)
    assertEquals(1, stockFiltered(mixed, listOf(FilterOption(StockFilter.FORM, StockForm.BULK.label)), crops).size)
    assertTrue(
      stockFiltered(mixed, listOf(FilterOption(StockFilter.FORM, StockForm.PALLET.label)), crops).isEmpty(),
    )
  }

  @Test
  fun `typed text reaches the station and the store, not only the name`() {
    val all = entries()

    fun text(value: String) = stockFiltered(all, listOf(FilterOption(FilterOption.TEXT, value)), crops)

    assertEquals(1, text("gerste").size)
    assertEquals(1, text("getreide").size)
    assertEquals(1, text("ballenlager").size)
    assertTrue(text("nothing here").isEmpty())
  }

  // ---- The price board's filtering --------------------------------------------------------------------

  @Test
  fun `the board offers only the categories its stations support`() {
    val data = board(
      fillTypes = listOf(barley(), PricesFillType(type = "DIESEL", title = "Diesel", basePrice = 1_500f)),
      stations = listOf(
        PricesStation(id = "m", name = "Getreidemühle", sell = listOf(PricesSell("BARLEY", 277.91f, "falling"))),
        PricesStation(id = "t", name = "Tankstelle", buy = listOf(PricesBuy("DIESEL", 1_500f))),
      ),
    )
    val facts = boardFacts(data)

    val labels = priceOptions(data, facts).map { it.label }

    assertEquals(
      listOf(PriceFilter.CROPS, PriceFilter.SELL, PriceFilter.BUY, "Diesel", "Gerste", "Getreidemühle", "Tankstelle"),
      labels,
    )
    // No pallet shop and no running demand on this board, so neither is offered.
    assertTrue(PriceFilter.PALLETS !in labels && PriceFilter.DEMAND !in labels)
  }

  @Test
  fun `a station token keeps the commodities that station trades`() {
    val data = board(
      fillTypes = listOf(barley(), PricesFillType(type = "DIESEL", title = "Diesel", basePrice = 1_500f)),
      stations = listOf(
        PricesStation(id = "m", name = "Getreidemühle", sell = listOf(PricesSell("BARLEY", 277.91f, "falling"))),
        PricesStation(id = "t", name = "Tankstelle", buy = listOf(PricesBuy("DIESEL", 1_500f))),
      ),
    )
    val facts = boardFacts(data)

    val shown = priceFiltered(data.fillTypes, facts, listOf(FilterOption(PriceFilter.STATION, "Tankstelle")))

    assertEquals(listOf("DIESEL"), shown.map { it.type })
    assertEquals(2, priceFiltered(data.fillTypes, facts, emptyList()).size)
  }
}
