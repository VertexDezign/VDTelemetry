package net.vertexdezign.vdt.server

import io.ktor.http.ContentType
import io.ktor.http.defaultForFilePath
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route

/** The web app manifest's media type; see [dashboardRoute] for why it has to be spelled out. */
private val MANIFEST_JSON = ContentType("application", "manifest+json")

/**
 * Serves the built wasm dashboard from the server's own resources — `index.html` at `/`, plus
 * `app.js`, the `*.wasm` blobs, the icons and the web app manifest. Declared last in the routing
 * block so `/health`, `/ws` and `/api` take precedence.
 *
 * The only thing here that isn't Ktor's default: Ktor's extension→MIME table has no entry for
 * `.webmanifest`, and the `application/octet-stream` it falls back to is not a JSON media type, which
 * per the spec makes a browser drop the manifest on the floor. That would cost "Add to Home Screen" —
 * how a phone becomes a chrome-free display (`?display=<page>`) — with no error anywhere to explain it.
 */
fun Route.dashboardRoute() {
  staticResources("/", "static") {
    contentType { url ->
      if (url.path.endsWith(".webmanifest")) MANIFEST_JSON else ContentType.defaultForFilePath(url.path)
    }
  }
}
