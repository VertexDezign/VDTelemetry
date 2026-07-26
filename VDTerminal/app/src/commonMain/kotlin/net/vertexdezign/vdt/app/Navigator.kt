package net.vertexdezign.vdt.app

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Opens a [Screen] from anywhere in the composition. Provided at the app root; the shell keeps owning
 * the actual screen state, this is only the way to ask it to change.
 *
 * A CompositionLocal because widgets are placed by the *user*, on any page, in any position: there is
 * no fixed call chain from the shell down to a given tile that a callback could be threaded through,
 * and every widget would have to carry a navigation parameter it mostly doesn't use. This is the same
 * reason navigation is ambient in Compose generally.
 *
 * Allow-listed for the compose ktlint rule via `compose_allowed_composition_locals` in the build
 * config, alongside [net.vertexdezign.vdt.app.state.LocalVdtStore].
 */
val LocalNavigator = staticCompositionLocalOf<(Screen) -> Unit> { error("Navigator not provided") }
