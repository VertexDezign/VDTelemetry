package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vertexdezign.vdt.app.i18n.Language
import net.vertexdezign.vdt.app.i18n.NumberStyle
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.Strings

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
 * How numbers are written is a choice of its own ([numberChoice]): the game says nothing about the
 * player's region, and German words over German numbers is wrong for a Swiss one.
 *
 * [browserLanguage] is the browser's own tag ("de-CH"), the guess before the game has ever been seen,
 * and where the number style's region comes from.
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

  private val _numberChoice =
    MutableStateFlow(
      settings.getStringOrNull(NUMBERS_KEY)?.let { stored ->
        NumberStyle.entries.firstOrNull {
          it.code ==
            stored
        }
      },
    )

  /** The device's own number style; null follows the browser's region, else the language. */
  val numberChoice: StateFlow<NumberStyle?> = _numberChoice.asStateFlow()

  fun setNumbers(choice: NumberStyle?) {
    _numberChoice.value = choice
    if (choice == null) settings.remove(NUMBERS_KEY) else settings.putString(NUMBERS_KEY, choice.code)
  }

  /** Records what the game says it speaks; a null (an older mod, no data yet) keeps the last one. */
  fun observeGame(code: String?) {
    if (code.isNullOrBlank() || code == _gameLanguage.value) return
    _gameLanguage.value = code
    settings.putString(GAME_KEY, code)
  }

  /** The language to draw in, given the current [choice] and [gameLanguage]. */
  fun resolve(choice: Language?, gameLanguage: String?): Language = resolve(choice, gameLanguage, browserLanguage)

  /** The catalogue to draw with: [resolve]'s language, writing numbers the way [numberChoice] resolves. */
  fun strings(choice: Language?, gameLanguage: String?, numberChoice: NumberStyle?): Strings {
    val language = resolve(choice, gameLanguage)
    return language.strings(numbers(numberChoice, language, browserLanguage))
  }

  /** [strings] for the current choices, for code outside composition. */
  fun currentStrings(): Strings = strings(choice.value, gameLanguage.value, numberChoice.value)

  companion object {
    const val CHOICE_KEY = "vdt.language"
    const val GAME_KEY = "vdt.gameLanguage"
    const val NUMBERS_KEY = "vdt.numbers"

    /** The device's pick, else the browser region's style, else [language]'s own. */
    fun numbers(choice: NumberStyle?, language: Language, browserLanguage: String?): Numbers =
      (choice ?: NumberStyle.ofRegion(browserLanguage))?.numbers ?: language.strings.numbers

    /**
     * The device's pick, else the game's language, else the browser's, else English. A game language
     * the terminal has no catalogue for (French, say) falls through to the browser's, which may be one
     * it has.
     */
    fun resolve(choice: Language?, gameLanguage: String?, browserLanguage: String?): Language =
      choice ?: Language.of(gameLanguage) ?: Language.of(browserLanguage) ?: Language.En
  }
}
