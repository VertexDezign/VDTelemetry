package net.vertexdezign.vdt.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `/api/map-layer/{id}` caching contract: a `?v=` URL is content-addressed and may therefore be
 * cached immutably, so the route must only ever serve the version that was actually asked for.
 */
class MapLayerRouteTest {
  /**
   * Synthetic, like the renderer's: what this asserts is the caching contract, so the plane only has
   * to be a plane with a stable content version — a real capture would be 500 KB of raster to test a
   * URL query parameter with.
   */
  private fun plane(id: String, cell: String) = MapLayerData(
    version = "3",
    terrainSize = 2048f,
    gridSize = 8,
    id = id,
    legend = listOf(MapLayerLegendEntry(1, "Weizen", "#c8b262", "crop")),
    rows = listOf("", cell),
  )

  private val data: MapLayerData = plane("crops", "0101")
  private val layers = mapOf("crops" to data)

  private fun withRoute(current: () -> Map<String, MapLayerData>, block: suspend (io.ktor.client.HttpClient) -> Unit) =
    testApplication {
      application { routing { mapLayerRoute(current) } }
      block(client)
    }

  @Test
  fun servesTheRequestedVersionImmutably() = withRoute({ layers }) { client ->
    val response = client.get("/api/map-layer/crops?v=${data.contentVersion}")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsBytes().isNotEmpty())
    assertEquals("max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
  }

  @Test
  fun rejectsAStaleVersionInsteadOfServingCurrentBytesUnderIt() = withRoute({ layers }) { client ->
    // The regression: a sweep landing between the WebSocket broadcast and this request must not
    // cause the new raster to be cached for a year under the old version's URL.
    val response = client.get("/api/map-layer/crops?v=deadbeef")
    assertEquals(HttpStatusCode.Conflict, response.status)
    assertNotEquals("max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
  }

  @Test
  fun servesAnUnversionedRequestWithoutImmutableCaching() = withRoute({ layers }) { client ->
    val response = client.get("/api/map-layer/crops")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
  }

  @Test
  fun unknownLayerIsNotFound() = withRoute({ layers }) { client ->
    assertEquals(HttpStatusCode.NotFound, client.get("/api/map-layer/nope").status)
  }

  /**
   * A plane the app is offered but the mod hasn't swept yet (nobody had subscribed to it) simply has
   * no file, so there is nothing to render until the sweep its selection triggers lands.
   */
  @Test
  fun anOfferedButUnsweptLayerIsNotFound() = withRoute({ emptyMap() }) { client ->
    assertEquals(HttpStatusCode.NotFound, client.get("/api/map-layer/crops").status)
  }

  /** Each plane is versioned on its own, so another plane's version must not open this one's URL. */
  @Test
  fun aVersionFromAnotherPlaneIsRejected() {
    val growth = plane("growth", "0202")
    withRoute({ mapOf("crops" to data, "growth" to growth) }) { client ->
      val response = client.get("/api/map-layer/crops?v=${growth.contentVersion}")
      assertEquals(HttpStatusCode.Conflict, response.status)
    }
  }
}
