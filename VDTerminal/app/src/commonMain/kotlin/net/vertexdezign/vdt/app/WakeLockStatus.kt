package net.vertexdezign.vdt.app

/** Screen wake-lock state, surfaced in the header so the user can see whether the screen is kept on. */
enum class WakeLockStatus {
    /** Wake lock is requested/held — the screen stays awake. */
    On,

    /** Supported but not currently held. */
    Off,

    /** The browser has no Screen Wake Lock API (e.g. Firefox on iOS). */
    Unsupported,
}
