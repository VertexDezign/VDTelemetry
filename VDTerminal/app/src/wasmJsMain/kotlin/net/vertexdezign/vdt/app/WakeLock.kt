@file:OptIn(ExperimentalWasmJsInterop::class)

package net.vertexdezign.vdt.app

import kotlin.js.ExperimentalWasmJsInterop

// Browser Screen Wake Lock interop (port of the old wakeLock.ts). The sentinel is kept on
// `window` and re-acquired on visibilitychange, since the browser drops it when the tab hides.
//
// Everything is wrapped in try/catch: some browsers (e.g. Firefox on iOS) expose
// `navigator.wakeLock` but throw synchronously from `request('screen')`, which a promise `.catch`
// can't handle — that surfaced as an uncaught runtime error. `window.__vdtWakeActive` tracks whether
// the lock is actually held so the UI can reflect real state, not just intent.

private fun jsRequest() {
  js(
    """
        try {
            if (navigator.wakeLock && navigator.wakeLock.request) {
                navigator.wakeLock.request('screen').then(function(s){
                    window.__vdtWakeLock = s;
                    window.__vdtWakeActive = true;
                    s.addEventListener('release', function(){ window.__vdtWakeActive = false; });
                }).catch(function(){ window.__vdtWakeActive = false; });
            } else {
                window.__vdtWakeActive = false;
            }
        } catch (e) {
            window.__vdtWakeActive = false;
        }
        """,
  )
}

private fun jsRelease() {
  js(
    """
        try {
            if (window.__vdtWakeLock) { window.__vdtWakeLock.release(); window.__vdtWakeLock = null; }
        } catch (e) {}
        window.__vdtWakeActive = false;
        """,
  )
}

private fun jsSetWant(want: Boolean) {
  js("window.__vdtWantWake = want;")
}

private fun jsInstallVisibilityHandler() {
  js(
    """
        document.addEventListener('visibilitychange', function(){
            if (document.visibilityState === 'visible' && window.__vdtWantWake && navigator.wakeLock && navigator.wakeLock.request) {
                try {
                    navigator.wakeLock.request('screen').then(function(s){
                        window.__vdtWakeLock = s;
                        window.__vdtWakeActive = true;
                        s.addEventListener('release', function(){ window.__vdtWakeActive = false; });
                    }).catch(function(){ window.__vdtWakeActive = false; });
                } catch (e) {
                    window.__vdtWakeActive = false;
                }
            }
        });
        """,
  )
}

private fun jsSupported(): Boolean = js("(typeof navigator !== 'undefined' && !!navigator.wakeLock)")

private fun jsActive(): Boolean = js("(!!window.__vdtWakeActive)")

/** Toggles the screen wake lock on/off. */
object WakeLock {
  private var enabled = false
  private var handlerInstalled = false

  /** Whether the browser exposes the Screen Wake Lock API at all. */
  val supported: Boolean get() = jsSupported()

  /** Whether the lock is currently held (reflects async success, not just the request intent). */
  val active: Boolean get() = jsActive()

  /** Flips the wake lock and returns the new desired state (true = keep screen awake). */
  fun toggle(): Boolean {
    enabled = !enabled
    jsSetWant(enabled)
    if (!handlerInstalled) {
      jsInstallVisibilityHandler()
      handlerInstalled = true
    }
    if (enabled) jsRequest() else jsRelease()
    return enabled
  }
}
