@file:OptIn(ExperimentalWasmJsInterop::class)

package net.vertexdezign.vdt.app

import kotlin.js.ExperimentalWasmJsInterop

// Web Audio chime for alert raises. Browsers gate audio behind user activation: an AudioContext
// created before any gesture starts 'suspended' and produces nothing. So [install] arms the
// context from the first user gesture instead, and [play] is a no-op until the context actually
// runs — alerts raised before the user ever touched the page are silently visual-only (the banner
// still shows). The chime is synthesized (two-tone oscillator) so no audio asset has to ship in
// the bundle.
//
// WebKit (iPad Safari) needs three accommodations beyond that:
// - Only touchend/click/keydown count as activation gestures there — touchstart, and thus
//   pointerdown, do NOT — so the unlock listeners cover both event families.
// - After a screen lock or interruption the context sits in a nonstandard 'interrupted' state, so
//   resume on anything that isn't 'running', not just 'suspended'.
// - Silent Mode mutes Web Audio routed as 'ambient' (the default); opting the audio session into
//   'playback' (AudioSession API, WebKit-only, harmless elsewhere) lets the chime through.

private fun jsInstall() {
  js(
    """
        try {
            if (!window.__vdtAlertChime) {
                var state = { ctx: null };
                var ensure = function() {
                    try {
                        var AC = window.AudioContext || window.webkitAudioContext;
                        if (!state.ctx && AC) {
                            state.ctx = new AC();
                            if (navigator.audioSession) {
                                try { navigator.audioSession.type = 'playback'; } catch (e) {}
                            }
                        }
                        if (state.ctx && state.ctx.state !== 'running') { state.ctx.resume().catch(function(){}); }
                    } catch (e) {}
                    return state.ctx;
                };
                var gestures = ['pointerdown', 'touchend', 'click', 'keydown'];
                for (var gi = 0; gi < gestures.length; gi++) {
                    window.addEventListener(gestures[gi], ensure, { passive: true });
                }
                window.__vdtAlertChime = function() {
                    var c = ensure();
                    if (!c || c.state !== 'running') { return; }
                    var tones = [880, 1174.66];
                    for (var i = 0; i < tones.length; i++) {
                        var t = c.currentTime + i * 0.18;
                        var o = c.createOscillator();
                        var g = c.createGain();
                        o.type = 'sine';
                        o.frequency.value = tones[i];
                        g.gain.setValueAtTime(0.0001, t);
                        g.gain.exponentialRampToValueAtTime(0.5, t + 0.02);
                        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
                        o.connect(g);
                        g.connect(c.destination);
                        o.start(t);
                        o.stop(t + 0.18);
                    }
                };
            }
        } catch (e) {}
        """,
  )
}

private fun jsPlay() {
  js("try { if (window.__vdtAlertChime) window.__vdtAlertChime(); } catch (e) {}")
}

/** The shell's audible alert cue; best-effort under the browser's autoplay policy (see above). */
object AlertSound {
  /** Installs the gesture hooks that arm the audio context. Idempotent; call once at startup. */
  fun install() {
    jsInstall()
  }

  /** Plays the chime if audio is armed; silently does nothing otherwise. */
  fun play() {
    jsPlay()
  }
}
