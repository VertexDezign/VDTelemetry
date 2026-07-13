package net.vertexdezign.vdt.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ServerMessage
import net.vertexdezign.vdt.VdtParser
import net.vertexdezign.vdt.model.MapLayersInfo
import org.slf4j.LoggerFactory

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
  // mapLayers.json rewrites on the mod's own multi-second sweep cadence; same absence rule.
  val mapLayersState = watcher.register("mapLayers.json", nullOnAbsent = true) { VdtParser.parseMapLayers(it) }
  watcher.launchIn(appScope)

  val commandWriter = CommandWriter(Config.commandPath())
  log.info("Command file: {}", Config.commandPath())

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
        // The raster rows never cross the WebSocket -- only legends + a content-derived version, so
        // the app knows when to refetch the PNG from /api/map-layer/{id}.
        val mapLayersJob =
          launch {
            mapLayersState.collect { data ->
              val message: ServerMessage = ServerMessage.MapLayers(data?.let { MapLayersInfo.from(it) })
              send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
            }
          }
        // Incoming: app -> mod commands. Decode and hand to the writer; ignore anything unparseable
        // so a bad frame can't kill the session. Reading `incoming` also keeps the socket alive.
        try {
          for (frame in incoming) {
            if (frame is Frame.Text) {
              try {
                val message = json.decodeFromString(ClientMessage.serializer(), frame.readText())
                commandWriter.submit(message)
              } catch (e: Exception) {
                log.warn("Ignoring unparseable client message", e)
              }
            }
          }
        } finally {
          sendJob.cancel()
          taskListJob.cancel()
          cropRotationJob.cancel()
          mapJob.cancel()
          mapVehiclesJob.cancel()
          mapLayersJob.cancel()
        }
      }

      get("/api/map-layer/{id}") {
        val data = mapLayersState.value
        val layerId = call.parameters["id"]
        if (data == null || layerId == null) {
          call.respondText("Ground layer not available", status = HttpStatusCode.NotFound)
          return@get
        }
        val bytes = MapLayerRenderer.rendered(data, layerId)
        if (bytes == null) {
          call.respondText("Unknown ground layer: $layerId", status = HttpStatusCode.NotFound)
          return@get
        }
        // Content-addressed via the ?v= query param (MapLayersInfo.version): the bytes at this exact
        // URL never change, unlike /api/map-image which has no such versioning.
        call.response.headers.append(HttpHeaders.CacheControl, "max-age=31536000, immutable")
        call.respondBytes(bytes, ContentType.Image.PNG)
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

      // Serve the built wasm dashboard (index.html at "/", plus app.js / *.wasm / assets).
      // Declared last so /health, /ws and /api take precedence.
      staticResources("/", "static")
    }
  }.start(wait = true)
}
