package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.FinanceData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The finance channel's half of the mod↔Kotlin contract: field mapping, the omission defaults, and a
 * lossless round-trip.
 *
 * Driven by the committed `examples/json/finance/vanilla.json` capture where it can be. That capture is
 * a fresh singleplayer save, so it covers exactly one period and carries no log — the shapes it cannot
 * show (several periods, a populated log, a client with no manage right, a spectator) are inline JSON
 * below, with the game's real `FinanceStats` names and invented amounts. More captures are wanted; see
 * FUTURE.md. The mod's side of the same contract is `spec/FinanceExporter_spec.lua`.
 *
 * The column alignment is what this exists for: `stats[].values[i]` belongs to `periods[i]`, and
 * getting that off by one would silently attribute every figure to the wrong month.
 */
class FinanceModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/finance/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/finance/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: FinanceData) {
    val encoded = json.encodeToString(FinanceData.serializer(), data)
    val decoded = json.decodeFromString(FinanceData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesTheVanillaCapture() {
    val data = VdtParser.parseFinance(example("vanilla.json"))

    assertEquals("1", data.version)
    assertTrue(data.hasFarm)
    assertEquals(95506440L, data.balance)
    assertEquals(0L, data.loan)
    // Equity-derived rather than the Farm.MIN_LOAN floor, so farm.loanMax was live when this was taken.
    assertEquals(800000L, data.loanMax)
    assertEquals(0L, data.loanInterestPerDay)
    assertTrue(data.loansAvailable)
    assertTrue(data.canManageLoan)

    // A fresh save: financesHistory is empty, so the table is a single column. The app must not assume
    // the in-game five.
    assertEquals(1, data.periods.size)
    val august = data.periods[0]
    assertTrue(august.current)
    assertEquals(6, august.period)
    assertEquals(2024, august.year)
    // The game hands over its own localization — this capture is a German client.
    assertEquals("August", august.label)
    assertEquals("Neue Fahrzeuge", data.stats.first { it.name == "newVehiclesCost" }.title)

    // Every row carries exactly one value, one per period.
    for (row in data.stats) {
      assertEquals(1, row.values.size, "row ${row.name}")
    }
    // The footer figure is the column summed, as the in-game screen prints it.
    assertEquals(data.stats.sumOf { it.values[0] }, august.total)

    // 34 rows: the base game's 33 `FinanceStats` buckets plus `dryingCharge`, which a mod in the
    // savegame this was taken from contributed. **The count is not an invariant** — it is a property of
    // this capture, and another save will have another number. What matters is that the extra bucket is
    // carried at all: the exporter walks the live `FinanceStats.statNames` instead of a hardcoded list,
    // so a third-party bucket flows through with its localized title and needs no change here. Asserted
    // as the regression guard for anyone tempted to hardcode the base-game 33.
    assertEquals(34, data.stats.size)
    val modded = data.stats.first { it.name == "dryingCharge" }
    assertEquals("Trocknungsgebühren", modded.title)

    // No notifications had popped when this was taken; the log is session-scoped mod-side.
    assertTrue(data.history.isEmpty())

    assertRoundTrips(data)
  }

  /**
   * What the vanilla capture cannot show, because it is a fresh save: six months of columns, a running
   * loan, and a log holding the two shapes an app has to survive — a money type with no stat row, and a
   * change the game left unlabelled. Real bucket names, invented amounts.
   */
  private val singleplayer =
    """
    {
      "version": "1",
      "balance": 1284310, "loan": 300000, "loanMax": 750000,
      "loanStep": 5000, "loanInterestPerDay": 1000,
      "loansAvailable": true, "canManageLoan": true,
      "periods": [
        { "index": 0, "period": 6, "label": "August", "year": 2026, "current": true, "total": 132390 },
        { "index": 1, "period": 5, "label": "July",   "year": 2026, "total": 202080 },
        { "index": 2, "period": 4, "label": "June",   "year": 2026, "total": 131870 },
        { "index": 3, "period": 3, "label": "May",    "year": 2026, "total": -79533 },
        { "index": 4, "period": 2, "label": "April",  "year": 2026, "total": -273966 },
        { "index": 5, "period": 1, "label": "March",  "year": 2026, "total": -10086 }
      ],
      "stats": [
        { "name": "harvestIncome", "title": "Harvest income",
          "values": [128450, 342100, 96200, 0, 0, 51300] },
        { "name": "purchaseFuel", "title": "Fuel",
          "values": [-9840, -21350, -7620, -3110, -2480, -6970] },
        { "name": "fieldPurchase", "title": "Purchased fields",
          "values": [0, 0, 0, 0, -240000, 0] },
        { "name": "soldWool", "title": "Sold wool",
          "values": [0, 0, 0, 0, 0, 0] },
        { "name": "loanInterest", "title": "Loan interest",
          "values": [-1000, -1000, -1000, -833, -666, -666] }
      ],
      "history": [
        { "seq": 47, "amount": 128450, "type": "harvestIncome", "title": "Harvest income",
          "date": "12.08.2026", "time": "14:32" },
        { "seq": 46, "amount": -1250, "type": "purchaseFuel", "title": "Fuel",
          "date": "12.08.2026", "time": "13:58" },
        { "seq": 44, "amount": 50000, "type": "loan", "title": "Other",
          "date": "11.08.2026", "time": "09:20" },
        { "seq": 43, "amount": -320, "type": "other",
          "date": "10.08.2026", "time": "17:41" }
      ]
    }
    """.trimIndent()

  @Test
  fun parsesTheLoanBlock() {
    val data = VdtParser.parseFinance(singleplayer)

    assertEquals("1", data.version)
    assertTrue(data.hasFarm)
    assertEquals(1284310L, data.balance)
    assertEquals(300000L, data.loan)
    assertEquals(750000L, data.loanMax)
    assertEquals(5000, data.loanStep)
    assertEquals(1000L, data.loanInterestPerDay)
    assertTrue(data.loansAvailable)
    assertTrue(data.canManageLoan)
    assertEquals(450000L, data.loanHeadroom)

    assertRoundTrips(data)
  }

  @Test
  fun periodsRunNewestFirst() {
    val data = VdtParser.parseFinance(singleplayer)

    assertEquals(6, data.periods.size)
    val current = data.periods[0]
    assertEquals(0, current.index)
    assertEquals(6, current.period)
    assertEquals("August", current.label)
    assertEquals(2026, current.year)
    assertTrue(current.current)

    // Only index 0 is flagged current; the rest are settled months.
    assertEquals("July", data.periods[1].label)
    assertFalse(data.periods[1].current)
    assertEquals(1, data.periods[5].period)
  }

  @Test
  fun statRowsAreColumnAligned() {
    val data = VdtParser.parseFinance(singleplayer)

    for (row in data.stats) {
      assertEquals(data.periods.size, row.values.size, "row ${row.name} must have one value per period")
    }

    val harvest = data.stats.first { it.name == "harvestIncome" }
    assertEquals("Harvest income", harvest.title)
    assertEquals(128450L, harvest.values[0])
    assertEquals(342100L, harvest.values[1])
    assertFalse(harvest.isEmpty)

    // The land purchase landed in April (index 4), not in the current month — the alignment this test
    // exists for. Reversing the array would put it in July and nothing else would complain.
    val fields = data.stats.first { it.name == "fieldPurchase" }
    assertEquals(-240000L, fields.values[4])
    assertEquals(0L, fields.values[0])

    // An untouched bucket is still exported as a row — the app decides whether to show it.
    assertTrue(data.stats.first { it.name == "soldWool" }.isEmpty)
  }

  @Test
  fun historyIsNewestFirstAndToleratesOmittedFields() {
    val data = VdtParser.parseFinance(singleplayer)

    assertEquals(4, data.history.size)
    assertEquals(data.history.map { it.seq }.sortedDescending(), data.history.map { it.seq })

    val newest = data.history[0]
    assertEquals(47, newest.seq)
    assertEquals(128450L, newest.amount)
    assertEquals("harvestIncome", newest.type)
    assertEquals("Harvest income", newest.title)
    assertEquals("12.08.2026", newest.date)
    assertEquals("14:32", newest.time)

    // A loan movement carries MoneyType.LOAN's statistic, which is not a FinanceStats bucket — so it
    // joins to no stat row, and the app must not assume it can.
    val loanEvent = data.history.first { it.type == "loan" }
    assertNull(data.stats.firstOrNull { it.name == loanEvent.type })

    // An unlabelled change: the mod omits the key rather than emitting an empty string.
    assertNull(data.history.first { it.seq == 43 }.title)

    assertRoundTrips(data)
  }

  @Test
  fun aClientCaptureIsShortAndMayLackTheManageRight() {
    // A multiplayer client only ever gets the five periods the server sends, so the app must render a
    // short table — and a farmhand has no farmManager right, so borrow/repay must not be offered.
    val data =
      VdtParser.parseFinance(
        """
        {
          "version": "1",
          "balance": 486200, "loan": 0, "loanMax": 500000,
          "loanStep": 5000, "loanInterestPerDay": 0,
          "loansAvailable": true, "canManageLoan": false,
          "periods": [
            { "index": 0, "period": 2, "label": "April",    "year": 2027, "current": true, "total": -4300 },
            { "index": 1, "period": 1, "label": "March",    "year": 2027, "total": -6300 },
            { "index": 2, "period": 12, "label": "February", "year": 2026, "total": -8250 },
            { "index": 3, "period": 11, "label": "January",  "year": 2026, "total": 184320 },
            { "index": 4, "period": 10, "label": "December", "year": 2026, "total": 41600 }
          ],
          "stats": [
            { "name": "harvestIncome", "title": "Harvest income", "values": [0, 0, 88300, 214000, 42100] }
          ],
          "history": []
        }
        """.trimIndent(),
      )

    assertEquals(5, data.periods.size)
    assertEquals(5, data.stats[0].values.size)
    // The year rolls backwards out of period 2, which is why each column carries its own.
    assertEquals(2027, data.periods[0].year)
    assertEquals(2026, data.periods[2].year)

    assertTrue(data.loansAvailable)
    assertFalse(data.canManageLoan)
    assertTrue(data.history.isEmpty())

    assertRoundTrips(data)
  }

  @Test
  fun aReplacedLoanSystemOmitsTheWholeBaseGameBlock() {
    // FS25_EnhancedLoanSystem replaces base-game loans, so the mod drops the block rather than
    // publishing figures whose subject no longer exists. The books themselves are unaffected.
    val data =
      VdtParser.parseFinance(
        """
        {
          "version": "1",
          "balance": 240500,
          "loansAvailable": false, "canManageLoan": true,
          "periods": [
            { "index": 0, "period": 6, "label": "August", "year": 2026, "current": true, "total": -8200 }
          ],
          "stats": [
            { "name": "loan", "title": "Kredit", "values": [-8200] }
          ]
        }
        """.trimIndent(),
      )

    assertTrue(data.hasFarm)
    assertEquals(240500L, data.balance)
    assertFalse(data.loansAvailable)

    // Null, not zero: the app must hide the section, never print a debt-free farm that may owe the
    // replacement a great deal.
    assertNull(data.loan)
    assertNull(data.loanMax)
    assertNull(data.loanInterestPerDay)
    assertEquals(0L, data.loanHeadroom)

    // ELS appends its own bucket to FinanceStats.statNames, so the table carries a loan row it never
    // had before — the same live-table read that carried the modded row in the capture above.
    assertEquals("Kredit", data.stats.first { it.name == "loan" }.title)

    assertRoundTrips(data)
  }

  @Test
  fun parsesTheEnhancedLoanSystemBlock() {
    val data =
      VdtParser.parseFinance(
        """
        {
          "version": "1",
          "balance": 240500,
          "loansAvailable": false, "canManageLoan": true,
          "enhancedLoans": {
            "canManage": true, "maxAmount": 486200,
            "interest": 3.5, "dynamicInterest": true, "maxDurationYears": 20,
            "redemptionFraction": 0.05,
            "loans": [
              { "id": 3, "amount": 120000, "restAmount": 0, "interest": 2.9,
                "durationYears": 5, "restMonths": 0, "monthlyRate": 2148,
                "monthlyInterest": 0, "paidOff": true, "specialRedemptionDone": true },
              { "id": 8, "amount": 200000, "restAmount": 184320, "interest": 3.5,
                "durationYears": 20, "restMonths": 221, "monthlyRate": 1159,
                "monthlyInterest": 538, "totalCost": 278160 }
            ]
          }
        }
        """.trimIndent(),
      )

    val els = assertNotNull(data.enhancedLoans)
    assertFalse(data.loansAvailable, "the base-game block must be gone when a replacement is present")
    assertNull(data.loan)

    assertTrue(els.canManage)
    assertEquals(486200L, els.maxAmount)
    assertEquals(3.5f, els.interest)
    assertTrue(els.dynamicInterest)
    assertEquals(20, els.maxDurationYears)
    // Absent means false — one special redemption per loan per year, and the fraction cap applies.
    assertFalse(els.multipleRedemptions)
    assertEquals(0.05f, els.redemptionFraction)

    // Paid-off and running arrive in one list; only the running ones count toward what is owed.
    assertEquals(2, els.loans.size)
    assertEquals(1, els.running.size)
    assertEquals(184320L, els.totalOutstanding)
    assertEquals(1159L, els.totalMonthlyRate)

    val running = els.running.single()
    assertEquals(8, running.id)
    assertEquals(221, running.restMonths)
    assertEquals(278160L, running.totalCost)
    // The instalment splits into interest and principal.
    assertEquals(621L, running.monthlyPrincipal)
    assertEquals((200000 - 184320).toFloat() / 200000, running.progress)

    // A cleared loan keeps its record but drops the figure that no longer means anything.
    val cleared = els.loans.first { it.paidOff }
    assertEquals(0L, cleared.restAmount)
    assertNull(cleared.totalCost)
    assertEquals(1f, cleared.progress)

    assertRoundTrips(data)
  }

  @Test
  fun spectatorHasNoFarmRatherThanAZeroBalance() {
    // The mod keeps the channel present but omits every money field when there is no farm to report on.
    val data = VdtParser.parseFinance("""{"version":"1"}""")

    assertEquals("1", data.version)
    assertFalse(data.hasFarm)
    assertNull(data.balance)
    assertNull(data.loan)
    assertNull(data.loanMax)
    assertTrue(data.periods.isEmpty())
    assertTrue(data.stats.isEmpty())
    assertTrue(data.history.isEmpty())

    assertRoundTrips(data)
  }

  @Test
  fun toleratesAnEmptyDocument() {
    // The mod running ahead of the terminal, or a torn read that still parsed: defaults, no throw.
    val data = VdtParser.parseFinance("{}")

    assertNotNull(data)
    assertEquals("", data.version)
    assertFalse(data.hasFarm)
    assertEquals(5000, data.loanStep)
    // Absent means "assume the platform has loans" — the PC truth, and the mod only says otherwise.
    assertTrue(data.loansAvailable)
    assertFalse(data.canManageLoan)
    assertEquals(0L, data.loanHeadroom)
  }
}
