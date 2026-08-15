@file:OptIn(ExperimentalWasmJsInterop::class)

package net.vertexdezign.vdt.app

import kotlin.js.ExperimentalWasmJsInterop

// Keeping the screen on, by whichever of two routes the page is allowed to take.
//
// The Screen Wake Lock API is secure-context only, and the device that needs it most cannot have
// one: the gaming PC reaches the dashboard at http://localhost:3001, which counts as trustworthy,
// but the tablet in the cab reaches it at http://<lan-ip>:3001, where `navigator.wakeLock` is not
// merely refused — it is undefined. A LAN address cannot be given a certificate anyone trusts
// without installing a CA on every device, so the tablet gets the older trick instead: a muted
// one-frame video, looping out of sight. Browsers keep the screen lit for playing video, and that
// rule has no secure-context clause.
//
// One switch over both: `request`/`release` are installed as `window.__vdtWake*` by whichever branch
// applies, so the toggle, the visibility re-acquire and the gesture retry below don't know which is
// running. `window.__vdtWakeActive` tracks whether the screen is actually being held awake — the
// real lock resolves asynchronously and the video's `play()` can be refused — so the UI reflects
// state rather than intent.
//
// Everything is wrapped in try/catch: some browsers (e.g. Firefox on iOS) expose `navigator.wakeLock`
// but throw synchronously from `request('screen')`, which a promise `.catch` can't handle — that
// surfaced as an uncaught runtime error.

/**
 * Defines the one request routine every path shares (toggle, visibility re-acquire, gesture retry)
 * and installs the visibility handler. Idempotent, guarded on `window`.
 *
 * [webm] and [mp4] are the two encodings of the same silent clip; a browser picks whichever it can
 * play. They are only touched on the fallback branch, so a secure context never fetches them.
 */
private fun jsInstall(webm: String, mp4: String) {
  js(
    """
        try {
            if (!window.__vdtWakeInstalled) {
                window.__vdtWakeInstalled = true;

                if (navigator.wakeLock && navigator.wakeLock.request) {
                    window.__vdtWakeRequest = function() {
                        try {
                            navigator.wakeLock.request('screen').then(function(s){
                                window.__vdtWakeLock = s;
                                window.__vdtWakeActive = true;
                                s.addEventListener('release', function(){ window.__vdtWakeActive = false; });
                            }).catch(function(){ window.__vdtWakeActive = false; });
                        } catch (e) {
                            window.__vdtWakeActive = false;
                        }
                    };
                    window.__vdtWakeRelease = function() {
                        try {
                            if (window.__vdtWakeLock) { window.__vdtWakeLock.release(); window.__vdtWakeLock = null; }
                        } catch (e) {}
                        window.__vdtWakeActive = false;
                    };
                } else {
                    var v = document.createElement('video');
                    // playsinline or iOS takes the video fullscreen over the dashboard; muted or the
                    // autoplay policy refuses a play() that nobody asked for, which is exactly how a
                    // display arms itself.
                    v.setAttribute('playsinline', '');
                    v.setAttribute('muted', '');
                    v.muted = true;
                    v.setAttribute('title', 'VDTerminal keep-awake');
                    v.style.cssText =
                        'position:fixed;right:0;bottom:0;width:1px;height:1px;opacity:0;pointer-events:none;';
                    var add = function(type, src) {
                        var s = document.createElement('source');
                        s.src = src;
                        s.type = 'video/' + type;
                        v.appendChild(s);
                    };
                    add('webm', webm);
                    add('mp4', mp4);
                    // The webm is under a second long and loops on its own; the mp4 is longer, and
                    // seeking it back before the end keeps playback continuous rather than looping
                    // through a pause. (Both from NoSleep.js, whose fallback this is.)
                    v.addEventListener('loadedmetadata', function() {
                        if (v.duration <= 1) {
                            v.loop = true;
                        } else {
                            v.addEventListener('timeupdate', function(){
                                if (v.currentTime > 0.5) { v.currentTime = Math.random(); }
                            });
                        }
                    });
                    v.addEventListener('pause', function(){ window.__vdtWakeActive = false; });
                    if (document.body) { document.body.appendChild(v); }
                    window.__vdtWakeVideo = v;

                    window.__vdtWakeRequest = function() {
                        try {
                            var p = v.play();
                            if (p && p.then) {
                                p.then(function(){ window.__vdtWakeActive = true; })
                                 .catch(function(){ window.__vdtWakeActive = false; });
                            } else {
                                window.__vdtWakeActive = true;
                            }
                        } catch (e) {
                            window.__vdtWakeActive = false;
                        }
                    };
                    window.__vdtWakeRelease = function() {
                        try { v.pause(); } catch (e) {}
                        window.__vdtWakeActive = false;
                    };
                }

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
        try { if (window.__vdtWakeRelease) window.__vdtWakeRelease(); } catch (e) {}
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
 * this the refusal would stand for the whole session. It carries the fallback too: a video that the
 * autoplay policy declined plays from the first touch onwards.
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

private fun jsSupported(): Boolean =
  js("(typeof document !== 'undefined' && typeof document.createElement === 'function')")

private fun jsActive(): Boolean = js("(!!window.__vdtWakeActive)")

/** Toggles the screen wake lock on/off. */
object WakeLock {
  /**
   * The silent clip the fallback loops, served from the app's own resources rather than inlined as a
   * data URI: 9 KB of base64 in a source file would be 9 KB the secure-context path also pays for.
   */
  private const val WEBM = "media/keep-awake.webm"
  private const val MP4 = "media/keep-awake.mp4"

  private var enabled = false
  private var installed = false

  /**
   * Whether *either* route is available, which in a browser means yes. It stays a question because
   * the answer gates the UI: a page with no document at all would show NO WAKE LOCK rather than a
   * button that quietly does nothing.
   */
  val supported: Boolean get() = jsSupported()

  /** Whether the screen is actually being held awake (reflects async success, not just intent). */
  val active: Boolean get() = jsActive()

  private fun install() {
    if (!installed) {
      installed = true
      jsInstall(WEBM, MP4)
    }
  }

  /** Flips the wake lock and returns the new desired state (true = keep screen awake). */
  fun toggle(): Boolean {
    enabled = !enabled
    install()
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
      install()
      jsSetWant(true)
      jsRequest()
    }
    jsRetryOnGesture()
  }
}
