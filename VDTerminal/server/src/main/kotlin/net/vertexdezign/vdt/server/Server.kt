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
import io.ktor.server.routing.post
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
import net.vertexdezign.vdt.model.COVERAGE_LAYER_ID
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerInfo
import net.vertexdezign.vdt.model.MapLayersCatalog
import net.vertexdezign.vdt.model.MapLayersInfo
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.io.path.isDirectory

/**
 * What the app is offered in its ground-layer picker: the mod's planes for this map, plus the
 * server's own coverage layer.
 *
 * Coverage is listed **unconditionally**, even before anything has been worked and even when the mod's
 * layer channel is switched off entirely — it does not come from the mod, so its availability must not
 * depend on the mod's catalogue arriving. Until there is a mask its version is null, which the app
 * already understands as "offered, nothing to draw".
 */
private fun layersInfo(
  catalog: MapLayersCatalog?,
  rasters: Map<String, MapLayerData>,
  coverage: MapLayerData?,
): MapLayersInfo {
  val modPlanes = catalog?.let { MapLayersInfo.from(it, rasters).layers }.orEmpty()
  return MapLayersInfo(
    modPlanes +
      MapLayerInfo(
        id = COVERAGE_LAYER_ID,
        label = "Coverage",
        version = coverage?.contentVersion,
        legend = coverage?.legend.orEmpty(),
      ),
  )
}

// How often the observed-cadence diagnostics snapshot is taken + broadcast. Slow on purpose: it's a
// diagnostics feed, and staleness only needs second-ish resolution.
private const val CHANNEL_STATS_INTERVAL_MS = 1000L

/**
 * How often the coverage mask is published as a new raster version.
 *
 * Deliberately far slower than the telemetry tick that feeds it. Every published version is a PNG the
 * app refetches, so publishing at 10 Hz would mean a fetch, a render and a decode ten times a second
 * for a picture that grows by one swath in that time. Coverage is a trail, not an instrument.
 */
private const val COVERAGE_PUBLISH_INTERVAL_MS = 2000L

/**
 * The addresses this server can be opened on from another device.
 *
 * Site-local IPv4 only — the whole point is the tablet in the cab, and `localhost` is already
 * printed beside these. Best-effort: an interface enumeration that throws costs us the hint, not
 * the startup.
 */
private fun lanUrls(port: Int): List<String> =
  runCatching {
    Collections
      .list(NetworkInterface.getNetworkInterfaces())
      .filter { it.isUp && !it.isLoopback }
      .flatMap { Collections.list(it.inetAddresses) }
      .filterIsInstance<Inet4Address>()
      .filter { it.isSiteLocalAddress }
      .map { "http://${it.hostAddress}:$port" }
  }.getOrDefault(emptyList())

