@file:OptIn(ExperimentalWasmJsInterop::class)

package net.vertexdezign.vdt.app

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fallback is for the one place it cannot be tried by hand here: a tablet on `http://<lan-ip>`,
 * where the Screen Wake Lock API is undefined. Karma serves these tests from localhost, which *is* a
 * secure context, so the API is present and the native branch would be the one taken — the test
 * shadows `navigator.wakeLock` to get at the other half.
 *
 * What it can assert is the wiring, not the effect: whether a playing video really keeps a screen lit
 * is the device's business, and headless Chrome has no screen to dim.
 */
class WakeLockFallbackTest {
  @Test
  fun installsAMutedInlineVideoWhenTheWakeLockApiIsMissing() {
    forceNoNativeWakeLock()
    assertFalse(hasNativeWakeLock(), "the shadow didn't take, so this would test the native branch")

    // True either way: with the fallback there is always something to try, and the UI shows a live
    // button rather than NO WAKE LOCK.
    assertTrue(WakeLock.supported, "a browser with a document can always keep itself awake somehow")

    WakeLock.enable()

    assertEquals(2, videoSourceCount(), "both encodings are offered, and the browser picks one")
    assertEquals(
      "media/keep-awake.webm,media/keep-awake.mp4",
      videoSourceSrcs(),
      "the clips are served from the app's own resources",
    )
    assertTrue(videoIsMutedAndInline(), "unmuted or fullscreen, and the browser refuses to autoplay it")
    assertTrue(videoIsOutOfTheWay(), "the video must not take pointer events from the dashboard")
  }
}

/** Shadows the API the way an insecure origin does: present on the prototype, undefined in fact. */
private fun forceNoNativeWakeLock() {
  js("try { Object.defineProperty(navigator, 'wakeLock', { value: undefined, configurable: true }); } catch (e) {}")
}

private fun hasNativeWakeLock(): Boolean = js("(!!(navigator.wakeLock && navigator.wakeLock.request))")

private fun videoSourceCount(): Int =
  js("(window.__vdtWakeVideo ? window.__vdtWakeVideo.querySelectorAll('source').length : -1)")

private fun videoSourceSrcs(): String = js(
  """
    (window.__vdtWakeVideo
        ? Array.prototype.map.call(
            window.__vdtWakeVideo.querySelectorAll('source'),
            function(s) { return s.getAttribute('src'); }
          ).join(',')
        : '')
    """,
)

private fun videoIsMutedAndInline(): Boolean =
  js("(!!window.__vdtWakeVideo && window.__vdtWakeVideo.muted && window.__vdtWakeVideo.hasAttribute('playsinline'))")

private fun videoIsOutOfTheWay(): Boolean =
  js("(!!window.__vdtWakeVideo && window.__vdtWakeVideo.style.pointerEvents === 'none')")
