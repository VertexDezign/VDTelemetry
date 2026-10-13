package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.AnimalsStrings
import net.vertexdezign.vdt.app.i18n.AppTitles
import net.vertexdezign.vdt.app.i18n.CommonStrings
import net.vertexdezign.vdt.app.i18n.DisplayStrings
import net.vertexdezign.vdt.app.i18n.FleetStrings
import net.vertexdezign.vdt.app.i18n.Language
import net.vertexdezign.vdt.app.i18n.LauncherStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.ProductionStrings
import net.vertexdezign.vdt.app.i18n.SettingsStrings
import net.vertexdezign.vdt.app.i18n.ShellStrings
import net.vertexdezign.vdt.app.i18n.StorageStrings
import net.vertexdezign.vdt.app.i18n.Strings

/** The English catalogue, and the reference the others are translated from. */
object English : Strings {
  override val numbers = Numbers.English
  override val language = Language.En
  override val shell: ShellStrings = EnShell
  override val settings: SettingsStrings = EnSettings
  override val launcher: LauncherStrings = EnLauncher
  override val display: DisplayStrings = EnDisplay
  override val apps: AppTitles = EnApps
  override val common: CommonStrings = EnCommon
  override val fleet: FleetStrings = EnFleet
  override val animals: AnimalsStrings = EnAnimals
  override val storage: StorageStrings = EnStorage
  override val production: ProductionStrings = EnProduction
}
