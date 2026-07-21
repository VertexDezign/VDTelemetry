package net.vertexdezign.vdt.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.vertexdezign.vdt.VdtParser
import net.vertexdezign.vdt.model.MapLayersData
import net.vertexdezign.vdt.model.contentVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `/api/map-layer/{id}` caching contract: a `?v=` URL is content-addressed and may therefore be
 * cached immutably, so the route must only ever serve the version that was actually asked for.
 */
class MapLayerRouteTest {
  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/mapLayers/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/mapLayers/$name from ${File(".").absolutePath}")
  }

  private val data: MapLayersData = VdtParser.parseMapLayers(example("basic.json"))

  private fun withRoute(
    current: () -> MapLayersData?,
    block: suspend (io.ktor.client.HttpClient) -> Unit,
  ) = testApplication {
    application { routing { mapLayerRoute(current) } }
    block(client)
  }

  @Test
  fun servesTheRequestedVersionImmutably() =
    withRoute({ data }) { client ->
      val response = client.get("/api/map-layer/crops?v=${data.contentVersion()}")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsBytes().isNotEmpty())
      assertEquals("max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
    }

  @Test
  fun rejectsAStaleVersionInsteadOfServingCurrentBytesUnderIt() =
    withRoute({ data }) { client ->
      // The regression: a sweep landing between the WebSocket broadcast and this request must not
      // cause the new raster to be cached for a year under the old version's URL.
      val response = client.get("/api/map-layer/crops?v=deadbeef")
      assertEquals(HttpStatusCode.Conflict, response.status)
      assertNotEquals("max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
    }

  @Test
  fun servesAnUnversionedRequestWithoutImmutableCaching() =
    withRoute({ data }) { client ->
      val response = client.get("/api/map-layer/crops")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

  @Test
  fun unknownLayerIsNotFound() =
    withRoute({ data }) { client ->
      assertEquals(HttpStatusCode.NotFound, client.get("/api/map-layer/nope").status)
    }

  @Test
  fun missingDataIsNotFound() =
    withRoute({ null }) { client ->
      assertEquals(HttpStatusCode.NotFound, client.get("/api/map-layer/crops").status)
    }
}
