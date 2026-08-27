package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **invoices** channel the mod writes to `invoices.json` — billing between farms,
 * from the FS25_Invoices mod (see the mod's `src/integrations/Invoices.lua`).
 *
 * The channel is event-driven: the mod hooks that mod's own "something changed" funnel, so a document
 * arrives when an invoice is raised, paid, withdrawn, answered or penalised, and not otherwise.
 *
 * **Absence of the file means the mod is not installed**, which is why the server broadcasts a null
 * rather than an empty document — the app must say "not installed" rather than "no invoices". An
 * installed mod with nothing to show sends a document with an empty [invoices] list, which is exactly
 * what singleplayer will always produce: an invoice needs two different farms and singleplayer has one.
 *
 * Its own [version], independent of [VdtData.version].
 *
 * Amounts are [Long] for the same reason the finance channel's are — the engine stores money as a
 * float and a long-running farm outgrows `Int`. Line prices and quantities are **not** rounded: a
 * hectare figure has decimals and a `liter` work type is priced per 1000 l, so those stay [Double].
 */
@Serializable
data class InvoicesData(
  val version: String = "",
  /**
   * The local farm. Null for a spectator, who still gets the catalogues but owes and is owed nothing.
   * Carried so the app can tell the two sides of an invoice apart without inferring it.
   */
  val farmId: Int? = null,
  /**
   * Whether this player holds the game's `farmManager` right, which every one of the mod's own network
   * events checks. When false, [Invoice.actions] is empty everywhere and the app offers nothing.
   */
  val canManage: Boolean = false,
  /** Whether the server simulates VAT at all. */
  val vatEnabled: Boolean = false,
  val penaltyTerms: PenaltyTerms? = null,
  /**
   * The farms that can be billed — every farm but the local one and the spectator. Empty in
   * singleplayer, which is the app's cue that issuing an invoice is not possible here.
   */
  val farms: List<InvoiceFarm> = emptyList(),
  /** The mod's work types, priced for **this** server's difficulty and `vatRates.xml`. */
  val workTypes: List<WorkType> = emptyList(),
  /** Both directions in one list, flagged by [Invoice.direction], sorted by id. */
  val invoices: List<Invoice> = emptyList(),
) {
  /** Invoices this farm owes and has not yet settled. */
  val unpaidIncoming: List<Invoice> get() = invoices.filter { it.isIncoming && it.isPayable }

  /** Invoices this farm has issued and is still waiting to be paid for. */
  val unpaidOutgoing: List<Invoice> get() = invoices.filter { it.isOutgoing && it.isPayable }

  /** Proposals waiting on **this** player to validate or refuse. */
  val pendingProposals: List<Invoice> get() = invoices.filter { it.isProposal && it.canValidate }

  /** Total this farm owes, penalties included. */
  val totalOwed: Long get() = unpaidIncoming.sumOf { it.totalDue }

  /**
   * Total this farm is owed — the tax-inclusive amount billed, which is what the mod's own list prints
   * on every row in both directions. Not [Invoice.credit]: what the issuer actually banks is smaller,
   * because the mod destroys the VAT, and that difference belongs in an invoice's detail where there is
   * room to say so rather than in a headline that has to agree with the rows under it.
   */
  val totalOwing: Long get() = unpaidOutgoing.sumOf { it.totalDue }

  /** Whether anything this farm owes has gone past its grace period. */
  val hasOverdue: Boolean get() = unpaidIncoming.any { it.overdue }

  /** Whether an invoice can be issued at all from here — there has to be somebody to bill. */
  val canIssue: Boolean get() = canManage && farms.isNotEmpty() && workTypes.any { it.isUsable }

  /** The work types the terminal can actually put on an invoice (see [WorkType.needsPicker]). */
  val usableWorkTypes: List<WorkType> get() = workTypes.filter { it.isUsable }
}

/**
 * The tokens the mod publishes. String constants rather than an `enum class` for the same reason
 * `ProductionLine.status` is a string: a token the mod adds must not break the parse, and an unknown
 * value inside a **list** (which [Invoice.actions] is) would do exactly that under kotlinx's enum
 * decoding — `coerceInputValues` rescues a property, not a list element.
 */
object InvoiceTokens {
  /** We are being billed — or, for a proposal, we are the issuer being asked to bill someone. */
  const val INCOMING = "incoming"

  /** We are billing — or, for a proposal, we are the payer who raised it. */
  const val OUTGOING = "outgoing"

  /** Raised and unpaid. */
  const val NEW = "new"

  /** Settled. */
  const val PAID = "paid"

  /** Raised by the payer, waiting for the named issuer to validate it. Not payable until they do. */
  const val PROPOSED = "proposed"

  /** Settle an invoice billed to us. */
  const val PAY = "pay"

  /** Withdraw one we raised — our own unpaid invoice, or a proposal we asked for. */
  const val CANCEL = "cancel"

  /** Turn a proposal addressed to us into a real invoice. */
  const val VALIDATE = "validate"

  /** Reject a proposal addressed to us. */
  const val REFUSE = "refuse"
}

/** A farm that can be billed. */
@Serializable
data class InvoiceFarm(val id: Int = 0, val name: String? = null) {
  /** What to print — the game does not always have a name. */
  val label: String get() = name ?: "Farm $id"
}

/**
 * One row of the mod's work-type catalogue, priced for this server.
 *
 * Exported rather than hardcoded in the app because [price] carries the server's economic difficulty
 * and [vatRate] its `vatRates.xml` — and because a future version of the mod adding a work type then
 * costs nothing here.
 */
@Serializable
data class WorkType(
  /** The mod's own id — the stable key, and what a command carries. */
  val id: Int = 0,
  /** Localized label, resolved out of the mod's own i18n environment. */
  val name: String = "",
  /** `piece` | `hour` | `hectare` | `liter`. Derived from the mod's constant **names**, not numbers. */
  val unit: String = "piece",
  /**
   * The difficulty-adjusted unit price. **Not** whole currency units, and on a `liter` row it is the
   * price per **1000 l** — the mod's own pricing rule, and the one thing about this catalogue that
   * will read as a hundredfold error if the app forgets it.
   */
  val price: Double = 0.0,
  /** The effective VAT fraction (`0.2` is 20%); null when the server has VAT simulation off. */
  val vatRate: Double? = null,
  /**
   * Set on the three rows the in-game wizard builds through a picker the terminal does not have —
   * `vehicle`, `consumable`, `fillType`. Their line items transfer ownership of real objects on
   * payment, which a command cannot assemble, so the mod refuses one and the app must not offer it.
   * Listed rather than dropped so the app can say *why* it is missing.
   */
  val needsPicker: String? = null,
) {
  /** Whether this row can go on an invoice raised from the terminal. */
  val isUsable: Boolean get() = needsPicker == null

  /** The quantity label for this unit, e.g. `3.5 ha`. */
  val unitLabel: String
    get() =
      when (unit) {
        "hectare" -> "ha"
        "hour" -> "h"
        "liter" -> "l"
        else -> "pc"
      }

  /** How [price] should be read: a `liter` row prices a thousand litres, everything else one unit. */
  val priceUnitLabel: String get() = if (unit == "liter") "1000 l" else unitLabel
}

/** One line of an invoice. */
@Serializable
data class InvoiceLine(
  /** Joins to [WorkType.id]. */
  val workTypeId: Int = 0,
  /** The label the line was created with. */
  val name: String? = null,
  /** Hectares / hours / pieces / litres, per [unit]. */
  val quantity: Double = 0.0,
  val unit: String = "piece",
  /** Unit price as invoiced — per 1000 l on a `liter` line. Fractional; never rounded. */
  val price: Double = 0.0,
  /** The line's tax-inclusive total, **after** its discount. */
  val amount: Long = 0,
  /** The VAT fraction inside [amount]; null when zero. */
  val vatRate: Double? = null,
  /** Fraction knocked off before VAT was extracted; null when zero. */
  val discountRate: Double? = null,
  /** The field this line was billed for, when it is field work. */
  val fieldId: Int? = null,
  /** That field's area, in hectares. */
  val fieldArea: Double? = null,
  /** Free text the issuer typed. */
  val note: String? = null,
) {
  /**
   * What the line would have cost without its discount — recomputed from price × quantity exactly as
   * the mod's `Invoice.computeLineGross` does, **not** reconstructed by dividing [amount] back out by
   * the rate. The mod rounds twice (once on the gross, once after the discount), so the reconstruction
   * disagrees with it by a unit or two, and this figure is subtracted to produce money on screen.
   */
  val grossAmount: Long
    get() {
      val gross = if (unit == "liter") price * quantity / 1000 else price * quantity
      return kotlin.math.round(gross).toLong()
    }

  /**
   * The money the discount actually took off, never negative — the mod's own
   * `Invoice.computeLineDiscountAmount`, which likewise takes the difference rather than trusting
   * [discountRate].
   */
  val discountAmount: Long get() = (grossAmount - amount).coerceAtLeast(0)
}

/** The server's late-payment rules, so a penalty can be explained rather than merely printed. */
@Serializable
data class PenaltyTerms(
  /** Whether penalties accrue at all. */
  val enabled: Boolean = false,
  /** Percent of the invoice total per elapsed month. */
  val ratePercent: Double = 0.0,
  /** Months of grace before the first penalty lands. */
  val gracePeriods: Int = 0,
  /** The ceiling, as a percent of the total. */
  val capPercent: Double = 0.0,
  /** In-game days in a month on this server — what the clock actually counts. */
  val daysPerPeriod: Int = 1,
)

/**
 * One invoice, from the **local farm's** point of view.
 *
 * Paying one moves two different numbers: the payer loses [totalDue] while the issuer gains [credit],
 * and the VAT between them is destroyed — collected by nobody. That is the mod's simulation, not a
 * rounding error, and it is why a single "total" would be a lie to one of the two parties.
 */
@Serializable
data class Invoice(
  /**
   * The mod's repository id — server-assigned and unique per savegame. Unlike the ELS loans' network
   * object ids this one survives a save/load, but a command carrying a stale one must still fail to
   * resolve rather than act on whatever now holds it.
   */
  val id: Int = 0,
  /**
   * Which way it points for us. Computed by the mod with its own rule, which **inverts for proposals**:
   * a proposal is raised by the payer, so it is outgoing for them and incoming for the issuer who has
   * to answer it.
   */
  val direction: String = "",
  /** `new` | `paid` | `proposed`. See [InvoiceTokens]. */
  val state: String = "",
  /** The other farm — who is billing us, or who we are billing. */
  val counterpartyId: Int? = null,
  val counterpartyName: String? = null,
  /** The issuer; money flows to them. */
  val senderFarmId: Int = 0,
  /** The payer; money flows from them. */
  val recipientFarmId: Int = 0,
  /** Tax-inclusive total, before any penalty. */
  val total: Long = 0,
  /** Total excluding VAT. */
  val totalNet: Long = 0,
  /** The VAT inside [total]; null when zero. */
  val vat: Long? = null,
  /** Accrued late penalty, on **top** of [total]; null when none. */
  val penalty: Long? = null,
  /** What the payer parts with: [total] + [penalty]. */
  val totalDue: Long = 0,
  /** What the issuer receives: [totalNet] + [penalty]. */
  val credit: Long = 0,
  /** True once a penalty has accrued — the mod's own definition of overdue. */
  val overdue: Boolean = false,
  /**
   * In-game days until the first penalty accrues, floored at zero. Null when the question does not
   * apply: penalties off, one already accrued, or a state that never accrues. Zero means the grace
   * has run out — accrual only runs on the last day of a month, so it can sit there for a few days.
   */
  val daysUntilPenalty: Int? = null,
  /** In-game creation date, `DD.MM.YYYY` — reads the same as `environment.date`. */
  val date: String? = null,
  /** In-game creation time, `HH:MM`. */
  val time: String? = null,
  /**
   * What this player may do, mirroring the mod's own server-side checks. The app's buttons dispatch on
   * **presence** here rather than restating the rules. Says nothing about affordability: the balance
   * moves faster than this channel writes, so Pay is greyed against the finance channel instead.
   */
  val actions: List<String> = emptyList(),
  val lines: List<InvoiceLine> = emptyList(),
) {
  val isIncoming: Boolean get() = direction == InvoiceTokens.INCOMING

  val isOutgoing: Boolean get() = direction == InvoiceTokens.OUTGOING

  /** Waiting to be settled — not paid, and not still a proposal. */
  val isPayable: Boolean get() = state == InvoiceTokens.NEW

  val isProposal: Boolean get() = state == InvoiceTokens.PROPOSED

  val isPaid: Boolean get() = state == InvoiceTokens.PAID

  val canPay: Boolean get() = InvoiceTokens.PAY in actions

  val canCancel: Boolean get() = InvoiceTokens.CANCEL in actions

  val canValidate: Boolean get() = InvoiceTokens.VALIDATE in actions

  val canRefuse: Boolean get() = InvoiceTokens.REFUSE in actions

  /**
   * What the discounts across all lines took off, as money — the mod's `computeTotalDiscountAmount`,
   * which it prints in its own list row and in the detail dialog's HT / Discount / VAT breakdown. Zero
   * when nothing was discounted.
   */
  val discountTotal: Long get() = lines.sumOf { it.discountAmount }

  /** Who to name in a list row. */
  val counterpartyLabel: String get() = counterpartyName ?: counterpartyId?.let { "Farm $it" } ?: "—"
}
