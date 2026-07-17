package net.vertexdezign.vdt.app.alerts

import net.vertexdezign.vdt.model.VdtData

enum class AlertSeverity { Info, Warning, Critical }

/**
 * A declarative, edge-triggered alert an app contributes — the notification counterpart of
 * [net.vertexdezign.vdt.app.widgets.Widget]. Apps list their rules in
 * [net.vertexdezign.vdt.app.apps.VdtApp.alerts]; [AlertEngine] evaluates them on every telemetry
 * tick and fires on the *transition*, never per tick.
 *
 * [enter] and [exit] form the hysteresis band and are deliberately separate predicates: an alert
 * raised by [enter] (fuel ≤ 10 %) stays active until [exit] holds (fuel > 15 %), so a value
 * sloshing around the enter threshold cannot flap. A pure edge alert uses complementary predicates.
 *
 * Both predicates must return `false` when the data they read is absent (on foot, mod not
 * installed, telemetry subtree missing): the rule then freezes in its current state instead of
 * firing or clearing on missing data — which is also what keeps an unavailable app's alerts from
 * ever firing.
 */
class AlertRule(
  /** Stable identifier; unique across all registered rules (surfaces key off it). */
  val id: String,
  val severity: AlertSeverity,
  /** Short headline, e.g. "LOW FUEL". */
  val title: String,
  /** Detail line, built at raise time so it can quote the offending value. */
  val message: (VdtData) -> String,
  val enter: (VdtData) -> Boolean,
  val exit: (VdtData) -> Boolean,
)

/**
 * A raised [rule] with its [message] snapshotted at raise time. Identity (not value) equality is
 * intentional: the same rule re-raised later is a distinct alert to the banner surface.
 */
class ActiveAlert(val rule: AlertRule, val message: String)
