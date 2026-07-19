package net.vertexdezign.vdt.app.apps

import androidx.compose.runtime.Composable

/**
 * The registered [VdtApp]s, in launcher order. To add an app, implement [VdtApp] and add it here —
 * the launcher lists it and its [VdtApp.widgets] flow into `WidgetRegistry`.
 */
object AppRegistry {
  val apps: List<VdtApp> = listOf(VehicleApp, MapApp, ProductionApp, StorageApp, AnimalsApp, TasksApp, CropRotationApp)

  fun byId(id: String): VdtApp? = apps.firstOrNull { it.id == id }
}

/**
 * The apps present in this installation — [AppRegistry.apps] minus those whose optional mod is
 * missing. This is what the launcher lists.
 *
 * [VdtApp.isAvailable] is asked of every app on every recomposition, in registry order: it reads
 * telemetry state, so calling it conditionally (short-circuiting it inside a predicate) would make
 * the number of composable calls vary and break Compose's positional memoization.
 */
@Composable
fun availableApps(): List<VdtApp> = buildList {
  for (app in AppRegistry.apps) {
    if (app.isAvailable()) add(app)
  }
}
