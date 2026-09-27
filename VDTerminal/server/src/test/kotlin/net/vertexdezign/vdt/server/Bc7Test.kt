package net.vertexdezign.vdt.server

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * BC7 against the base-game brand logos in `examples/brand_logos/` — real game files, DX10 headers,
 * two mip levels. Each must decode to exactly what ImageMagick 7.1.1 decodes it to: the SHA-256 is of
 * `magick <file>[0] -depth 8 rgba:-`, so a hash rather than 512 KB of golden pixels per logo.
 *
 * Between them the five use every BC7 mode (brand_stoll alone uses all eight), so every branch of the
 * block decoder is exercised by a real encoder's output.
 */
class Bc7Test {
  private fun logo(name: String): ByteArray {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val file = File(dir, "examples/brand_logos/$name.dds")
      if (file.isFile) return file.readBytes()
      dir = dir.parentFile
    }
    fail("examples/brand_logos/$name.dds not found above ${File(".").absolutePath}")
  }

  private fun assertMatchesImageMagick(name: String, sha256: String) {
    val decoded = Dds.decode(logo(name))
    assertEquals(512, decoded.width, "$name width")
    assertEquals(256, decoded.height, "$name height")
    val hash = MessageDigest.getInstance("SHA-256").digest(decoded.rgba).joinToString("") { "%02x".format(it) }
    assertEquals(sha256, hash, "$name: decoded RGBA differs from ImageMagick's")
  }

  @Test fun deutzFahr() =
    assertMatchesImageMagick("brand_deutzFahr", "9fb9f509a343f49b4cd22f865fc690c818734fd92dc1680bb25ac6aadef494c9")

  @Test fun steyr() =
    assertMatchesImageMagick("brand_steyr", "0451b1f82de9cb9fd39fd0109ee4eb1447ea1cc87595c689b9944713a8447fa6")

  @Test fun stollUsesEveryMode() =
    assertMatchesImageMagick("brand_stoll", "e827e91a2a01134dec3164d65275af9b64d22fff93d974d98cef18ed7ca2687b")

  @Test fun valtra() =
    assertMatchesImageMagick("brand_valtra", "7b0f6529cd99305d49c4f876b4d3240051345db22112908c765a97c655a1d897")

  @Test fun ziegler() =
    assertMatchesImageMagick("brand_ziegler", "690a0b44a6ec68f57176f97415d5a773bc1a92900cc0fecaa9e89f528bfb75b2")
}
