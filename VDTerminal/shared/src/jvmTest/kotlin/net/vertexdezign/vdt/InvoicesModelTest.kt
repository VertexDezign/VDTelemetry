package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.InvoicesData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The invoices channel's half of the mod↔Kotlin contract: field mapping, the omission defaults, and a
 * lossless round-trip.
 *
 * **Everything here is inline synthetic JSON**, unlike the finance tests. FS25_Invoices needs two
 * farms to do anything at all, and singleplayer has one — so a real capture can only come from a
 * two-farm multiplayer session, which has not happened yet. `examples/json/` holds real game captures
 * only (a hand-written file claiming to be one was rejected before), so the capture is listed under
 * FUTURE.md → "Captures wanted as fixtures" rather than faked here. The amounts below are invented but
 * the *shapes* are the mod's, and the mod's own side of the contract is `spec/Invoices_spec.lua`.
 *
 * What this exists for is the money asymmetry: paying an invoice moves `totalDue` out of one farm and
 * `credit` into the other, and the VAT between them is destroyed. A test that only checked `total`
 * would pass while the app showed one of the two parties a number they will never see.
 */
class InvoicesModelTest {
  private val json = Json { encodeDefaults = true }

  private fun assertRoundTrips(data: InvoicesData) {
    val encoded = json.encodeToString(InvoicesData.serializer(), data)
    val decoded = json.decodeFromString(InvoicesData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  /** A populated document: one incoming invoice (overdue), one outgoing, one proposal to answer. */
  private val populated =
    """
    {
      "version": "1",
      "farmId": 1,
      "canManage": true,
      "vatEnabled": true,
      "penaltyTerms": {
        "enabled": true, "ratePercent": 5, "gracePeriods": 1, "capPercent": 25, "daysPerPeriod": 3
      },
      "farms": [ { "id": 2, "name": "Meadow Farm" }, { "id": 3 } ],
      "workTypes": [
        { "id": 2,  "name": "Plowing",      "unit": "hectare", "price": 3080,  "vatRate": 0.1 },
        { "id": 53, "name": "Goods",        "unit": "liter",   "price": 0.55,  "vatRate": 0.055 },
        { "id": 56, "name": "Vehicle sale", "unit": "piece",   "price": 0,     "needsPicker": "vehicle" }
      ],
      "invoices": [
        {
          "id": 7,
          "direction": "incoming",
          "state": "new",
          "counterpartyId": 2,
          "counterpartyName": "Meadow Farm",
          "senderFarmId": 2,
          "recipientFarmId": 1,
          "total": 11022,
          "totalNet": 10071,
          "vat": 951,
          "penalty": 551,
          "totalDue": 11573,
          "credit": 10622,
          "overdue": true,
          "date": "12.08.2026",
          "time": "14:32",
          "actions": ["pay"],
          "lines": [
            {
              "workTypeId": 2, "name": "Plowing", "quantity": 3.5, "unit": "hectare",
              "price": 3080, "amount": 9702, "vatRate": 0.1, "discountRate": 0.1,
              "fieldId": 12, "fieldArea": 3.5, "note": "north field"
            },
            {
              "workTypeId": 53, "name": "Goods", "quantity": 2400, "unit": "liter",
              "price": 550, "amount": 1320, "vatRate": 0.055
            }
          ]
        },
        {
          "id": 9,
          "direction": "outgoing",
          "state": "new",
          "counterpartyId": 3,
          "senderFarmId": 1,
          "recipientFarmId": 3,
          "total": 5000,
          "totalNet": 5000,
          "totalDue": 5000,
          "credit": 5000,
          "daysUntilPenalty": 4,
          "date": "14.08.2026",
          "time": "09:05",
          "actions": ["cancel"],
          "lines": [
            { "workTypeId": 2, "quantity": 1, "unit": "hectare", "price": 5000, "amount": 5000 }
          ]
        },
        {
          "id": 11,
          "direction": "incoming",
          "state": "proposed",
          "counterpartyId": 2,
          "counterpartyName": "Meadow Farm",
          "senderFarmId": 1,
          "recipientFarmId": 2,
          "total": 2000,
          "totalNet": 2000,
          "totalDue": 2000,
          "credit": 2000,
          "actions": ["validate", "refuse"],
          "lines": [
            { "workTypeId": 2, "quantity": 1, "unit": "hectare", "price": 2000, "amount": 2000 }
          ]
        }
      ]
    }
    """.trimIndent()

  @Test
  fun parsesAPopulatedDocument() {
    val data = VdtParser.parseInvoices(populated)

    assertEquals("1", data.version)
    assertEquals(1, data.farmId)
    assertTrue(data.canManage)
    assertTrue(data.vatEnabled)
    assertEquals(3, data.invoices.size)
    assertRoundTrips(data)
  }

  @Test
  fun carriesTheTwoSidesOfAPayment() {
    val invoice = VdtParser.parseInvoices(populated).invoices.first { it.id == 7 }

    assertEquals(11022L, invoice.total)
    assertEquals(10071L, invoice.totalNet)
    assertEquals(951L, invoice.vat)
    assertEquals(551L, invoice.penalty)
    // The payer parts with total + penalty; the issuer receives net + penalty. The 951 of VAT
    // between them is destroyed by the mod, which is why one figure would not do.
    assertEquals(11573L, invoice.totalDue)
    assertEquals(10622L, invoice.credit)
    assertEquals(11573L - 10622L, invoice.vat)
  }

  @Test
  fun sumsTheTaxInclusiveTotalInBothDirections() {
    val data = VdtParser.parseInvoices(populated)

    // The row figure is `totalDue` whichever way the invoice points -- what the mod's own list prints.
    // `credit` (what the issuer banks, net of the destroyed VAT) is a detail, not a headline: a
    // headline that disagreed with the rows under it would read as a bug.
    assertEquals(11573L, data.totalOwed)
    assertEquals(5000L, data.totalOwing)
  }

  @Test
  fun sumsWhatIsOwedInEachDirection() {
    val data = VdtParser.parseInvoices(populated)

    assertEquals(1, data.unpaidIncoming.size)
    assertEquals(11573L, data.totalOwed)
    assertEquals(1, data.unpaidOutgoing.size)
    assertEquals(5000L, data.totalOwing)
    assertTrue(data.hasOverdue)
    // A proposal is neither owed nor owing until it has been validated.
    assertTrue(data.invoices.first { it.id == 11 }.isProposal)
    assertFalse(data.invoices.first { it.id == 11 }.isPayable)
  }

  @Test
  fun surfacesProposalsWaitingOnUs() {
    val data = VdtParser.parseInvoices(populated)

    val pending = data.pendingProposals
    assertEquals(1, pending.size)
    assertEquals(11, pending.first().id)
    // The proposal inversion: it is *incoming* for us even though we are its sender, because we are
    // the issuer being asked to bill somebody.
    assertTrue(pending.first().isIncoming)
    assertEquals(1, pending.first().senderFarmId)
  }

  @Test
  fun dispatchesActionsOnPresence() {
    val data = VdtParser.parseInvoices(populated)

    val incoming = data.invoices.first { it.id == 7 }
    assertTrue(incoming.canPay)
    assertFalse(incoming.canCancel)

    val outgoing = data.invoices.first { it.id == 9 }
    assertTrue(outgoing.canCancel)
    assertFalse(outgoing.canPay)

    val proposal = data.invoices.first { it.id == 11 }
    assertTrue(proposal.canValidate)
    assertTrue(proposal.canRefuse)
    assertFalse(proposal.canPay)
  }

  @Test
  fun keepsLinePricesAndQuantitiesFractional() {
    val invoice = VdtParser.parseInvoices(populated).invoices.first { it.id == 7 }

    // A hectare figure has decimals; rounding it away would misprice every field-work line.
    assertEquals(3.5, invoice.lines.first { it.workTypeId == 2 }.quantity)

    val goods = invoice.lines.first { it.workTypeId == 53 }
    assertEquals(550.0, goods.price)
    assertEquals(2400.0, goods.quantity)
    // Priced per 1000 l: 550 x 2400 / 1000. Without the divisor the derived gross below would be
    // 1,320,000 and the line would read as a six-figure discount.
    assertEquals(1320L, goods.amount)
    assertEquals(1320L, goods.grossAmount)
    assertEquals(0L, goods.discountAmount)
  }

  @Test
  fun derivesWhatADiscountTookOff() {
    val line =
      VdtParser
        .parseInvoices(populated)
        .invoices
        .first { it.id == 7 }
        .lines
        .first()

    assertEquals(0.1, line.discountRate)
    // The mod's own arithmetic: gross is recomputed from 3080 x 3.5, and the rebate is the difference
    // against the invoiced amount -- NOT amount / (1 - rate), which rounds differently.
    assertEquals(10780L, line.grossAmount)
    assertEquals(1078L, line.discountAmount)
  }

  @Test
  fun sumsWhatTheDiscountsTookOffTheWholeInvoice() {
    val data = VdtParser.parseInvoices(populated)

    // What the mod prints in its own list row and in the detail dialog's HT / Discount / VAT footer.
    assertEquals(1078L, data.invoices.first { it.id == 7 }.discountTotal)
    assertEquals(0L, data.invoices.first { it.id == 9 }.discountTotal)
  }

  @Test
  fun leavesADiscountFreeLineAlone() {
    val line =
      VdtParser
        .parseInvoices(populated)
        .invoices
        .first { it.id == 9 }
        .lines
        .first()

    assertNull(line.discountRate)
    assertEquals(line.amount, line.grossAmount)
    assertEquals(0L, line.discountAmount)
  }

  @Test
  fun flagsTheWorkTypesTheTerminalCannotBuild() {
    val data = VdtParser.parseInvoices(populated)

    val vehicleSale = data.workTypes.first { it.id == 56 }
    assertEquals("vehicle", vehicleSale.needsPicker)
    assertFalse(vehicleSale.isUsable)
    // Listed, but not offered: 2 of the 3 rows can go on a terminal-raised invoice.
    assertEquals(2, data.usableWorkTypes.size)
    assertTrue(data.canIssue)
  }

  @Test
  fun statesHowAPriceShouldBeRead() {
    val data = VdtParser.parseInvoices(populated)

    // A liter row prices a *thousand* litres — the mod's rule, and the one the app must print.
    assertEquals("1000 l", data.workTypes.first { it.id == 53 }.priceUnitLabel)
    assertEquals("ha", data.workTypes.first { it.id == 2 }.priceUnitLabel)
  }

  @Test
  fun namesAFarmThatHasNoName() {
    val data = VdtParser.parseInvoices(populated)

    assertEquals("Meadow Farm", data.farms.first { it.id == 2 }.label)
    assertEquals("Farm 3", data.farms.first { it.id == 3 }.label)
    // Same fallback on an invoice row, where the id is all the mod could give us.
    assertEquals("Farm 3", data.invoices.first { it.id == 9 }.counterpartyLabel)
  }

  @Test
  fun readsTheServersPenaltyTerms() {
    val terms = assertNotNull(VdtParser.parseInvoices(populated).penaltyTerms)

    assertTrue(terms.enabled)
    assertEquals(5.0, terms.ratePercent)
    assertEquals(1, terms.gracePeriods)
    assertEquals(25.0, terms.capPercent)
    assertEquals(3, terms.daysPerPeriod)
  }

  @Test
  fun countsDownToTheFirstPenalty() {
    val data = VdtParser.parseInvoices(populated)

    assertEquals(4, data.invoices.first { it.id == 9 }.daysUntilPenalty)
    // Already accruing: the countdown is over and the mod stops sending it.
    assertNull(data.invoices.first { it.id == 7 }.daysUntilPenalty)
  }

  @Test
  fun parsesAnInstalledButEmptyDocument() {
    // What singleplayer will always produce: the mod is up, the catalogue is real, but there is
    // nobody to bill. Distinct from the channel being absent, which the server sends as a null.
    val data =
      VdtParser.parseInvoices(
        """
        {
          "version": "1",
          "farmId": 1,
          "canManage": true,
          "vatEnabled": true,
          "penaltyTerms": {
            "enabled": true, "ratePercent": 5, "gracePeriods": 1, "capPercent": 25, "daysPerPeriod": 3
          },
          "workTypes": [
            { "id": 2, "name": "Plowing", "unit": "hectare", "price": 3080, "vatRate": 0.1 }
          ]
        }
        """.trimIndent(),
      )

    assertTrue(data.invoices.isEmpty())
    assertTrue(data.farms.isEmpty())
    assertEquals(0L, data.totalOwed)
    assertFalse(data.hasOverdue)
    // Nothing to bill means the builder must not be offered, even though everything else is present.
    assertFalse(data.canIssue)
    assertRoundTrips(data)
  }

  @Test
  fun parsesASpectatorDocument() {
    val data = VdtParser.parseInvoices("""{ "version": "1" }""")

    assertNull(data.farmId)
    assertFalse(data.canManage)
    assertTrue(data.invoices.isEmpty())
    assertTrue(data.workTypes.isEmpty())
    assertNull(data.penaltyTerms)
    assertFalse(data.canIssue)
  }

  @Test
  fun offersNothingWithoutTheManageRight() {
    val data =
      VdtParser.parseInvoices(
        """
        {
          "version": "1", "farmId": 1, "canManage": false,
          "farms": [ { "id": 2, "name": "Meadow Farm" } ],
          "workTypes": [ { "id": 2, "name": "Plowing", "unit": "hectare", "price": 3080 } ],
          "invoices": [
            {
              "id": 1, "direction": "incoming", "state": "new",
              "senderFarmId": 2, "recipientFarmId": 1,
              "total": 100, "totalNet": 100, "totalDue": 100, "credit": 100
            }
          ]
        }
        """.trimIndent(),
      )

    val invoice = data.invoices.single()
    // The mod omits `actions` entirely rather than sending an empty list, so this is the omission
    // default doing the work — the app must show the invoice but offer no buttons.
    assertTrue(invoice.actions.isEmpty())
    assertFalse(invoice.canPay)
    assertFalse(data.canIssue)
    // Still counted as owed: not being allowed to pay it does not mean it is not due.
    assertEquals(100L, data.totalOwed)
  }

  @Test
  fun toleratesATokenThisBuildDoesNotKnow() {
    // The tokens are strings, not enums, precisely so a mod that grows a state or an action cannot
    // break the parse of the whole document — and an unknown action in a *list* is what an enum
    // would choke on hardest.
    val data =
      VdtParser.parseInvoices(
        """
        {
          "version": "1", "farmId": 1, "canManage": true,
          "invoices": [
            {
              "id": 1, "direction": "incoming", "state": "disputed",
              "senderFarmId": 2, "recipientFarmId": 1,
              "total": 100, "totalNet": 100, "totalDue": 100, "credit": 100,
              "actions": ["pay", "escalate"]
            }
          ]
        }
        """.trimIndent(),
      )

    val invoice = data.invoices.single()
    assertEquals("disputed", invoice.state)
    assertFalse(invoice.isPayable)
    assertFalse(invoice.isPaid)
    // The action we do understand still works; the one we do not is carried and ignored.
    assertTrue(invoice.canPay)
    assertEquals(listOf("pay", "escalate"), invoice.actions)
  }
}
