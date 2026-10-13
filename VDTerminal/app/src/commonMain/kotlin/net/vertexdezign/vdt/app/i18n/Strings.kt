package net.vertexdezign.vdt.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import net.vertexdezign.vdt.app.i18n.de.German
import net.vertexdezign.vdt.app.i18n.en.English

/**
 * Every word the terminal draws, in one language. One sub-catalogue per area of the app, each its own
 * interface, so a language is a package with one file per area (see [English], [German]) and a translation that
 * is missing is a compile error rather than an English word in a German screen.
 *
 * Text the game sends — fill types, vehicle and brand names, mission titles — is not in here: it arrives
 * already translated into the game's language, which is why the terminal follows that language by
 * default (see [net.vertexdezign.vdt.app.state.LanguageStore]).
 *
 * Plain values and functions, nothing composable, so code outside composition (alert rules, formatters,
 * registries) takes a [Strings] as a parameter; composables read [LocalStrings].
 */
interface Strings {
  val language: Language

  /** How this language writes numbers; every formatter takes it. */
  val numbers: Numbers
  val shell: ShellStrings
  val settings: SettingsStrings
  val launcher: LauncherStrings
  val display: DisplayStrings
  val apps: AppTitles
  val common: CommonStrings
  val fleet: FleetStrings
  val animals: AnimalsStrings
  val storage: StorageStrings
  val production: ProductionStrings
}

/** The languages the terminal has a catalogue for. */
enum class Language(val code: String) {
  En("en"),
  De("de"),
  ;

  val strings: Strings get() = when (this) {
    En -> English
    De -> German
  }

  companion object {
    /**
     * The catalogue for a language tag, or null when there is none: matched on the primary subtag, so a
     * browser's "de-CH" reads as German. The game's codes ("en", "de", "fr", "jp", …) are bare.
     */
    fun of(tag: String?): Language? {
      val primary = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
      return entries.firstOrNull { it.code == primary }
    }
  }
}

/**
 * The ambient [Strings]; provided at the app root. Static: a language switch is rare and rebuilds the
 * whole tree, which is what it should do. English outside a provider, so previews and tests render.
 */
val LocalStrings = staticCompositionLocalOf<Strings> { English }
