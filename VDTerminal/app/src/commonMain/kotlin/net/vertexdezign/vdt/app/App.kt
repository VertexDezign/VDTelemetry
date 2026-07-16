package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.layout.WidgetDashboard
import net.vertexdezign.vdt.app.net.ConnectionState
import net.vertexdezign.vdt.app.pages.AutoShow
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.panels.Footer
import net.vertexdezign.vdt.app.panels.Header
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.state.VdtStore
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.VdtData

@Composable
fun App(store: VdtStore, modifier: Modifier = Modifier) {
  CompositionLocalProvider(LocalVdtStore provides store) {
    val telemetry by store.telemetry.collectAsState()
    val connection by store.connection.collectAsState()
    val pages by store.pages.pages.collectAsState()

    // Auto-switch on each enter/leave transition: activate the first page that opts into the new
    // state. Keying the effect on the resolved *id* (which only changes when the state flips) means a
    // manual pick from the launcher stays put until the next transition. Only pages auto-show; apps
    // are opened by hand.
    val onFoot = telemetry?.vehicle == null
    val wanted = if (onFoot) AutoShow.OnFoot else AutoShow.InVehicle
    val autoPageId = pages.firstOrNull { it.autoShow == wanted }?.id

    var screen by remember { mutableStateOf(initialScreen(autoPageId, pages)) }
    LaunchedEffect(autoPageId) { autoPageId?.let { screen = Screen.OpenPage(it) } }
    // The open page can vanish (deleted in edit mode) — fall back rather than render nothing.
    LaunchedEffect(pages, screen) {
      val open = screen
      if (open is Screen.OpenPage && pages.none { it.id == open.pageId }) {
        screen = initialScreen(autoPageId, pages)
      }
    }

    var launcherOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    MaterialTheme {
      Box(modifier.fillMaxSize().background(VdtColors.Light)) {
        val data = telemetry
        when {
          data == null -> LoadingScreen()

          else ->
            Shell(
              data,
              screen,
              pages,
              editing = editing,
              onToggleEdit = { editing = !editing },
              onOpenLauncher = { launcherOpen = true },
            )
        }

        if (connection != ConnectionState.Connected) {
          Box(
            Modifier.fillMaxSize().background(VdtColors.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              if (connection ==
                ConnectionState.Connecting
              ) {
                "CONNECTING…"
              } else {
                "CONNECTION LOST — RECONNECTING…"
              },
              color = VdtColors.White,
              fontSize = 22.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        if (launcherOpen) {
          Launcher(
            apps = AppRegistry.apps,
            pages = pages,
            screen = screen,
            onOpen = {
              // Apps aren't editable, so leaving a page ends edit mode.
              if (it is Screen.OpenApp) editing = false
              screen = it
              launcherOpen = false
            },
            onCreatePage = {
              // Open the new (empty) page straight into edit mode — there's nothing on it yet.
              screen = Screen.OpenPage(store.pages.create().id)
              editing = true
              launcherOpen = false
            },
            onDismiss = { launcherOpen = false },
          )
        }
      }
    }
  }
}

/** The page that should auto-show, else any page, else the first app (the user deleted every page). */
private fun initialScreen(autoPageId: String?, pages: List<Page>): Screen = autoPageId?.let(Screen::OpenPage)
  ?: pages.firstOrNull()?.let { Screen.OpenPage(it.id) }
  ?: Screen.OpenApp(AppRegistry.apps.first().id)

@Composable
private fun LoadingScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("VDTERMINAL LOADING…", color = VdtColors.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun Shell(
  data: VdtData,
  screen: Screen,
  pages: List<Page>,
  editing: Boolean,
  onToggleEdit: () -> Unit,
  onOpenLauncher: () -> Unit,
) {
  val store = LocalVdtStore.current
  val wakeLock by store.wakeLock.collectAsState()

  Column(Modifier.fillMaxSize()) {
    Header(
      data.environment,
      data.vehicle,
      wakeLock = wakeLock,
      editing = editing,
      // Only pages have an editable layout; the edit toggle is hidden while an app is open.
      canEdit = screen is Screen.OpenPage,
      onToggleWakeLock = store.onToggleWakeLock,
      onToggleEdit = onToggleEdit,
      onOpenLauncher = onOpenLauncher,
    )

    when (screen) {
      is Screen.OpenPage -> {
        val page = pages.firstOrNull { it.id == screen.pageId }
        if (page != null) WidgetDashboard(page, editing) else Box(Modifier.fillMaxWidth().weight(1f))
      }

      is Screen.OpenApp -> {
        val app = AppRegistry.byId(screen.appId)
        Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
          app?.FullPage(Modifier.fillMaxSize())
        }
      }
    }

    Footer(data.vehicle, onCommand = store.onCommand)
  }
}
