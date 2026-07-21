package net.vertexdezign.vdt.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.vertexdezign.vdt.model.MapLayersData
import net.vertexdezign.vdt.model.contentVersion

/**
 * `GET /api/map-layer/{id}?v={version}` — the rendered ground-layer PNG for one layer id.
 *
 * The raster never crosses the WebSocket (a 512² grid × 3 layers is far too heavy to push on every
 * sweep); the app is told only the legends plus a content version, and fetches the pixels here.
 *
 * That `?v=` is a promise: the bytes at this exact URL never change, which is what lets the response
 * be cached immutably for a year (unlike `/api/map-image`, which has no versioning). Keeping the
 * promise means checking it — see the mismatch branch below.
 *
 * Takes the current data as a supplier rather than a value so the route reads whatever the file
 * watcher last parsed, and so tests can drive it without standing up the watcher.
 */
fun Route.mapLayerRoute(currentData: () -> MapLayersData?) {
  get("/api/map-layer/{id}") {
    val data = currentData()
    val layerId = call.parameters["id"]
    if (data == null || layerId == null) {
      call.respondText("Ground layer not available", status = HttpStatusCode.NotFound)
      return@get
    }
    val requested = call.request.queryParameters["v"]
    val current = data.contentVersion()
    // A sweep can land between the WebSocket broadcast that gave the app this version and this
    // request. Rendering the newer raster here would cache it under the older version's URL forever,
    // and since versions are content-derived they recur — that poisoned entry would then be served
    // for a later, legitimately matching request. Reject instead: the app is about to be told about
    // the new version over the WebSocket, and will fetch the URL that matches it.
    if (requested != null && requested != current) {
      call.respondText("Stale ground-layer version: $requested", status = HttpStatusCode.Conflict)
      return@get
    }
    val bytes = MapLayerRenderer.rendered(data, layerId, current)
    if (bytes == null) {
      call.respondText("Unknown ground layer: $layerId", status = HttpStatusCode.NotFound)
      return@get
    }
    // Only a version-pinned URL is immutable. A bare /api/map-layer/{id} (hand-typed, or a probe)
    // returns whatever happens to be current, so it must not be cached under that unversioned URL.
    val caching = if (requested != null) "max-age=31536000, immutable" else "no-store"
    call.response.headers.append(HttpHeaders.CacheControl, caching)
    call.respondBytes(bytes, ContentType.Image.PNG)
  }
}
