package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.MapSettings
import net.vertexdezign.vdt.app.i18n.Language
import net.vertexdezign.vdt.app.i18n.NumberStyle
import net.vertexdezign.vdt.app.i18n.Numbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LanguageStoreTest {
  @Test
  fun followsTheGameThenTheBrowserThenEnglish() {
    assertEquals(Language.De, LanguageStore.resolve(null, "de", "en-US"))
    // A game language without a catalogue falls through to the browser's.
    assertEquals(Language.De, LanguageStore.resolve(null, "fr", "de-CH"))
    assertEquals(Language.En, LanguageStore.resolve(null, "fr", "it-IT"))
    // Before the game has ever been seen.
    assertEquals(Language.De, LanguageStore.resolve(null, null, "de"))
    assertEquals(Language.En, LanguageStore.resolve(null, null, null))
  }

  @Test
  fun aChoiceWinsOverTheGame() {
    assertEquals(Language.En, LanguageStore.resolve(Language.En, "de", "de-DE"))
  }

  @Test
  fun persistsAChoiceAndForgetsItOnGame() {
    val settings = MapSettings()
    LanguageStore(settings, null).set(Language.De)
    assertEquals(Language.De, LanguageStore(settings, null).choice.value)

    LanguageStore(settings, null).set(null)
    assertNull(settings.getStringOrNull(LanguageStore.CHOICE_KEY))
  }

  @Test
  fun remembersTheGameLanguageAcrossALoad() {
    val settings = MapSettings()
    LanguageStore(settings, "en-US").observeGame("de")
    val reloaded = LanguageStore(settings, "en-US")
    assertEquals(Language.De, reloaded.resolve(reloaded.choice.value, reloaded.gameLanguage.value))

    // An older mod, or no data yet, says nothing — that is not the game switching to English.
    reloaded.observeGame(null)
    assertEquals("de", reloaded.gameLanguage.value)
  }

  @Test
  fun readsTheLanguageOffItsPrimarySubtag() {
    assertEquals(Language.De, Language.of("de-AT"))
    assertEquals(Language.De, Language.of("DE"))
    assertEquals(Language.En, Language.of("en_GB"))
    assertNull(Language.of("jp"))
    assertNull(Language.of(""))
    assertNull(Language.of(null))
  }

  @Test
  fun numbersFollowTheChoiceThenTheRegionThenTheLanguage() {
    assertSame(Numbers.English, LanguageStore.numbers(NumberStyle.English, Language.De, "de-CH"))
    // A Swiss browser writes Swiss numbers whatever the language.
    assertSame(Numbers.Swiss, LanguageStore.numbers(null, Language.De, "de-CH"))
    assertSame(Numbers.Swiss, LanguageStore.numbers(null, Language.En, "fr_CH"))
    assertSame(Numbers.German, LanguageStore.numbers(null, Language.De, "de-DE"))
    assertSame(Numbers.English, LanguageStore.numbers(null, Language.En, null))
  }

  @Test
  fun aNumberStyleKeepsTheWordsAndIsPersisted() {
    val settings = MapSettings()
    LanguageStore(settings, null).setNumbers(NumberStyle.Swiss)
    val reloaded = LanguageStore(settings, "de-DE")
    val strings = reloaded.strings(Language.De, null, reloaded.numberChoice.value)
    assertSame(Numbers.Swiss, strings.numbers)
    assertEquals(Language.De.strings.settings.language, strings.settings.language)
    // The same instance each time: LocalStrings is static, a new one would rebuild the tree.
    assertSame(strings, reloaded.strings(Language.De, null, NumberStyle.Swiss))

    reloaded.setNumbers(null)
    assertNull(settings.getStringOrNull(LanguageStore.NUMBERS_KEY))
  }
}
