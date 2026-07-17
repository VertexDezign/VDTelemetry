package net.vertexdezign.vdt.app.alerts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vertexdezign.vdt.model.VdtData

/**
 * Runs the registered [AlertRule]s' hysteresis state machines over the telemetry stream. Plain
 * class, no Compose and no own coroutine: the caller feeds every telemetry tick into [process]
 * (wired up in `main()`), which keeps the edge-triggering logic directly unit-testable.
 *
 * Two outputs, for the two surfaces:
 * - [raised] — one event per enter-transition, feeding the transient banners.
 * - [active] — the sticky set of currently-active alerts, for anything that renders ongoing state
 *   (the footer's fuel icon, a future badge/history).
 */
class AlertEngine(private val rules: List<AlertRule>) {
  init {
    require(rules.distinctBy { it.id }.size == rules.size) { "duplicate alert rule ids" }
  }

  /** Raise-order map of active alerts; the rule's id is the state-machine key. */
  private val activeById = LinkedHashMap<String, ActiveAlert>()

  private val _active = MutableStateFlow<List<ActiveAlert>>(emptyList())
  val active: StateFlow<List<ActiveAlert>> = _active.asStateFlow()

  private val _raised = MutableSharedFlow<ActiveAlert>(extraBufferCapacity = 16)
  val raised: SharedFlow<ActiveAlert> = _raised.asSharedFlow()

  /**
   * Advances every rule against one telemetry tick and returns the alerts this tick raised (also
   * emitted on [raised]). `null` telemetry is a no-op: a connection drop must neither clear an
   * alert nor re-fire it on reconnect.
   */
  fun process(data: VdtData?): List<ActiveAlert> {
    if (data == null) return emptyList()
    val raisedNow = mutableListOf<ActiveAlert>()
    var changed = false
    for (rule in rules) {
      if (rule.id in activeById) {
        if (rule.exit(data)) {
          activeById.remove(rule.id)
          changed = true
        }
      } else if (rule.enter(data)) {
        val alert = ActiveAlert(rule, rule.message(data))
        activeById[rule.id] = alert
        raisedNow += alert
        changed = true
      }
    }
    if (changed) _active.value = activeById.values.toList()
    raisedNow.forEach(_raised::tryEmit)
    return raisedNow
  }
}
