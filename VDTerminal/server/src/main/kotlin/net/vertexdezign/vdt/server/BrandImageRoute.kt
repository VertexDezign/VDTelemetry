package net.vertexdezign.vdt.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.vertexdezign.vdt.model.Brand
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The brand logos the mod has named, and the ones already rendered.
 *
 * The route is addressed by brand *name*, never by path: the path is the mod's, read off telemetry,
 * so the URL can only ever reach a file the game itself registered as a brand logo. Every brand seen
 * is remembered rather than just the current one, because the app asks after the vehicle it was just
 * told about — and by the time the request lands the player may already have switched to another.
 */
class BrandImages {
  private val paths = ConcurrentHashMap<String, String>()
  private val rendered = ConcurrentHashMap<String, ByteArray>()

  fun note(brand: Brand?) {
    val name = brand?.name?.takeIf { it.isNotBlank() } ?: return
    val image = brand.image?.takeIf { it.isNotBlank() } ?: return
    paths[name] = image
  }

  fun pathOf(name: String): String? = paths[name]

  /** The PNG for [path], rendered once: a session switches vehicles far more often than it gains brands. */
  fun renderedOr(path: String, render: () -> ByteArray?): ByteArray? =
    rendered[path] ?: render()?.also { rendered[path] = it }
}

private val log = LoggerFactory.getLogger("net.vertexdezign.vdt.server.BrandImageRoute")

/**
 * `GET /api/brand-image/{name}` — the brand's logo as a PNG, trimmed to what is drawn in it.
 *
 * Read out of the game install the way `/api/map-image` reads the overview, `.png`→`.dds` swap
 * included (see [AssetResolver.lookupTexture]). A failure answers with the same kind of body that
 * route gives — the path, the game folder, everywhere looked — although the app shows none of it: a
 * logo it cannot have is simply the brand's name in text, which is what the header showed before.
 *
 * [current] is the brand on the latest telemetry, noted on every request so a logo is found even if
 * the request beats the server's own bookkeeping of that frame.
 */
fun Route.brandImageRoute(images: BrandImages, gameDir: () -> Path, current: () -> Brand?) {
  get("/api/brand-image/{name}") {
    images.note(current())
    val name = call.parameters["name"].orEmpty()
    val path = images.pathOf(name)
    if (path == null) {
      call.respondText("The mod has reported no logo for brand '$name'.", status = HttpStatusCode.NotFound)
      return@get
    }
    val dir = gameDir()
    var failure: String? = null
    val png = images.renderedOr(path) { renderLogo(dir, path).onFailure { failure = it.message }.getOrNull() }
    if (png == null) {
      log.warn("Brand logo {} unavailable: {}", name, failure?.lines()?.joinToString(" | ") { it.trim() })
      call.respondText(failure.orEmpty(), status = HttpStatusCode.NotFound)
      return@get
    }
    // A brand's logo does not change during a session; an hour spares the tablet a refetch on reload.
    call.response.headers.append(HttpHeaders.CacheControl, "max-age=3600")
    call.respondBytes(png, ContentType.Image.PNG)
  }
}

/** The logo at [path] as a trimmed PNG, or a failure whose message is the body the route answers with. */
private fun renderLogo(gameDir: Path, path: String): Result<ByteArray> {
  val lookup = AssetResolver.lookupTexture(gameDir, path)
  val asset =
    lookup.asset ?: return Result.failure(
      IllegalStateException(
        buildString {
          appendLine("Brand logo not found.")
          appendLine("The mod reported: $path")
          appendLine("Game folder:     $gameDir")
          appendLine("Looked at:")
          lookup.tried.forEach { appendLine("  $it") }
        },
      ),
    )
  val (bytes, contentType) =
    runCatching { ImagePipeline.process(asset.bytes, asset.entry ?: asset.path.toString(), trim = true) }
      .getOrElse {
        return Result.failure(
          IllegalStateException("Brand logo could not be decoded: ${it.message}\nSource: ${asset.source}"),
        )
      }
  // A format ImagePipeline passes through undecoded is no picture the app can draw.
  if (contentType !=
    "image/png"
  ) {
    return Result.failure(IllegalStateException("Brand logo is not an image: ${asset.source}"))
  }
  log.info("Brand logo served from {}", asset.source)
  return Result.success(bytes)
}
