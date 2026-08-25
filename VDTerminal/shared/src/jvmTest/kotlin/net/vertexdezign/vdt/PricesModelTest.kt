package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.PricesData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `prices.json` channel the mod writes for the map's price board
 * (`src/collect/PricesExporter.lua`).
 *
 * Two captures drive this. [parsesTheVanillaCapture] and [theCaptureCarriesEveryStationShape]
 * decode `examples/json/prices/vanilla.json` — a whole vanilla board in singleplayer, 44 stations
 * against 121 commodities — through the real server path ([VdtParser.parsePrices]), and pin the
 * invariants the exporter promises: every row joins to the catalogue, one placeable is one row, and
 * every list arrives sorted. [parsesTheModdedMultiplayerCapture] and the three tests after it
 * decode `mp_modded.json`, a modded map on a multiplayer client, for what a base-game board cannot
 * show: a catalogue half again as large, five stations sharing one name, a curve that is mostly
 * zero, and (against `examples/json/map/mp_modded.json` from the same session) stations the map
 * puts no marker on at all.
 *
 * The rest is **inline JSON deliberately.** Fixtures in this project are real game captures and
 * never hand-authored, and the two shapes neither capture contains — a running great demand, and a
 * commodity with no economy at all — are still waiting on a save that has them (FUTURE.md
 * → "Captures wanted as fixtures"). Their fill-type names and prices are therefore illustrative,
 * and those tests pin the *shape* and the absence rules rather than any map's numbers.
 *
 * What is worth pinning down is where **absent means something**: a station that only buys has no
 * empty sell list to distinguish it from one that pays nothing, a commodity with no economy has no
 * "best month" rather than a best month of January at zero, and a great demand's multiplier is null
 * on a row that has none rather than 1.
 */
class PricesModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String = capture("prices/$name")

  /** The map channel, for the one cross-channel invariant below. */
  private fun mapExample(name: String): String = capture("map/$name")

  private fun capture(relative: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/$relative")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/$relative from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: PricesData) {
    val encoded = json.encodeToString(PricesData.serializer(), data)
    assertEquals(data, json.decodeFromString(PricesData.serializer(), encoded), "JSON round-trip should be lossless")
  }

  /**
   * The committed capture: a played-in vanilla save's whole board, in German, at period 6. The
   * numbers are that file's and are the point — this is the size and shape a real board has, which
   * no hand-written fixture would have got right (581 price rows across 44 stations, and a
   * catalogue that is exactly the fill types those rows name).
   */
  @Test
  fun parsesTheVanillaCapture() {
    val data = VdtParser.parsePrices(example("vanilla.json"))

    assertEquals("1", data.version)
    assertEquals(6, data.period, "the app marks period 6 as 'now' on every twelve-month curve")
    assertEquals(1f, data.priceMultiplier, "a hard-economy save — these prices carry a factor of 1")
    assertEquals(44, data.stations.size)
    assertEquals(121, data.fillTypes.size)

    // The catalogue is built from the fill types the rows named, so the join holds in both
    // directions: no row without a title to print, no orphan commodity nothing sells.
    val catalogue = data.fillTypes.associateBy { it.type }
    assertEquals(data.fillTypes.size, catalogue.size, "one catalogue entry per fill type")
    val rowTypes =
      data.stations.flatMap { station ->
        station.sell.map { it.type } + station.buy.map { it.type } + station.pallets.map { it.type }
      }
    assertEquals(581, rowTypes.size)
    assertEquals(catalogue.keys, rowTypes.toSet(), "every row joins to exactly one catalogue entry")

    // Sorted on the way out (stations by name, rows by fill type): the file is rewritten every 30 s
    // forever, and a list that shuffled would make every diff — and every app animation — noise.
    val names = data.stations.map { it.name }
    assertEquals(names.sorted(), names)
    assertEquals(catalogue.keys.sorted(), data.fillTypes.map { it.type })
    val board = data.stations.first { it.name == "Bauernmarkt" }
    assertEquals(109, board.sell.size)
    assertEquals(board.sell.map { it.type }.sorted(), board.sell.map { it.type })

    // Ids are unique per station and come in several shapes — a map's own `preplaced_*`, a
    // save-generated `placeable<hash>`, and the plain uniqueId a map author wrote by hand. The app
    // treats all three as opaque.
    assertEquals(
      data.stations.size,
      data.stations
        .map { it.id }
        .distinct()
        .size,
    )

    // The positions #112 kept on this channel rather than joining them from map.json: every station
    // has one, normalized to the same [0,1] frame the map overlay draws in.
    assertTrue(
      data.stations.all { station ->
        val x = station.posX
        val z = station.posZ
        x != null && z != null && x in 0f..1f && z in 0f..1f
      },
      "every station in the capture carries a normalized position",
    )

    // A twelve-month curve is a running average of what this save has seen, so its peak is a real
    // month rather than the seasonal maximum — and bestMonth/bestPrice must agree with the curve.
    val barley = catalogue.getValue("BARLEY")
    assertEquals("Gerste", barley.title, "titles are localized; only `type` is a stable token")
    assertTrue(barley.isCrop)
    assertEquals(12, barley.months.size)
    assertEquals(11, barley.bestMonth)
    assertEquals(379f, barley.bestPrice)
    assertEquals(barley.months.max(), barley.bestPrice)
    assertTrue(
      data.fillTypes.all { it.months.isEmpty() || it.bestPrice == it.months.max() },
      "bestPrice is the peak of the curve it summarises",
    )

    // Water and chaff trade but are not on the game's own prices table — the flag is a filter the
    // app can offer, not a claim that the commodity is worthless.
    assertEquals(listOf("CHAFF", "WATER"), data.fillTypes.filter { !it.showOnPriceTable }.map { it.type })

    // No great demand was running when this was captured (they last a few in-game hours, so a
    // capture has to be timed): `carriesAGreatDemandAsAPremiumWithATimeLimit` covers that shape.
    assertTrue(data.stations.flatMap { it.sell }.none { it.greatDemand })

    assertRoundTrips(data)
  }

  /**
   * The four station shapes the board actually contains, each read off the capture: buy-only,
   * buy-and-sell on one placeable, a counter shop that also pays for goods, and one the game will
   * only let you reach by train.
   */
  @Test
  fun theCaptureCarriesEveryStationShape() {
    val data = VdtParser.parsePrices(example("vanilla.json"))

    // Buy-only: the dredging boat sells stone and pays for nothing. An empty `sell` is the whole
    // difference between that and a station that pays zero.
    val dredger = data.stations.first { it.name == "Baggerschiff" }
    assertTrue(dredger.sell.isEmpty())
    assertEquals(listOf("STONE"), dredger.buy.map { it.type })
    assertEquals(62.5f, dredger.buy.single().price)

    // One placeable is one row, even when it trades both ways in the same fill type: the slurry
    // station charges 36.30 per 1000 l and pays 29.96 for the same litres, and the spread only
    // reads as a spread because both sides sit on one entry.
    val slurry = data.stations.first { it.name == "Gülle" }
    assertEquals(36.3f, slurry.buy.single { it.type == "LIQUIDMANURE" }.price)
    assertEquals(29.96f, slurry.sell.single { it.type == "LIQUIDMANURE" }.price)

    // Same rule across the two collection passes: the depot's pallet counter and its sell desk are
    // one row. The two BOARDS prices are in different units — 1872 for a whole pallet against
    // 1296.28 per 1000 l — so they must never be compared or summed.
    val depot = data.stations.first { it.name == "Depot" }
    assertTrue(depot.isPalletStation)
    assertEquals(66, depot.pallets.size)
    assertEquals(25, depot.sell.size)
    assertEquals(1872f, depot.pallets.single { it.type == "BOARDS" }.price)
    assertEquals(1296.28f, depot.sell.single { it.type == "BOARDS" }.price)

    // The train station: flagged because you cannot drive there, and the one station in the capture
    // whose id is a map-authored uniqueId rather than a generated one.
    val train = data.stations.single { it.isTrainStation }
    assertEquals("Goldcrest Valley", train.name)
    assertEquals("sellingStationTrain01", train.id)
    assertEquals(103, train.sell.size)
    assertFalse(train.isPalletStation)

    // Trends are the game's own arrows and all three tokens occur; none of them is a state the app
    // may render by colour alone (see the design rules).
    val trends =
      data.stations
        .flatMap { it.sell }
        .map { it.trend }
        .toSet()
    assertEquals(setOf("climbing", "falling", "steady"), trends)
  }

  /**
   * The second committed capture: a **modded map, seen from a multiplayer client** — 26 stations
   * against 166 commodities, 46 of which never appear on the vanilla board. It is the proof that
   * the catalogue is data: nothing mod-side or app-side enumerates fill types, so a map that trades
   * rye, winter wheat, six ice creams and a biogas chain arrives complete without a line of code.
   */
  @Test
  fun parsesTheModdedMultiplayerCapture() {
    val data = VdtParser.parsePrices(example("mp_modded.json"))

    assertEquals("1", data.version)
    assertEquals(6, data.period)
    assertEquals(1f, data.priceMultiplier, "another hard-economy save — no factor to unfold")
    assertEquals(26, data.stations.size)
    assertEquals(166, data.fillTypes.size)

    // The join holds in both directions here too, over a board of a different shape: half the
    // stations of the vanilla map, half again as many commodities, and one pallet counter
    // contributing 66 of the 653 rows.
    val catalogue = data.fillTypes.associateBy { it.type }
    assertEquals(data.fillTypes.size, catalogue.size, "one catalogue entry per fill type")
    assertEquals(579, data.stations.sumOf { it.sell.size })
    assertEquals(8, data.stations.sumOf { it.buy.size })
    assertEquals(66, data.stations.sumOf { it.pallets.size })
    val rowTypes =
      data.stations.flatMap { station ->
        station.sell.map { it.type } + station.buy.map { it.type } + station.pallets.map { it.type }
      }
    assertEquals(653, rowTypes.size)
    assertEquals(catalogue.keys, rowTypes.toSet(), "every row joins to exactly one catalogue entry")

    // Sorted on the way out, same as the vanilla board.
    val names = data.stations.map { it.name }
    assertEquals(names.sorted(), names)
    assertEquals(catalogue.keys.sorted(), data.fillTypes.map { it.type })
    val market = data.stations.first { it.name == "Agraria Markt" }
    assertEquals(150, market.sell.size)
    assertEquals(market.sell.map { it.type }.sorted(), market.sell.map { it.type })

    // What this map adds, measured against the other capture: 46 commodities the vanilla board
    // never listed — crops (rye, spelt, triticale, winter wheat), produce chains (six ice creams),
    // a biogas chain's intermediates. Each arrives with a title and a full curve, because both come
    // off the fill type itself rather than off any list we keep.
    val vanilla =
      VdtParser
        .parsePrices(example("vanilla.json"))
        .fillTypes
        .map { it.type }
        .toSet()
    val added = catalogue.keys - vanilla
    assertEquals(46, added.size)
    assertTrue(added.containsAll(listOf("RYE", "SPELT", "TRITICALE", "WINTERWHEAT", "ICECREAMVANILLA", "RAWMETHANE")))
    assertEquals(setOf("STONE"), vanilla - catalogue.keys, "and only the dredger's stone is missing the other way")
    assertTrue(added.all { catalogue.getValue(it).title.isNotEmpty() && catalogue.getValue(it).months.size == 12 })
    val rye = catalogue.getValue("RYE")
    assertEquals("Roggen", rye.title)
    assertTrue(rye.isCrop)
    assertEquals(10, rye.bestMonth)
    assertEquals(270f, rye.bestPrice)

    assertEquals(32, data.fillTypes.count { it.isCrop })
    assertTrue(data.fillTypes.all { it.months.size == 12 && it.bestPrice == it.months.max() })
    assertEquals(
      listOf("CCMRAW", "CHAFF", "COMPOSTRAW", "COTTON", "DIGESTATE1", "DIGESTATE3", "RAWMETHANE", "SUGARCANE", "WATER"),
      data.fillTypes.filter { !it.showOnPriceTable }.map { it.type },
      "the game's own table hides these; the flag is a filter the app can offer, not a verdict",
    )

    // Still no great demand and no train station: this map has neither, so both stay inline JSON.
    assertTrue(data.stations.flatMap { it.sell }.none { it.greatDemand })
    assertTrue(data.stations.none { it.isTrainStation })
    assertEquals(
      setOf("climbing", "falling", "steady"),
      data.stations
        .flatMap { it.sell }
        .map { it.trend }
        .toSet(),
    )

    assertRoundTrips(data)
  }

  /**
   * The station shapes on this board: the buy-only pump, the spread on one placeable, the pallet
   * counter and its unit trap — the same rules as the vanilla capture, re-read on a modded map — and
   * the one shape only this capture has, five separate placeables sharing a single name.
   */
  @Test
  fun theModdedBoardRepeatsAStationNameFiveTimes() {
    val data = VdtParser.parsePrices(example("mp_modded.json"))

    // Five garden plots, one name. The id is the key and the position is what tells them apart on
    // screen — anything that grouped this board by name would collapse five stations into one.
    val plots = data.stations.filter { it.name == "Gartengrundstück Mist" }
    assertEquals(5, plots.size)
    assertEquals(5, plots.map { it.id }.distinct().size)
    assertEquals(5, plots.map { it.posX to it.posZ }.distinct().size)
    assertTrue(plots.all { it.sell.map { row -> row.type } == listOf("MANURE", "SEEDS", "WATER") })
    assertEquals(
      26,
      data.stations
        .map { it.id }
        .distinct()
        .size,
      "ids stay unique where names do not",
    )

    // Buy-only, twice over: the fuel pump and the seed-and-fertilizer dealer pay for nothing, and
    // the empty `sell` is the whole difference between that and a station that pays zero.
    val fuel = data.stations.first { it.name == "Tankstelle" }
    assertTrue(fuel.sell.isEmpty())
    assertEquals(1500f, fuel.buy.single { it.type == "DIESEL" }.price)
    val dealer = data.stations.first { it.name == "Händler für Saatgut und Dünger" }
    assertTrue(dealer.sell.isEmpty())
    assertEquals(listOf("FERTILIZER", "HERBICIDE", "LIME", "LIQUIDFERTILIZER", "SEEDS"), dealer.buy.map { it.type })

    // One placeable, both directions, one fill type: the slurry station charges 36.30 per 1000 l
    // and pays 30.00 for the same litres, and the spread only reads as a spread on one entry.
    val slurry = data.stations.first { it.name == "Gülle" }
    assertEquals(36.3f, slurry.buy.single { it.type == "LIQUIDMANURE" }.price)
    assertEquals(30f, slurry.sell.single { it.type == "LIQUIDMANURE" }.price)

    // And the unit trap again, on this map's depot: 1872 for a whole pallet of boards against
    // 1319 per 1000 l for the same fill type. Two columns, two units, never summed.
    val depot = data.stations.first { it.name == "Depot" }
    assertTrue(depot.isPalletStation)
    assertEquals(66, depot.pallets.size)
    assertEquals(38, depot.sell.size)
    assertEquals(1872f, depot.pallets.single { it.type == "BOARDS" }.price)
    assertEquals(1319f, depot.sell.single { it.type == "BOARDS" }.price)
  }

  /**
   * Why #112 kept coordinates on this channel instead of joining the board to `map.json` by name.
   * The two captures come from the same session, so they can be held against each other: 21 of the
   * 26 stations sit on a map marker at exactly the same normalized coordinates — the two channels
   * share one frame to the last decimal — and the five garden plots have **no marker at all**. A
   * board drawn by joining to the map would have silently lost them.
   */
  @Test
  fun everyStationCarriesItsOwnPositionBecauseNotAllOfThemAreOnTheMap() {
    val board = VdtParser.parsePrices(example("mp_modded.json"))
    val markers = VdtParser.parseMap(mapExample("mp_modded.json")).pois

    val (onTheMap, unmarked) =
      board.stations.partition { station ->
        markers.any { it.posX == station.posX && it.posZ == station.posZ }
      }
    assertEquals(21, onTheMap.size)
    assertTrue(
      onTheMap.all { station ->
        markers.single { it.posX == station.posX && it.posZ == station.posZ }.name == station.name
      },
      "where a marker does exist it is exactly one marker, and it agrees on the name",
    )
    assertEquals(List(5) { "Gartengrundstück Mist" }, unmarked.map { it.name })
    assertTrue(
      unmarked.all { it.posX != null && it.posZ != null },
      "a station the map never marks still knows where it is",
    )
  }

  /**
   * A curve can be mostly zero. Three of this board's mod-added commodities — the two digestates and
   * raw methane, all internal to a biogas chain — have a running average recorded in two periods
   * only, so ten of their twelve months are 0. That is not a price: a chart plotting the curve
   * cannot let those zeros set its floor, and "cheapest month" over them would name a month nobody
   * can sell in. `bestMonth`/`bestPrice` still agree with the curve, and the game hides all three
   * from its own table — which is the app's ready-made default filter.
   */
  @Test
  fun aCurveCanBeMostlyZeroOnAModdedChainCommodity() {
    val data = VdtParser.parsePrices(example("mp_modded.json"))

    val sparse = data.fillTypes.filter { it.months.contains(0f) }
    assertEquals(listOf("DIGESTATE1", "DIGESTATE3", "RAWMETHANE"), sparse.map { it.type })
    assertTrue(sparse.none { it.showOnPriceTable })

    val digestate = data.fillTypes.single { it.type == "DIGESTATE1" }
    assertEquals("Fermentergärreste", digestate.title)
    assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 33f, 32f, 0f, 0f, 0f, 0f), digestate.months)
    assertEquals(7, digestate.bestMonth)
    assertEquals(33f, digestate.bestPrice)
    assertEquals(2, digestate.months.count { it > 0f }, "two periods seen, ten never priced")

    // The vanilla board has no such gap, which is why this needed the modded capture to show up.
    assertTrue(VdtParser.parsePrices(example("vanilla.json")).fillTypes.none { it.months.contains(0f) })
  }

  @Test
  fun parsesASellingStationWithItsCommodityCurve() {
    val data =
      VdtParser.parsePrices(
        """
        {
          "version": "1",
          "period": 6,
          "priceMultiplier": 1.0,
          "fillTypes": [
            {
              "type": "WHEAT",
              "title": "Wheat",
              "basePrice": 900.0,
              "isCrop": true,
              "showOnPriceTable": true,
              "months": [880, 910, 1010, 1080, 1120, 960, 780, 700, 720, 800, 850, 870],
              "bestMonth": 5,
              "bestPrice": 1120.0
            }
          ],
          "stations": [
            {
              "id": "MyMap.grainElevator",
              "name": "Grain Elevator",
              "posX": 0.4212,
              "posZ": 0.6031,
              "sell": [{ "type": "WHEAT", "price": 1043.55, "trend": "climbing" }]
            }
          ]
        }
        """.trimIndent(),
      )

    assertEquals("1", data.version)
    assertEquals(6, data.period)
    assertEquals(1f, data.priceMultiplier)
    assertRoundTrips(data)

    val station = data.stations.single()
    assertEquals("Grain Elevator", station.name)
    assertEquals(0.4212f, assertNotNull(station.posX))
    // A station that only buys from the player has neither of the two sell-to-player lists, and the
    // flags that would put a train or a pallet icon on it are off rather than absent-and-unknown.
    assertTrue(station.buy.isEmpty())
    assertTrue(station.pallets.isEmpty())
    assertFalse(station.isTrainStation)
    assertFalse(station.isPalletStation)

    val row = station.sell.single()
    assertEquals("WHEAT", row.type)
    assertEquals(1043.55f, row.price)
    assertEquals("climbing", row.trend)
    assertFalse(row.greatDemand)
    // No great demand means no multiplier at all -- not a multiplier of 1, which would read as a
    // demand running at no premium.
    assertNull(row.demandMultiplier)
    assertNull(row.demandHoursLeft)

    val wheat = data.fillTypes.single()
    assertEquals(row.type, wheat.type, "every station row joins to exactly one catalogue entry")
    assertTrue(wheat.isCrop)
    assertEquals(12, wheat.months.size)
    assertEquals(5, wheat.bestMonth)
    assertEquals(1120f, wheat.bestPrice)
    assertEquals(wheat.bestPrice, wheat.months.max(), "bestPrice is the peak of the curve it summarises")
  }

  @Test
  fun carriesAGreatDemandAsAPremiumWithATimeLimit() {
    val data =
      VdtParser.parsePrices(
        """
        {
          "version": "1",
          "stations": [
            {
              "id": "node41207",
              "name": "Spinnery",
              "sell": [
                { "type": "WOOL", "price": 2870.4, "trend": "falling",
                  "greatDemand": true, "demandMultiplier": 1.3, "demandHoursLeft": 12 }
              ]
            }
          ]
        }
        """.trimIndent(),
      )

    val row =
      data.stations
        .single()
        .sell
        .single()
    assertTrue(row.greatDemand)
    assertEquals(1.3f, assertNotNull(row.demandMultiplier))
    assertEquals(12, assertNotNull(row.demandHoursLeft))
    // Great demand is not a trend: the price is still on its way down, and the app must be able to
    // say both things at once.
    assertEquals("falling", row.trend)
    assertRoundTrips(data)
  }

  @Test
  fun oneStationCanBuyAndSellAndCountPallets() {
    val data =
      VdtParser.parsePrices(
        """
        {
          "version": "1",
          "stations": [
            {
              "id": "MyMap.farmShop",
              "name": "Farm Shop",
              "isPalletStation": true,
              "buy": [
                { "type": "DIESEL", "price": 1850.0 },
                { "type": "FERTILIZER", "price": 1200.0 }
              ],
              "pallets": [{ "type": "FERTILIZER", "price": 480.0 }]
            }
          ]
        }
        """.trimIndent(),
      )

    val station = data.stations.single()
    assertTrue(station.isPalletStation)
    assertTrue(station.sell.isEmpty(), "a shop that only sells to the player pays for nothing")
    assertEquals(listOf("DIESEL", "FERTILIZER"), station.buy.map { it.type })
    // The pallet price is for the whole pallet, so it is nowhere near the per-1000-litre buy price
    // of the same fill type -- the two columns are different units and must not be summed.
    assertEquals(480f, station.pallets.single().price)
    assertRoundTrips(data)
  }

  @Test
  fun aCommodityWithNoEconomyHasNoCurveRatherThanAFlatOne() {
    val data =
      VdtParser.parsePrices(
        """
        {
          "version": "1",
          "fillTypes": [{ "type": "WATER", "title": "Water", "basePrice": 2.5 }]
        }
        """.trimIndent(),
      )

    val water = data.fillTypes.single()
    assertTrue(water.months.isEmpty())
    // Not month 1 at price 0: there is no best month, and a table column that prints one would be
    // inventing a peak out of twelve absent values.
    assertEquals(0, water.bestMonth)
    assertEquals(0f, water.bestPrice)
    assertFalse(water.showOnPriceTable)
    assertRoundTrips(data)
  }

  @Test
  fun anEmptyBoardStillParses() {
    // What the mod writes before any station has registered, and what the app must render as "no
    // prices yet" rather than as a missing channel (the missing channel is a null message instead).
    val data = VdtParser.parsePrices("""{ "version": "1", "priceMultiplier": 1.8 }""")
    assertEquals(1.8f, data.priceMultiplier, "the difficulty factor is already folded into the prices")
    assertTrue(data.stations.isEmpty())
    assertTrue(data.fillTypes.isEmpty())
    assertEquals(0, data.period)
    assertRoundTrips(data)
  }
}
