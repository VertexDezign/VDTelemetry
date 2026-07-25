package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.app.Screen

/**
 * A screen pinned to the bottom bar beside the launcher.
 *
 * Deliberately its own type rather than a serializable [Screen]: `Screen` is transient shell state
 * with no persistence contract, and pinning one shouldn't freeze its shape into stored user data.
 */
@Serializable
data class Favourite(val kind: Kind, val id: String) {
  @Serializable
  enum class Kind { App, Page }

  fun toScreen(): Screen = when (kind) {
    Kind.App -> Screen.OpenApp(id)
    Kind.Page -> Screen.OpenPage(id)
  }

  companion object {
    fun of(screen: Screen): Favourite = when (screen) {
      is Screen.OpenApp -> Favourite(Kind.App, screen.appId)
      is Screen.OpenPage -> Favourite(Kind.Page, screen.pageId)
    }
  }
}

/**
 * The screens pinned to the bottom bar, persisted to [Settings] as JSON.
 *
 * **Order is the user's and never changes on its own.** No most-recent, no most-used: the whole value
 * of a pinned row is that a target sits where you last left it, so muscle memory works without
 * looking — which an auto-sorting dock destroys.
 *
 * Capped at [MAX]: past four the row starts competing with the page title for width, and a dock you
 * have to read is no faster than the launcher.
 *
 * Entries are *not* validated here. A favourite can outlive its page (deleted) or its app (mod
 * uninstalled), and the bar resolves against what currently exists — so a page deleted by accident
 * and restored keeps its slot instead of being silently dropped from the pins.
 */
class FavouritesStore(private val settings: Settings) {
  private val json = Json { ignoreUnknownKeys = true }

  private val _favourites = MutableStateFlow(load())
  val favourites: StateFlow<List<Favourite>> = _favourites.asStateFlow()

  fun isPinned(favourite: Favourite): Boolean = favourite in _favourites.value

  /** True when another pin would exceed [MAX] — the picker uses this to disable its unpinned rows. */
  fun isFull(): Boolean = _favourites.value.size >= MAX

  /** Pins [favourite] at the end, or unpins it if already pinned. Pinning past [MAX] is a no-op. */
  fun toggle(favourite: Favourite) {
    val current = _favourites.value
    persist(
      when {
        favourite in current -> current - favourite
        current.size >= MAX -> return
        else -> current + favourite
      },
    )
  }

  private fun persist(list: List<Favourite>) {
    _favourites.value = list
    settings.putString(KEY, json.encodeToString(Serializer, list))
  }

  private fun load(): List<Favourite> {
    val raw = settings.getStringOrNull(KEY) ?: return DEFAULTS
    return runCatching { json.decodeFromString(Serializer, raw) }.getOrNull()?.take(MAX) ?: DEFAULTS
  }

  companion object {
    const val MAX = 4

    /**
     * Seeded with core apps rather than pages: the starter pages are already one swipe away and have
     * dots pointing at them, so pinning them would spend the row on the one thing already reachable.
     * Apps have no such affordance — without a pin they're two taps deep behind the launcher.
     */
    private val DEFAULTS = listOf(
      Favourite(Favourite.Kind.App, "map"),
      Favourite(Favourite.Kind.App, "production"),
      Favourite(Favourite.Kind.App, "storage"),
    )

    private const val KEY = "vdt.favourites"
    private val Serializer = ListSerializer(Favourite.serializer())
  }
}
