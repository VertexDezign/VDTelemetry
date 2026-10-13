package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.AnimalsStrings
import net.vertexdezign.vdt.app.i18n.AppTitles
import net.vertexdezign.vdt.app.i18n.AutoDriveStrings
import net.vertexdezign.vdt.app.i18n.BalerStrings
import net.vertexdezign.vdt.app.i18n.CalendarStrings
import net.vertexdezign.vdt.app.i18n.ClusterStrings
import net.vertexdezign.vdt.app.i18n.CombineStrings
import net.vertexdezign.vdt.app.i18n.CommonStrings
import net.vertexdezign.vdt.app.i18n.DiagnosticsStrings
import net.vertexdezign.vdt.app.i18n.DisplayStrings
import net.vertexdezign.vdt.app.i18n.FieldsStrings
import net.vertexdezign.vdt.app.i18n.FinanceStrings
import net.vertexdezign.vdt.app.i18n.FleetStrings
import net.vertexdezign.vdt.app.i18n.InvoicesStrings
import net.vertexdezign.vdt.app.i18n.IsoBusStrings
import net.vertexdezign.vdt.app.i18n.Language
import net.vertexdezign.vdt.app.i18n.LauncherStrings
import net.vertexdezign.vdt.app.i18n.LoaderStrings
import net.vertexdezign.vdt.app.i18n.LoansStrings
import net.vertexdezign.vdt.app.i18n.MarketStrings
import net.vertexdezign.vdt.app.i18n.MissionsStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.ProductionStrings
import net.vertexdezign.vdt.app.i18n.RotationStrings
import net.vertexdezign.vdt.app.i18n.SectionStrings
import net.vertexdezign.vdt.app.i18n.SettingsStrings
import net.vertexdezign.vdt.app.i18n.ShellStrings
import net.vertexdezign.vdt.app.i18n.StorageStrings
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.i18n.TasksStrings
import net.vertexdezign.vdt.app.i18n.VehicleStrings

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
  override val missions: MissionsStrings = EnMissions
  override val calendar: CalendarStrings = EnCalendar
  override val finance: FinanceStrings = EnFinance
  override val loans: LoansStrings = EnLoans
  override val invoices: InvoicesStrings = EnInvoices
  override val market: MarketStrings = EnMarket
  override val tasks: TasksStrings = EnTasks
  override val rotation: RotationStrings = EnRotation
  override val fields: FieldsStrings = EnFields
  override val autoDrive: AutoDriveStrings = EnAutoDrive
  override val diagnostics: DiagnosticsStrings = EnDiagnostics
  override val vehicle: VehicleStrings = EnVehicle
  override val cluster: ClusterStrings = EnCluster
  override val isoBus: IsoBusStrings = EnIsoBus
  override val baler: BalerStrings = EnBaler
  override val combine: CombineStrings = EnCombine
  override val loader: LoaderStrings = EnLoader
  override val section: SectionStrings = EnSection
}
