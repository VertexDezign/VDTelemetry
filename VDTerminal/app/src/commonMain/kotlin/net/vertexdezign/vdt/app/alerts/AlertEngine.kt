package net.vertexdezign.vdt.app.alerts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs the registered [AlertRule]s' edge-detection state machines over the data streams. Plain
 * class, no Compose and no own coroutine: the caller feeds every tick into [process] (wired up in
 * `main()`), which keeps the edge-triggering logic directly unit-testable.
 *
 * Two outputs, for the two surfaces:
 * - [raised] — one event per transition, feeding the transient banners.
 * - [active] — the sticky set of currently-active alerts, for anything that renders ongoing state
 *   (the footer's fuel icon, a future badge/history).
 */
class AlertEngine(private val rules: List<AlertRule>) {
  init {
    require(rules.distinctBy { it.id }.size == rules.size) { "duplicate alert rule ids" }
  }

  /** Raise-order map of active alerts; the rule's id is the state-machine key. */
  private val activeById = LinkedHashMap<String, ActiveAlert>()

  /** Per keyed rule: the entity keys seen in the condition last tick. */
  private val entityKeysByRule = HashMap<String, Set<String>>()

  private val _active = MutableStateFlow<List<ActiveAlert>>(emptyList())
  val active: StateFlow<List<ActiveAlert>> = _active.asStateFlow()

  private val _raised = MutableSharedFlow<ActiveAlert>(extraBufferCapacity = 16)
  val raised: SharedFlow<ActiveAlert> = _raised.asSharedFlow()

  /**
   * Advances every rule against one tick of [inputs] and returns the alerts this tick raised (also
   * emitted on [raised]). Rules whose data is absent hold their state (see [AlertInputs]) — a
   * connection drop must neither clear an alert nor re-fire it on reconnect.
   */
  fun process(inputs: AlertInputs): List<ActiveAlert> {
    val raisedNow = mutableListOf<ActiveAlert>()
    var changed = false
    for (rule in rules) {
      changed = when (rule) {
        is ThresholdAlertRule -> processThreshold(rule, inputs, raisedNow)
        is KeyedAlertRule -> processKeyed(rule, inputs, raisedNow)
      } || changed
    }
    if (changed) _active.value = activeById.values.toList()
    raisedNow.forEach(_raised::tryEmit)
    return raisedNow
  }

  private fun processThreshold(
    rule: ThresholdAlertRule,
    inputs: AlertInputs,
    raisedNow: MutableList<ActiveAlert>,
  ): Boolean {
    if (rule.id in activeById) {
      if (!rule.exit(inputs)) return false
      activeById.remove(rule.id)
    } else {
      if (!rule.enter(inputs)) return false
      raise(rule, rule.message(inputs), raisedNow)
    }
    return true
  }

  private fun processKeyed(rule: KeyedAlertRule, inputs: AlertInputs, raisedNow: MutableList<ActiveAlert>): Boolean {
    val entities = rule.activeEntities(inputs) ?: return false
    val previous = entityKeysByRule[rule.id] ?: emptySet()
    entityKeysByRule[rule.id] = entities.keys
    val newLabels = entities.filterKeys { it !in previous }.values.toList()
    return when {
      // One alert for the whole batch — ten tasks turning due on a month change is one banner.
      newLabels.isNotEmpty() -> {
        raise(rule, rule.message(newLabels), raisedNow)
        true
      }

      entities.isEmpty() && rule.id in activeById -> {
        activeById.remove(rule.id)
        true
      }

      else -> false
    }
  }

  private fun raise(rule: AlertRule, message: String, raisedNow: MutableList<ActiveAlert>) {
    val alert = ActiveAlert(rule, message)
    activeById[rule.id] = alert
    raisedNow += alert
  }
}
