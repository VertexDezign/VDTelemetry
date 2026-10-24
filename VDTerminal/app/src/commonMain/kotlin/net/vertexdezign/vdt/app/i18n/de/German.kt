package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.AlertsStrings
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
import net.vertexdezign.vdt.app.i18n.MapStrings
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
import net.vertexdezign.vdt.app.i18n.TirePressureStrings
import net.vertexdezign.vdt.app.i18n.VehicleStrings
import net.vertexdezign.vdt.app.i18n.WidgetsStrings

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
  override val animals: AnimalsStrings = DeAnimals
  override val storage: StorageStrings = DeStorage
  override val production: ProductionStrings = DeProduction
  override val missions: MissionsStrings = DeMissions
  override val calendar: CalendarStrings = DeCalendar
  override val finance: FinanceStrings = DeFinance
  override val loans: LoansStrings = DeLoans
  override val invoices: InvoicesStrings = DeInvoices
  override val market: MarketStrings = DeMarket
  override val tasks: TasksStrings = DeTasks
  override val rotation: RotationStrings = DeRotation
  override val fields: FieldsStrings = DeFields
  override val autoDrive: AutoDriveStrings = DeAutoDrive
  override val tirePressure: TirePressureStrings = DeTirePressure
  override val diagnostics: DiagnosticsStrings = DeDiagnostics
  override val vehicle: VehicleStrings = DeVehicle
  override val cluster: ClusterStrings = DeCluster
  override val isoBus: IsoBusStrings = DeIsoBus
  override val baler: BalerStrings = DeBaler
  override val combine: CombineStrings = DeCombine
  override val loader: LoaderStrings = DeLoader
  override val section: SectionStrings = DeSection
  override val map: MapStrings = DeMap
  override val alerts: AlertsStrings = DeAlerts
  override val widgets: WidgetsStrings = DeWidgets
}
