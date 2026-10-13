package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.AppTitles
import net.vertexdezign.vdt.app.i18n.CommonStrings
import net.vertexdezign.vdt.app.i18n.DisplayStrings
import net.vertexdezign.vdt.app.i18n.FleetStrings
import net.vertexdezign.vdt.app.i18n.Language
import net.vertexdezign.vdt.app.i18n.LauncherStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.SettingsStrings
import net.vertexdezign.vdt.app.i18n.ShellStrings
import net.vertexdezign.vdt.app.i18n.Strings

/**
 * The German catalogue. Where the game has a word for a thing, it is the game's word ("Aufträge", not
 * "Verträge"): the data beside it is in the game's German.
 */
object German : Strings {
  override val numbers = Numbers.German
  override val language = Language.De
  override val shell: ShellStrings = DeShell
  override val settings: SettingsStrings = DeSettings
  override val launcher: LauncherStrings = DeLauncher
  override val display: DisplayStrings = DeDisplay
  override val apps: AppTitles = DeApps
  override val common: CommonStrings = DeCommon
  override val fleet: FleetStrings = DeFleet
}
