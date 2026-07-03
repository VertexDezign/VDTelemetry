package net.vertexdezign.vdt.app

// Browser Screen Wake Lock interop (port of the old wakeLock.ts). The sentinel is kept on
// `window` and re-acquired on visibilitychange, since the browser drops it when the tab hides.

private fun jsRequest() {
    js(
        "if (navigator.wakeLock) { navigator.wakeLock.request('screen').then(function(s){ window.__vdtWakeLock = s; }).catch(function(){}); }"
    )
}

private fun jsRelease() {
    js("if (window.__vdtWakeLock) { window.__vdtWakeLock.release(); window.__vdtWakeLock = null; }")
}

private fun jsSetWant(want: Boolean) {
    js("window.__vdtWantWake = want;")
}

private fun jsInstallVisibilityHandler() {
    js(
        "document.addEventListener('visibilitychange', function(){ if (document.visibilityState === 'visible' && window.__vdtWantWake && navigator.wakeLock) { navigator.wakeLock.request('screen').then(function(s){ window.__vdtWakeLock = s; }).catch(function(){}); } });"
    )
}

/** Toggles the screen wake lock on/off. */
object WakeLock {
    private var enabled = false
    private var handlerInstalled = false

    fun toggle() {
        enabled = !enabled
        jsSetWant(enabled)
        if (!handlerInstalled) {
            jsInstallVisibilityHandler()
            handlerInstalled = true
        }
        if (enabled) jsRequest() else jsRelease()
    }
}
