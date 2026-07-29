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

/**
 * Defines the one request routine every path shares (toggle, visibility re-acquire, gesture retry)
 * and installs the visibility handler. Idempotent, guarded on `window`.
 */
private fun jsInstall() {
  js(
    """
        try {
            if (!window.__vdtWakeInstalled) {
                window.__vdtWakeInstalled = true;
                window.__vdtWakeRequest = function() {
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
                };
                document.addEventListener('visibilitychange', function(){
                    if (document.visibilityState === 'visible' && window.__vdtWantWake) {
                        window.__vdtWakeRequest();
                    }
                });
            }
        } catch (e) {}
        """,
  )
}

private fun jsRequest() {
  js("try { if (window.__vdtWakeRequest) window.__vdtWakeRequest(); } catch (e) {}")
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

/**
 * Re-requests the lock on the first user gesture, if it is wanted and still not held. Covers browsers
 * that refuse the request on a page nobody has touched yet — a display asks at startup, so without
 * this the refusal would stand for the whole session.
 */
private fun jsRetryOnGesture() {
  js(
    """
        try {
            if (!window.__vdtWakeRetry) {
                window.__vdtWakeRetry = true;
                var retry = function() {
                    if (window.__vdtWantWake && !window.__vdtWakeActive && window.__vdtWakeRequest) {
                        window.__vdtWakeRequest();
                    }
                };
                var gestures = ['pointerdown', 'touchend', 'click', 'keydown'];
                for (var gi = 0; gi < gestures.length; gi++) {
                    window.addEventListener(gestures[gi], retry, { passive: true });
                }
            }
        } catch (e) {}
        """,
  )
}

private fun jsSupported(): Boolean = js("(typeof navigator !== 'undefined' && !!navigator.wakeLock)")

private fun jsActive(): Boolean = js("(!!window.__vdtWakeActive)")

/** Toggles the screen wake lock on/off. */
object WakeLock {
  private var enabled = false

  /** Whether the browser exposes the Screen Wake Lock API at all. */
  val supported: Boolean get() = jsSupported()

  /** Whether the lock is currently held (reflects async success, not just the request intent). */
  val active: Boolean get() = jsActive()

  /** Flips the wake lock and returns the new desired state (true = keep screen awake). */
  fun toggle(): Boolean {
    enabled = !enabled
    jsInstall()
    jsSetWant(enabled)
    if (enabled) jsRequest() else jsRelease()
    return enabled
  }

  /**
   * Turns the lock on without a user having asked — display mode wants the screen kept awake from
   * startup and has no header button to press. Idempotent, and arms the gesture retry in case the
   * browser declines the unprompted request (see [jsRetryOnGesture]); a later [toggle] still turns it
   * back off.
   */
  fun enable() {
    if (!enabled) {
      enabled = true
      jsInstall()
      jsSetWant(true)
      jsRequest()
    }
    jsRetryOnGesture()
  }
}
