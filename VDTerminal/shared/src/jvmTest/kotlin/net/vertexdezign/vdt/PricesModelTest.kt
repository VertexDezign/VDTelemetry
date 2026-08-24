package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.PricesData
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
 * **Inline JSON throughout, deliberately.** Fixtures in this project are real game captures and
 * never hand-authored, and no capture of this channel exists yet (FUTURE.md → "Captures wanted as
 * fixtures"). Every fill-type name and price below is therefore illustrative, and the tests pin the
 * *shape* and the absence rules rather than any map's numbers.
 *
 * What is worth pinning down is where **absent means something**: a station that only buys has no
 * empty sell list to distinguish it from one that pays nothing, a commodity with no economy has no
 * "best month" rather than a best month of January at zero, and a great demand's multiplier is null
 * on a row that has none rather than 1.
 */
class PricesModelTest {
  private val json = Json { encodeDefaults = true }

  private fun assertRoundTrips(data: PricesData) {
    val encoded = json.encodeToString(PricesData.serializer(), data)
    assertEquals(data, json.decodeFromString(PricesData.serializer(), encoded), "JSON round-trip should be lossless")
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
