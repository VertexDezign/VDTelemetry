package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **finance** channel the mod writes to `finance.json` (separate file, interval
 * plus event-driven — see the mod's `src/collect/FinanceExporter.lua`): the local farm's books, the
 * same three things the in-game finances screen shows — the balance and loan, the month-by-month
 * table, and the money notifications as a running log.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 *
 * The money fields are nullable rather than defaulting to zero because the mod omits them outright
 * when there is no farm to report on (a spectator): "no farm" and "broke" are different statements,
 * and a dashboard must not print `0 €` for the first. See [hasFarm].
 *
 * Amounts are [Long]: the engine stores money as a float and a savegame's default is ten digits, so
 * `Int` is not wide enough for a long-running farm.
 */
@Serializable
data class FinanceData(
  val version: String = "",
  /** The farm's money. Null when no farm is resolved. */
  val balance: Long? = null,
  /** Outstanding loan. Null when no farm is resolved. */
  val loan: Long? = null,
  /**
   * The borrowing ceiling — the game's own `farm.loanMax`, read rather than recomputed (deriving it
   * means walking every farmland and placeable on the map; the in-game screen reads the cached field
   * too). Null when no farm is resolved.
   */
  val loanMax: Long? = null,
  /**
   * The in-game borrow/repay granularity, 5000. The same grid [loanMax] is snapped to, so the app's
   * stepper should move in multiples of it.
   */
  val loanStep: Int = 5000,
  /** What the current loan costs per in-game day at the game's 4% annual rate. */
  val loanInterestPerDay: Long? = null,
  /**
   * Whether the **base-game** loan is in play. False on a platform without loans, and false when a
   * mod has replaced the loan system outright — FS25_EnhancedLoanSystem does, and it deactivates the
   * base loan by overwriting the in-game frame's own permission check, so neither the platform flag
   * nor the farmManager right would give it away.
   *
   * When false the mod omits [loan], [loanMax], [loanStep] and [loanInterestPerDay] entirely, and the
   * app must hide the whole loan section rather than print their defaults: a `0` there would claim a
   * debt-free farm that may in fact owe the replacement a great deal.
   */
  val loansAvailable: Boolean = true,
  /**
   * Whether this player holds the game's `farmManager` right. Drives whether the app offers
   * borrow/repay at all — the server re-checks it when the event lands, so this is the UI gate, not
   * the boundary.
   */
  val canManageLoan: Boolean = false,
  /**
   * The table's columns, **newest first**: index 0 is the period being played. Up to a year of them
   * where the game has that much; a multiplayer client only ever gets five (the server sends four
   * archived periods and no more), and a savegame reload drops back to five.
   */
  val periods: List<FinancePeriod> = emptyList(),
  /**
   * The table's rows — one per `FinanceStats` bucket, **every** one of them including the all-zero
   * rows, so hiding empties stays a view choice. [FinanceStatRow.values] is index-aligned with
   * [periods].
   */
  val stats: List<FinanceStatRow> = emptyList(),
  /** The money notifications the HUD popped this session, newest first. Session-scoped; not persisted. */
  val history: List<MoneyEvent> = emptyList(),
) {
  /** Whether a farm was resolved at all — false for a spectator, and before the first good read. */
  val hasFarm: Boolean get() = balance != null

  /** Headroom left to borrow, floored at zero (the ceiling can drop below an existing loan). */
  val loanHeadroom: Long get() = ((loanMax ?: 0) - (loan ?: 0)).coerceAtLeast(0)
}

/** One column of the finances table: one in-game period, which is one month. */
@Serializable
data class FinancePeriod(
  /** 0 is the period being played, 1 is one period back, and so on. */
  val index: Int = 0,
  /** The game's period number, 1..12. */
  val period: Int = 0,
  /** Localized month name, as the game formats it. */
  val label: String = "",
  /** Calendar year this period belongs to — so a full year of columns has no ambiguous duplicates. */
  val year: Int = 0,
  /** True on [index] 0 only. */
  val current: Boolean = false,
  /** Sum of every stat row for this period — income minus expenses. */
  val total: Long = 0,
)

/** One row of the finances table: one `FinanceStats` bucket across every exported period. */
@Serializable
data class FinanceStatRow(
  /** The game's raw stat name (`harvestIncome`, `purchaseFuel`, …) — the stable key. */
  val name: String = "",
  /** Localized row label, as the game's own finances screen prints it. */
  val title: String = "",
  /** One signed amount per period, index-aligned with [FinanceData.periods]. */
  val values: List<Long> = emptyList(),
) {
  /** True when this bucket saw no movement in any exported period — the app may hide these. */
  val isEmpty: Boolean get() = values.all { it == 0L }
}

/**
 * One money notification, as the in-game HUD showed it — the mod hooks the single client-side funnel
 * (`HUD:showMoneyChange`) rather than reconstructing transactions, so this log holds exactly what the
 * player saw pop up, including the game's own rule that a change between -1 and 0 shows nothing.
 */
@Serializable
data class MoneyEvent(
  /** Monotonic within the mod session. The list is newest-first, so this is the sort key. */
  val seq: Int = 0,
  /** Signed: expenses negative. */
  val amount: Long = 0,
  /**
   * The money type's `statistic` — joins to [FinanceStatRow.name] where the game keeps a bucket for
   * it. Null for the few types with none (a loan movement is a balance-sheet item, not income).
   */
  val type: String? = null,
  /** The localized label the notification carried ("Harvest income"). */
  val title: String? = null,
  /** In-game date, `DD.MM.YYYY` — the same format as `environment.date`. */
  val date: String? = null,
  /** In-game time, `HH:MM`. */
  val time: String? = null,
)
