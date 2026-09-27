package net.vertexdezign.vdt.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.vertexdezign.vdt.model.Brand
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/api/brand-image/{name}`: found by the brand's name, read from the `.dds` beside the `.png` the
 * brand XML declared, and trimmed to the logo. The DDS is synthetic — nothing of Giants' ships here.
 */
class BrandImageRouteTest {
  /**
   * An uncompressed 32-bit DDS of [width]×[height], transparent except a [solidW]×[solidH] opaque
   * block at ([x], [y]) — the shape of the game's logos, a mark floating in a wide empty canvas.
   */
  private fun dds(width: Int, height: Int, x: Int, y: Int, solidW: Int, solidH: Int): ByteArray {
    val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
    header.put("DDS ".toByteArray(Charsets.US_ASCII))
    header.putInt(12, height)
    header.putInt(16, width)
    // fourCC left zero: an uncompressed surface, described by the bit count alone.
    header.putInt(88, 32)
    val body = ByteArray(width * height * 4)
    for (py in y until y + solidH) {
      for (px in x until x + solidW) {
        val o = (py * width + px) * 4
        body[o] = 0xC0.toByte()
        body[o + 1] = 0x20
        body[o + 2] = 0x20
        body[o + 3] = 0xFF.toByte()
      }
    }
    return header.array() + body
  }

  /** A game folder holding only `data/store/brands/brand_test.dds`; returns it and the declared `.png`. */
  private fun gameWithLogo(): Pair<Path, String> {
    val dir = Files.createTempDirectory("vdt-brand")
    val shipped = dir.resolve("data/store/brands/brand_test.dds")
    shipped.parent.createDirectories()
    shipped.writeBytes(dds(64, 32, x = 10, y = 8, solidW = 40, solidH = 12))
    return dir to dir.resolve("data/store/brands/brand_test.png").toString()
  }

  private fun withRoute(
    gameDir: Path,
    images: BrandImages,
    current: () -> Brand? = {
      null
    },
    block: suspend (HttpClient) -> Unit,
  ) = testApplication {
    application { routing { brandImageRoute(images, { gameDir }, current) } }
    block(client)
  }

  @Test
  fun servesTheShippedDdsTrimmedToTheLogo() {
    val (gameDir, declared) = gameWithLogo()
    val images = BrandImages().apply { note(Brand("TEST", "Test", declared)) }
    withRoute(gameDir, images) { client ->
      val response = client.get("/api/brand-image/TEST")
      assertEquals(HttpStatusCode.OK, response.status)
      val png = ImageIO.read(ByteArrayInputStream(response.bodyAsBytes()))
      assertEquals(40, png.width, "the transparent canvas around the mark is cut away")
      assertEquals(12, png.height)
    }
  }

  /** The app asks after the brand it was just told about; the route must not depend on having seen it first. */
  @Test
  fun findsTheBrandOnTheCurrentTelemetry() {
    val (gameDir, declared) = gameWithLogo()
    withRoute(gameDir, BrandImages(), current = { Brand("TEST", "Test", declared) }) { client ->
      assertEquals(HttpStatusCode.OK, client.get("/api/brand-image/TEST").status)
    }
  }

  /** The player has since switched vehicles: the earlier brand is still remembered. */
  @Test
  fun remembersABrandNoLongerDriven() {
    val (gameDir, declared) = gameWithLogo()
    val images = BrandImages().apply { note(Brand("TEST", "Test", declared)) }
    withRoute(gameDir, images, current = { Brand("OTHER", "Other", "$gameDir/other.png") }) { client ->
      assertEquals(HttpStatusCode.OK, client.get("/api/brand-image/TEST").status)
    }
  }

  @Test
  fun aBrandNeverReportedIsNotFound() {
    val (gameDir, _) = gameWithLogo()
    withRoute(gameDir, BrandImages()) { client ->
      assertEquals(HttpStatusCode.NotFound, client.get("/api/brand-image/NOPE").status)
    }
  }

  @Test
  fun aMissingLogoSaysWhereItLooked() {
    val dir = Files.createTempDirectory("vdt-brand-empty")
    val declared = dir.resolve("data/store/brands/brand_gone.png").toString()
    val images = BrandImages().apply { note(Brand("GONE", "Gone", declared)) }
    withRoute(dir, images) { client ->
      val response = client.get("/api/brand-image/GONE")
      assertEquals(HttpStatusCode.NotFound, response.status)
      val body = response.bodyAsText()
      assertTrue("brand_gone.png" in body && "brand_gone.dds" in body, "both spellings are in the trail: $body")
    }
  }
}
