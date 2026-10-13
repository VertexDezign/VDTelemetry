package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vertexdezign.vdt.app.i18n.Language

/**
 * Which language this device draws the terminal in. By default the game's: fill types, vehicle names
 * and mission texts reach the terminal already translated into it, and chrome in another language
 * beside them reads as two. [choice] overrides that per device, persisted in this browser like the
 * theme and the UI size — one player's tablet in English beside a German game is a fair thing to want.
 *
 * The game's language is remembered once seen ([gameLanguage]), so a cold load draws its loading
 * screen and a dropped connection keeps its words in the language the game last spoke, rather than
 * flashing the browser's in between.
 *
 * [browserLanguage] is the browser's own tag ("de-CH"), the guess before the game has ever been seen.
 */
class LanguageStore(private val settings: Settings, private val browserLanguage: String?) {
  private val _choice =
    MutableStateFlow(
      settings.getStringOrNull(CHOICE_KEY)?.let { stored ->
        Language.entries.firstOrNull {
          it.code ==
            stored
        }
      },
    )

  /** The device's own pick; null follows the game. */
  val choice: StateFlow<Language?> = _choice.asStateFlow()

  private val _gameLanguage = MutableStateFlow(settings.getStringOrNull(GAME_KEY))

  /** The last language code the game reported, as it reported it ("fr" included). */
  val gameLanguage: StateFlow<String?> = _gameLanguage.asStateFlow()

  fun set(choice: Language?) {
    _choice.value = choice
    if (choice == null) settings.remove(CHOICE_KEY) else settings.putString(CHOICE_KEY, choice.code)
  }

  /** Records what the game says it speaks; a null (an older mod, no data yet) keeps the last one. */
  fun observeGame(code: String?) {
    if (code.isNullOrBlank() || code == _gameLanguage.value) return
    _gameLanguage.value = code
    settings.putString(GAME_KEY, code)
  }

  /** The language to draw in, given the current [choice] and [gameLanguage]. */
  fun resolve(choice: Language?, gameLanguage: String?): Language = resolve(choice, gameLanguage, browserLanguage)

  companion object {
    const val CHOICE_KEY = "vdt.language"
    const val GAME_KEY = "vdt.gameLanguage"

    /**
     * The device's pick, else the game's language, else the browser's, else English. A game language
     * the terminal has no catalogue for (French, say) falls through to the browser's, which may be one
     * it has.
     */
    fun resolve(choice: Language?, gameLanguage: String?, browserLanguage: String?): Language =
      choice ?: Language.of(gameLanguage) ?: Language.of(browserLanguage) ?: Language.En
  }
}
