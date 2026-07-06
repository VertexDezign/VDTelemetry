package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.ServerMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.response.respondBytes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("VDTerminal")
    val json = Json { encodeDefaults = true }

    val telemetryPath = Config.telemetryPath()
    log.info("Game directory: {}", Config.gameDir())
    log.info("Telemetry file: {}", telemetryPath)
    log.info("Debounce: {} ms", Config.debounceMs())

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val source = TelemetrySource(telemetryPath, Config.debounceMs())
    source.launchIn(appScope)

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
                // Sending the StateFlow's current value on connect + every subsequent update.
                source.state.collect { data ->
                    if (data != null) {
                        val message: ServerMessage = ServerMessage.Telemetry(data)
                        send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
                    }
                }
            }

            get("/api/map-image") {
                val pda = source.state.value?.environment?.pda
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
