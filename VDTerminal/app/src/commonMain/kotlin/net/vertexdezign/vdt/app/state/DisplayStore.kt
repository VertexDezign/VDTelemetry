package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.pages.Page

/**
 * What a URL asked this browser to do about display mode — the result of reading `display` out of a
 * location search string.
 *
 * A parameter is the whole addressing mechanism on purpose: you bookmark a different URL on the second
 * device, so the two devices need no pairing and no shared state.
 */
sealed interface DisplayRequest {
  /** No `display` parameter at all: leave this device however it already was. */
  data object Absent : DisplayRequest

  /** `?display=off` (or a valueless `?display`): back to the full shell. */
  data object Clear : DisplayRequest

  /** `?display=<id>`: pin this device to the page or app with that id. */
  data class Pin(val id: String) : DisplayRequest

  companion object {
    private const val PARAM = "display"
    private const val OFF = "off"

    /**
     * Reads [query], a `?a=b&c=d` location search string (the leading `?` is optional). The last
     * `display` wins, matching what `URLSearchParams.get` would *not* do but what a hand-edited URL
     * that ended up with two of them almost certainly means — the one you typed at the end.
     *
     * Values are taken verbatim: page and app ids are our own slugs (`vehicle`, `page-1f3k`), so there
     * is nothing to percent-decode, and anything that isn't an id simply fails to resolve later — see
     * [resolveDisplay], and the way [net.vertexdezign.vdt.app.DisplayShell] handles that.
     */
    fun parse(query: String): DisplayRequest {
      val value =
        query
          .removePrefix("?")
          .split('&')
          .mapNotNull { part -> if (part.substringBefore('=') == PARAM) part.substringAfter('=', "").trim() else null }
          .lastOrNull()
          ?: return Absent
      return if (value.isEmpty() || value.equals(OFF, ignoreCase = true)) Clear else Pin(value)
    }
  }
}

/**
 * Whether this browser is a **display**: a device pinned to one screen with no shell around it, for a
 * phone clamped in the cab beside the tablet. See [net.vertexdezign.vdt.app.DisplayShell] for what
 * that actually renders.
 *
 * The URL is how you set it and storage is how it sticks: the parameter is applied once at startup and
 * then persisted, so a home-screen shortcut, a reload after a crash, or anyone opening the bare address
 * on that device all come back to the same pinned screen. `?display=off` is the way out that needs
 * nothing on the device itself.
 *
 * Only the raw id is stored, and it is resolved against what exists *now* (see [resolveDisplay]) —
 * the same choice [FavouritesStore] makes, so a page deleted by accident and restored keeps working.
 */
class DisplayStore(private val settings: Settings) {
  private val _target = MutableStateFlow(settings.getStringOrNull(KEY))

  /** The pinned page/app id, or null when this device is an ordinary shell. */
  val target: StateFlow<String?> = _target.asStateFlow()

  /** Applies a URL's request. [DisplayRequest.Absent] deliberately leaves the stored value alone. */
  fun apply(request: DisplayRequest) {
    when (request) {
      DisplayRequest.Absent -> Unit
      DisplayRequest.Clear -> clear()
      is DisplayRequest.Pin -> pin(request.id)
    }
  }

  fun pin(id: String) {
    _target.value = id
    settings.putString(KEY, id)
  }

  /** Leaves display mode on this device — the reveal overlay's EXIT, and `?display=off`. */
  fun clear() {
    _target.value = null
    settings.remove(KEY)
  }

  companion object {
    const val KEY = "vdt.display"
  }
}

/**
 * The [Screen] a display is pinned to, or null when [target] names nothing that exists.
 *
 * Pages win over apps on an id clash: `?display=` is aimed at a laid-out page first and foremost, and
 * a page id is the one of the two the user chose themselves. Apps resolve from the whole
 * [AppRegistry] rather than the available subset — an app whose optional mod is missing renders its
 * own empty state, which says more than a display that has become "target missing".
 */
fun resolveDisplay(target: String, pages: List<Page>): Screen? =
  pages.firstOrNull { it.id == target }?.let { Screen.OpenPage(it.id) }
    ?: AppRegistry.byId(target)?.let { Screen.OpenApp(it.id) }
