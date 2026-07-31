package net.vertexdezign.vdt.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.ChannelStatsData
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ServerMessage
import net.vertexdezign.vdt.VdtParser
import net.vertexdezign.vdt.model.MapLayersInfo
import org.slf4j.LoggerFactory

// How often the observed-cadence diagnostics snapshot is taken + broadcast. Slow on purpose: it's a
// diagnostics feed, and staleness only needs second-ish resolution.
private const val CHANNEL_STATS_INTERVAL_MS = 1000L

fun main() {
  val log = LoggerFactory.getLogger("VDTerminal")
  val json = Json { encodeDefaults = true }

  val telemetryPath = Config.telemetryPath()
  log.info("Game directory: {}", Config.gameDir())
  log.info("Telemetry file: {}", telemetryPath)
  log.info("Debounce: {} ms", Config.debounceMs())

  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  // One watcher over the telemetry directory feeds a StateFlow per file. taskList.json and
  // cropRotation.json are optional: a file's absence is the "mod not installed" signal, so those
  // flows reset to null when the file is gone.
  val watcher = TelemetryWatcher(telemetryPath.parent, Config.debounceMs())
  val telemetryState =
    watcher.register(telemetryPath.fileName.toString(), nullOnAbsent = false) {
      VdtParser.parseJson(it)
    }
  val taskListState = watcher.register("taskList.json", nullOnAbsent = true) { VdtParser.parseTaskList(it) }
  val cropRotationState =
    watcher.register("cropRotation.json", nullOnAbsent = true) { VdtParser.parseCropRotation(it) }
  // map.json is event-driven too, but its absence means "no data yet / export off" rather than
  // "mod not installed" — either way the app must drop its overlays, so null broadcasts as well.
  val mapState = watcher.register("map.json", nullOnAbsent = true) { VdtParser.parseMap(it) }
  // mapVehicles.json rewrites on the mod's own ~1 s vehicle interval; same absence rule.
  val mapVehiclesState = watcher.register("mapVehicles.json", nullOnAbsent = true) { VdtParser.parseMapVehicles(it) }
  // gpsCourse.json is rewritten only when the steering course changes — once per field, not on a
  // clock. Same absence rule; an empty course is published as a file rather than by deleting it.
  val gpsCourseState = watcher.register("gpsCourse.json", nullOnAbsent = true) { VdtParser.parseGpsCourse(it) }
  // fieldInfo.json is interval-driven (per-field agronomy, resampled as crops grow); same "absence
  // means no data / export off" rule as map.json — the app drops back to the geometry rows.
  val fieldInfoState = watcher.register("fieldInfo.json", nullOnAbsent = true) { VdtParser.parseFieldInfo(it) }
  // production.json / storage.json rewrite on the mod's own ~2 s interval; same absence rule as map.json.
  val productionState =
    watcher.register("production.json", nullOnAbsent = true) { VdtParser.parseProduction(it) }
  val storageState =
    watcher.register("storage.json", nullOnAbsent = true) { VdtParser.parseStorage(it) }
  // husbandry.json is interval-driven too (own animal pens); same absence rule.
  val husbandryState = watcher.register("husbandry.json", nullOnAbsent = true) { VdtParser.parseHusbandry(it) }
  watcher.launchIn(appScope)

  // The ground-layer rasters live in their own folder, one file per plane plus index.json naming the
  // planes this map offers. Which planes exist is the mod's to decide (base game today, Precision
  // Farming later), so the planes are taken as a keyed map rather than registered by name -- see
  // TelemetryWatcher.registerRest. Its own watcher because it is its own directory; the folder appears
  // when the mod loads a map, which the watcher's retry loop already handles.
  val layerWatcher = TelemetryWatcher(telemetryPath.parent.resolve("mapLayers"), Config.debounceMs())
  val mapLayerCatalogState =
    layerWatcher.register("index.json", nullOnAbsent = true) { VdtParser.parseMapLayerCatalog(it) }
  val mapLayerState = layerWatcher.registerRest { VdtParser.parseMapLayer(it) }
  layerWatcher.launchIn(appScope)

  // Diagnostics: sample every channel's observed write cadence on a slow timer (independent of the
  // channels' own updates, so an idle channel's staleness keeps advancing). One shared flow; each
  // session collects it, same as the data channels.
  // Both watchers' files, in one feed: the panel lists channels, not directories.
  fun cadenceSnapshot(): ChannelStatsData {
    val telemetry = watcher.snapshotCadence()
    return telemetry.copy(channels = telemetry.channels + layerWatcher.snapshotCadence().channels)
  }

  val channelStatsState = MutableStateFlow(cadenceSnapshot())
  appScope.launch {
    while (isActive) {
      delay(CHANNEL_STATS_INTERVAL_MS)
      channelStatsState.value = cadenceSnapshot()
    }
  }

  val commandWriter = CommandWriter(Config.commandPath())
  log.info("Command file: {}", Config.commandPath())

  // Which ground-layer planes the connected dashboards are showing; the mod sweeps only those.
  val mapLayerSubscriptions =
    MapLayerSubscriptions { ids ->
      log.info("Ground-layer subscription: {}", if (ids.isEmpty()) "(none)" else ids.joinToString(","))
      commandWriter.submit(ClientMessage.SetMapLayers(ids))
    }
  // The mod reports which planes it is actually sweeping in its catalogue, so every time it says so,
  // check that against what the dashboards want and restate the command when they disagree. That is
  // what makes the subscription survive a lossy channel: the mod deletes commands.xml at every map
  // load, so a subscription sent while the game was at a menu or loading never arrived -- and since
  // the desire itself never changed, nothing else would ever send it again. It covers the reverse
  // too (a server restart under a running game, where the mod is still sweeping for dashboards that
  // are gone), which is why there is no separate startup write.
  appScope.launch {
    mapLayerCatalogState.collect { catalog ->
      if (catalog == null) return@collect // channel off / no map loaded: nothing to reconcile against
      mapLayerSubscriptions.reconcile(
        modActive = catalog.layers.filter { it.active }.map { it.id },
        offered = catalog.layers.map { it.id },
      )
    }
  }
  val sessionIds =
    java.util.concurrent.atomic
      .AtomicLong()

  log.info("Server starting on port {}", Config.port)
  embeddedServer(Netty, port = Config.port) {
    install(WebSockets)
    install(ContentNegotiation) { json(json) }
    install(CORS) {
      anyHost() // LAN tool, same as the Go server
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Options)
      allowHeader(HttpHeaders.ContentType)
    }

    routing {
      get("/health") { call.respondText("OK") }

      webSocket("/ws") {
        // Identifies this session for as long as its socket lives -- see mapLayerSubscriptions, the one
        // piece of per-session state the server keeps.
        val sessionId = sessionIds.incrementAndGet()
        // Outgoing: push each StateFlow's current value on connect + every subsequent update. One job
        // per channel so the slow taskList feed broadcasts on its own cadence, not the telemetry tick.
        val sendJob =
          launch {
            telemetryState.collect { data ->
              if (data != null) {
                val message: ServerMessage = ServerMessage.Telemetry(data)
                send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
              }
            }
          }
        // The optional channels broadcast their null too: null means "mod not installed" (file gone),
        // and the app keeps whatever it was last sent — swallowing the null would leave it rendering
        // a stale panel for a mod that has since been uninstalled.
        val taskListJob =
          launch {
            taskListState.collect { data ->
              val message: ServerMessage = ServerMessage.TaskList(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val cropRotationJob =
          launch {
            cropRotationState.collect { data ->
              val message: ServerMessage = ServerMessage.CropRotation(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val mapJob =
          launch {
            mapState.collect { data ->
              val message: ServerMessage = ServerMessage.MapUpdate(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val mapVehiclesJob =
          launch {
            mapVehiclesState.collect { data ->
              val message: ServerMessage = ServerMessage.MapVehicles(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val gpsCourseJob =
          launch {
            gpsCourseState.collect { data ->
              val message: ServerMessage = ServerMessage.GpsCourse(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val fieldInfoJob =
          launch {
            fieldInfoState.collect { data ->
              val message: ServerMessage = ServerMessage.FieldInfo(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val productionJob =
          launch {
            productionState.collect { data ->
              val message: ServerMessage = ServerMessage.Production(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val storageJob =
          launch {
            storageState.collect { data ->
              val message: ServerMessage = ServerMessage.Storage(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val husbandryJob =
          launch {
            husbandryState.collect { data ->
              val message: ServerMessage = ServerMessage.Husbandry(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val channelStatsJob =
          launch {
            channelStatsState.collect { data ->
              val message: ServerMessage = ServerMessage.ChannelStats(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        // The raster rows never cross the WebSocket -- only legends + a content-derived version, so
        // the app knows when to refetch the PNG from /api/map-layer/{id}.
        val mapLayersJob =
          launch {
            // The catalogue decides whether there is anything to announce at all; the rasters fill in
            // each plane's legend + version as they are swept, so both feed one broadcast.
            combine(mapLayerCatalogState, mapLayerState) { catalog, rasters ->
              catalog?.let { MapLayersInfo.from(it, rasters) }
            }.collect { info ->
              val message: ServerMessage = ServerMessage.MapLayers(info)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        // Incoming: app -> mod commands. Decode and hand to the writer; ignore anything unparseable
        // so a bad frame can't kill the session. Reading `incoming` also keeps the socket alive.
        try {
          for (frame in incoming) {
            if (frame is Frame.Text) {
              try {
                when (val message = json.decodeFromString(ClientMessage.serializer(), frame.readText())) {
                  // Session-scoped: what THIS dashboard is showing. The mod gets the union across all
                  // of them, which the registry submits when it changes -- so this one is not written
                  // through as sent.
                  is ClientMessage.SetMapLayers -> mapLayerSubscriptions.show(sessionId, message.ids)

                  else -> commandWriter.submit(message)
                }
              } catch (e: Exception) {
                log.warn("Ignoring unparseable client message", e)
              }
            }
          }
        } finally {
          // This dashboard is gone, so its planes stop counting toward the union: the last one to
          // leave takes the mod's sweep with it.
          mapLayerSubscriptions.forget(sessionId)
          sendJob.cancel()
          taskListJob.cancel()
          cropRotationJob.cancel()
          mapJob.cancel()
          mapVehiclesJob.cancel()
          gpsCourseJob.cancel()
          fieldInfoJob.cancel()
          productionJob.cancel()
          storageJob.cancel()
          husbandryJob.cancel()
          channelStatsJob.cancel()
          mapLayersJob.cancel()
        }
      }

      mapLayerRoute { mapLayerState.value }

      get("/api/map-image") {
        val pda =
          telemetryState.value
            ?.environment
            ?.pda
        val filename = pda?.filename
        if (pda == null || filename.isNullOrBlank()) {
          call.respondText("PDA / filename not available", status = HttpStatusCode.NotFound)
          return@get
        }
        val asset = AssetResolver.resolve(Config.gameDir(), filename)
        if (asset == null) {
          call.respondText("Image not found: $filename", status = HttpStatusCode.NotFound)
          return@get
        }
        try {
          val (bytes, contentType) =
            ImagePipeline.process(asset.bytes, filename, pda.width ?: 0, pda.height ?: 0)
          call.respondBytes(bytes, ContentType.parse(contentType))
        } catch (e: Exception) {
          log.error("Failed to process map image {}", filename, e)
          call.respondText("Error processing image", status = HttpStatusCode.InternalServerError)
        }
      }

      // The built wasm dashboard. Declared last so /health, /ws and /api take precedence.
      dashboardRoute()
    }
  }.start(wait = true)
}
