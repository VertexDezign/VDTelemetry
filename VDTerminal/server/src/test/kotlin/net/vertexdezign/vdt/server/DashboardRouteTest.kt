package net.vertexdezign.vdt.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The static dashboard route, over the *real* bundle: `:server:processResources` embeds the built wasm
 * app, so these assertions run against the same bytes a user gets.
 */
class DashboardRouteTest {
  private fun withDashboard(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
    application { routing { dashboardRoute() } }
    block(client)
  }

  /**
   * The one thing Ktor doesn't get right on its own. A manifest served as `application/octet-stream`
   * is not a JSON media type, so the browser discards it and "Add to Home Screen" quietly stops
   * producing a standalone window — the failure has no error message anywhere, hence the test.
   */
  @Test
  fun servesTheWebManifestAsAJsonMediaType() = withDashboard { client ->
    val response = client.get("/manifest.webmanifest")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
      ContentType("application", "manifest+json"),
      ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters(),
    )
    assertTrue(response.bodyAsText().contains("\"icons\""))
  }

  /** The manifest's icons have to actually resolve, or an installed shortcut has no icon. */
  @Test
  fun servesTheIconsTheManifestNames() = withDashboard { client ->
    for (icon in listOf("icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png")) {
      val response = client.get("/icons/$icon")
      assertEquals(HttpStatusCode.OK, response.status, icon)
      assertEquals(
        ContentType.Image.PNG,
        ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters(),
        icon,
      )
    }
  }

  /**
   * The wake-lock fallback plays these, and can only play what it can fetch. They exist for the
   * device that has no Screen Wake Lock API — a tablet on a plain-http LAN address — so a 404 or a
   * media type a browser won't play is a cluster that dims halfway down the field, on the one screen
   * nobody is going to touch to wake up.
   */
  @Test
  fun servesTheKeepAwakeClipsAsVideo() = withDashboard { client ->
    val clips =
      mapOf(
        "keep-awake.webm" to ContentType("video", "webm"),
        "keep-awake.mp4" to ContentType("video", "mp4"),
      )
    for ((clip, type) in clips) {
      val response = client.get("/media/$clip")
      assertEquals(HttpStatusCode.OK, response.status, clip)
      assertEquals(
        type,
        ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters(),
        clip,
      )
    }
  }

  /** Overriding one extension must not have replaced Ktor's table for the rest of the bundle. */
  @Test
  fun stillTypesTheAppNormally() = withDashboard { client ->
    val index = client.get("/")
    assertEquals(HttpStatusCode.OK, index.status)
    assertEquals(
      ContentType.Text.Html,
      ContentType.parse(index.headers[HttpHeaders.ContentType]!!).withoutParameters(),
    )
    val script = client.get("/app.js")
    assertEquals(HttpStatusCode.OK, script.status)
    // text/javascript, not application/javascript: Ktor follows the type WHATWG settled on.
    assertEquals(
      ContentType.Text.JavaScript,
      ContentType.parse(script.headers[HttpHeaders.ContentType]!!).withoutParameters(),
    )
  }
}
