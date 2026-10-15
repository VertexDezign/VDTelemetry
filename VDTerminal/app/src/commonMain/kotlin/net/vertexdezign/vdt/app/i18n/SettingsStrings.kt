package net.vertexdezign.vdt.app.i18n

/** The header's per-device settings menu: theme, size, language and number style. */
interface SettingsStrings {
  val description: String
  val theme: String
  val themeAuto: String
  val themeLight: String
  val themeDark: String
  val size: String
  val language: String

  /** The choice that follows the game's own language. */
  val languageGame: String
  val numbers: String

  /** The number style that follows the browser's region, else the language. */
  val numbersAuto: String
}
