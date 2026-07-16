package net.vertexdezign.vdt.app.apps

/**
 * The registered [VdtApp]s, in launcher order. To add an app, implement [VdtApp] and add it here —
 * the launcher lists it and its [VdtApp.widgets] flow into `WidgetRegistry`.
 */
object AppRegistry {
  val apps: List<VdtApp> = listOf(VehicleApp, MapApp, TasksApp, CropRotationApp)

  fun byId(id: String): VdtApp? = apps.firstOrNull { it.id == id }
}
