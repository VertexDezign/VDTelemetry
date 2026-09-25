@file:OptIn(ExperimentalWasmJsInterop::class)

package net.vertexdezign.vdt.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.js.ExperimentalWasmJsInterop

// The browser's `prefers-color-scheme`, as a flow. `addListener` is the pre-2020 Safari spelling of
// `addEventListener('change')`, and older iPads are exactly the tablets that end up in a cab.

private fun jsPrefersDark(): Boolean = js(
  "(function(){ try { return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches); } catch (e) { return false; } })()",
)

private fun jsOnPrefersDarkChange(callback: (Boolean) -> Unit) {
  js(
    """
        try {
            var m = window.matchMedia('(prefers-color-scheme: dark)');
            var f = function(e) { callback(e.matches); };
            if (m.addEventListener) m.addEventListener('change', f); else if (m.addListener) m.addListener(f);
        } catch (e) {}
    """,
  )
}

/** Whether the browser currently prefers a dark scheme, updated live. */
fun systemPrefersDark(): StateFlow<Boolean> {
  val flow = MutableStateFlow(jsPrefersDark())
  jsOnPrefersDarkChange { flow.value = it }
  return flow.asStateFlow()
}
