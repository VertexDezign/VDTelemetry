package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData

enum class AlertSeverity { Info, Warning, Critical }

/**
 * The latest value of every data channel alert rules may read; [AlertEngine.process] gets one per
 * tick of any channel. A `null` channel means "no data" (not connected yet, mod not installed) —
 * never "condition cleared" — and rules must freeze rather than fire or clear on it.
 */
data class AlertInputs(val telemetry: VdtData? = null, val taskList: TaskListData? = null)

/**
 * A declarative, edge-triggered alert an app contributes — the notification counterpart of
 * [net.vertexdezign.vdt.app.widgets.Widget]. Apps list their rules in
 * [net.vertexdezign.vdt.app.apps.VdtApp.alerts]; [AlertEngine] evaluates them on every tick and
 * fires on the *transition*, never per tick. Two kinds: [ThresholdAlertRule] watches one condition
 * with hysteresis, [KeyedAlertRule] watches a set of entities entering/leaving a condition.
 *
 * Absent data never fires or clears either kind (see [AlertInputs]) — which is also what keeps an
 * unavailable app's alerts from ever firing.
 */
sealed interface AlertRule {
  /** Stable identifier; unique across all registered rules (surfaces key off it). */
  val id: String
  val severity: AlertSeverity

  /** Short headline, e.g. "LOW FUEL". */
  val title: String
}

/**
 * A single condition with a hysteresis band: [enter] and [exit] are deliberately separate
 * predicates, so an alert raised by [enter] (fuel ≤ 10 %) stays active until [exit] holds (fuel >
 * 15 %) and a value sloshing around the enter threshold cannot flap. A pure edge alert uses
 * complementary predicates. Both must return `false` when the data they read is absent.
 */
class ThresholdAlertRule(
  override val id: String,
  override val severity: AlertSeverity,
  override val title: String,
  /** Detail line, built at raise time so it can quote the offending value. */
  val message: (AlertInputs) -> String,
  val enter: (AlertInputs) -> Boolean,
  val exit: (AlertInputs) -> Boolean,
) : AlertRule

/**
 * A condition over a *set* of entities (e.g. tasks becoming due): [activeEntities] snapshots the
 * entities currently in the condition as stable key → human label. Entities that appear since the
 * last tick raise **one** alert for the whole batch (message built by [message] from the new
 * labels); the alert stays active until the set empties. Return `null` — not an empty map — when
 * the backing channel is absent, so the rule freezes instead of clearing.
 */
class KeyedAlertRule(
  override val id: String,
  override val severity: AlertSeverity,
  override val title: String,
  val activeEntities: (AlertInputs) -> Map<String, String>?,
  val message: (List<String>) -> String,
) : AlertRule

/**
 * A raised [rule] with its [message] snapshotted at raise time. Identity (not value) equality is
 * intentional: the same rule re-raised later is a distinct alert to the banner surface.
 */
class ActiveAlert(val rule: AlertRule, val message: String)