fun main() {
  val log = LoggerFactory.getLogger("VDTerminal")
  val json = Json { encodeDefaults = true }

  val telemetryPath = Config.telemetryPath()
  val gameDir = Config.gameDir()
  log.info("Game directory: {}", gameDir)
  log.info("Telemetry file: {}", telemetryPath)
  log.info("Debounce: {} ms", Config.debounceMs())

  // Say so at startup rather than serving an empty dashboard. Everything downstream of this — the
  // watcher, the map image, the command channel — reads from a folder that isn't there, and the
  // symptom (a terminal that connects fine and shows nothing) points at the wrong half of the setup.
  if (!gameDir.isDirectory()) {
    log.warn("Game directory not found: {}", gameDir)
    log.warn("Set VDT_GAME_DIR to the folder holding your FS25 profile (the one with modSettings/ in it).")
  }

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
  // missions.json is event-driven (a contract generated/accepted/finished) plus a slow interval for
  // the countdown; same absence rule -- the app must clear contracts rather than offer stale ones.
  val missionsState = watcher.register("missions.json", nullOnAbsent = true) { VdtParser.parseMissions(it) }
  // finance.json is interval-driven plus kicked by a money notification / month rollover / loan
  // change; same absence rule -- a stale balance is the last thing this dashboard should show.
  val financeState = watcher.register("finance.json", nullOnAbsent = true) { VdtParser.parseFinance(it) }
  // invoices.json is purely event-driven (an invoice raised/paid/withdrawn/answered/penalised). Here
  // the absence rule carries an extra meaning: the file only ever exists when FS25_Invoices is
  // installed, so null is what tells the app the whole feature is unavailable.
  val invoicesState = watcher.register("invoices.json", nullOnAbsent = true) { VdtParser.parseInvoices(it) }
  // cropCalendar.json is event-driven and nearly static -- rewritten once per in-game day for the
  // today marker. Same absence rule: on a different map the crop list is a different set entirely, so
  // the app must clear rather than keep the last one.
  val cropCalendarState =
    watcher.register("cropCalendar.json", nullOnAbsent = true) { VdtParser.parseCropCalendar(it) }
  // weather.json is event-driven on the in-game hour; same absence rule -- a stale forecast is worse
  // than none, since it is read to decide whether to cut hay.
  val weatherState = watcher.register("weather.json", nullOnAbsent = true) { VdtParser.parseWeather(it) }
  // fleet.json is interval-driven (the farm's machines, their condition and ADS's maintenance
  // record); same absence rule -- a machine that has quietly stopped being reported is the one thing
  // a list of machines must not do.
  val fleetState = watcher.register("fleet.json", nullOnAbsent = true) { VdtParser.parseFleet(it) }
  // prices.json is interval-driven on the mod's 30 s cadence -- the interval the game itself
  // refreshes a multiplayer client's prices on. Same absence rule: a stale board would value today's
  // stock at last session's prices.
  val pricesState = watcher.register("prices.json", nullOnAbsent = true) { VdtParser.parsePrices(it) }
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

  // Coverage: the one ground layer the server owns. Fed from the telemetry the dashboards already
  // receive, so the game does no extra work for it — see CoverageRecorder for why this is not in the
  // mod, and COVERAGE_LAYER_ID for how it reaches the app as an ordinary plane.
  val coverage = CoverageRecorder()
  val coverageState = MutableStateFlow<MapLayerData?>(null)
  appScope.launch {
    telemetryState.collect { data ->
      if (data == null) return@collect
      // The coordinates are already normalized; the terrain edge only sizes the grid. Either channel
      // that carries it will do, so coverage does not go dark because one of them is switched off —
      // and each is checked for a usable value rather than merely for being present, since a parsed
      // file with the key missing defaults it to zero.
      val terrainSize =
        listOfNotNull(mapState.value?.terrainSize, mapLayerCatalogState.value?.terrainSize)
          .firstOrNull { it > 0f } ?: 0f
      coverage.record(data.vehicle, terrainSize, System.currentTimeMillis())
    }
  }
  appScope.launch {
    while (isActive) {
      delay(COVERAGE_PUBLISH_INTERVAL_MS)
      coverage.snapshotIfChanged()?.let { coverageState.value = it }
    }
  }

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

  val commandPath = Config.commandPath()
  val commandWriter = CommandWriter(commandPath)
  log.info("Command file: {}", commandPath)

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
  log.info("Dashboard: http://localhost:{}", Config.port)
  lanUrls(Config.port).forEach { log.info("  from another device: {}", it) }
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
        val fleetJob =
          launch {
            fleetState.collect { data ->
              val message: ServerMessage = ServerMessage.Fleet(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val pricesJob =
          launch {
            pricesState.collect { data ->
              val message: ServerMessage = ServerMessage.Prices(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val missionsJob =
          launch {
            missionsState.collect { data ->
              val message: ServerMessage = ServerMessage.Missions(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val financeJob =
          launch {
            financeState.collect { data ->
              val message: ServerMessage = ServerMessage.Finance(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val invoicesJob =
          launch {
            invoicesState.collect { data ->
              val message: ServerMessage = ServerMessage.Invoices(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val cropCalendarJob =
          launch {
            cropCalendarState.collect { data ->
              val message: ServerMessage = ServerMessage.CropCalendar(data)
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        val weatherJob =
          launch {
            weatherState.collect { data ->
              val message: ServerMessage = ServerMessage.Weather(data)
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
            // The catalogue names the mod's planes; the rasters fill in each one's legend + version as
            // they are swept; coverage is the server's own and rides along on the same broadcast.
            combine(mapLayerCatalogState, mapLayerState, coverageState) { catalog, rasters, worked ->
              layersInfo(catalog, rasters, worked)
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
                  // through as sent. Coverage is stripped here rather than inside the registry: it is
                  // the server's own plane, and asking the mod to sweep one it has never heard of
                  // would be a mismatch its reconcile loop could never settle.
                  is ClientMessage.SetMapLayers -> {
                    mapLayerSubscriptions.show(sessionId, message.ids - COVERAGE_LAYER_ID)
                  }

                  else -> {
                    commandWriter.submit(message)
                  }
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
          fleetJob.cancel()
          pricesJob.cancel()
          missionsJob.cancel()
          financeJob.cancel()
          invoicesJob.cancel()
          cropCalendarJob.cancel()
          weatherJob.cancel()
          channelStatsJob.cancel()
          mapLayersJob.cancel()
        }
      }

      // The mod's swept planes plus the server's coverage mask, under one route: to the app they are
      // all just ground layers, fetched by id at the version the broadcast gave it.
      mapLayerRoute {
        val worked = coverageState.value
        if (worked == null) mapLayerState.value else mapLayerState.value + (COVERAGE_LAYER_ID to worked)
      }

      // Coverage is a trail the driver decides is finished — a new day, a new job, a field done — so
      // clearing it is a control rather than a rule. There is nothing for the mod to do here: the mask
      // never existed in the game, so this is the whole of it.
      post("/api/coverage/reset") {
        coverage.reset()
        // Published straight away rather than waiting for the timer: the app is about to be told a new
        // version exists and would otherwise refetch the old mask and redraw what was just cleared.
        coverage.snapshotIfChanged()?.let { coverageState.value = it }
        call.respondText("OK")
      }

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
