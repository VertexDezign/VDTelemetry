package net.vertexdezign.vdt.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A one-shot "put this on the map" request, handed from one app to another.
 *
 * The fleet list knows where a machine is (normalized map coordinates, the same frame the map
 * channels use) but has no map to put it on; the map has the pan and the zoom but no idea what you
 * were looking at. This carries the coordinate across while the shell switches screens.
 *
 * **Consume-once**, deliberately: [take] hands the pending target to the first reader and clears it,
 * so a map that is composed twice (a full page and a widget tile on some other page) does not both
 * jump, and coming back to the map later does not re-fire a jump from ten minutes ago. A request that
 * nobody reads simply expires when the next one replaces it.
 *
 * An object rather than a field on `VdtStore` because it is neither telemetry nor a setting: it is a
 * message with no sender and no receiver, alive for one recomposition. Nothing persists it.
 */
object MapFocus {
  private val _pending = MutableStateFlow<Pair<Float, Float>?>(null)

  /** The pending target, for a reader that wants to recompose when one arrives. */
  val pending: StateFlow<Pair<Float, Float>?> = _pending.asStateFlow()

  /** Ask the map to centre on a normalized `[0,1]` position. */
  fun request(x: Float, z: Float) {
    _pending.value = x to z
  }

  /** Take the pending target, if any, clearing it so it fires exactly once. */
  fun take(): Pair<Float, Float>? {
    val target = _pending.value
    if (target != null) _pending.value = null
    return target
  }
}
