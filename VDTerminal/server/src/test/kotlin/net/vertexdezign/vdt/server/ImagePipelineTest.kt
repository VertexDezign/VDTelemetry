package net.vertexdezign.vdt.server

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The PDA overview arrives with the map painted into the middle of a decorated border, and it is
 * served whole — border included, because the app draws it (see `MapOverview`). What this pins is
 * that nothing here takes a crop of its own: two crops lived in this file before, one keyed on the
 * PDA's declared world size and one on the terrain proportion, and either one silently changes what
 * the frame the app places the image in actually means.
 */
class ImagePipelineTest {
  /** A [size]² image: border color everywhere, [terrain] in the middle half of each axis. */
  private fun framed(size: Int, border: Int = 0xFF102030.toInt(), terrain: Int = 0xFF40A060.toInt()): ByteArray {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val from = size / 4
    val until = from + size / 2
    for (y in 0 until size) {
      for (x in 0 until size) {
        img.setRGB(x, y, if (x in from until until && y in from until until) terrain else border)
      }
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  private fun process(bytes: ByteArray, name: String = "overview.png"): BufferedImage {
    val (png, contentType) = ImagePipeline.process(bytes, name)
    assertEquals("image/png", contentType)
    return ImageIO.read(ByteArrayInputStream(png))
  }

  @Test
  fun `serves the overview whole, border and all`() {
    val result = process(framed(64))

    assertEquals(64, result.width)
    assertEquals(64, result.height)
    // The corner is border and the centre is terrain: both halves of the image survive, which is what
    // lets the app place the terrain on [0,1] and let the scenery run out to [-0.5, 1.5].
    assertEquals(0xFF102030.toInt(), result.getRGB(0, 0), "the decorated surround is part of the picture")
    assertEquals(0xFF40A060.toInt(), result.getRGB(32, 32), "and the terrain is still in the middle of it")
    assertEquals(0xFF102030.toInt(), result.getRGB(15, 15), "the border reaches to just inside a quarter")
    assertEquals(0xFF40A060.toInt(), result.getRGB(16, 16), "where the terrain starts, on the quarter")
  }

  @Test
  fun `trims only when asked, and then to what is drawn`() {
    val img = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
    for (y in 4 until 10) for (x in 6 until 26) img.setRGB(x, y, 0xFFC02020.toInt())
    // The faint DXT5 fringe a logo sits in: visible to the decoder, not part of the picture.
    img.setRGB(0, 0, 0x05FFFFFF)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)

    assertEquals(32, process(out.toByteArray()).width, "the overview path never crops")
    val (png, _) = ImagePipeline.process(out.toByteArray(), "logo.png", trim = true)
    val trimmed = ImageIO.read(ByteArrayInputStream(png))
    assertEquals(20, trimmed.width)
    assertEquals(6, trimmed.height)
  }

  @Test
  fun `does not resize whatever resolution the map author exported`() {
    assertEquals(4096, process(framed(4096)).width)
  }

  @Test
  fun `decodes a DXT1 overview at its own size`() {
    val dds = javaClass.getResourceAsStream("/dds/dxt1_8x8.dds")!!.readBytes()
    val decoded = ImagePipeline.process(dds, "full.dds").let { ImageIO.read(ByteArrayInputStream(it.first)) }

    assertEquals(8, decoded.width)
    assertEquals(8, decoded.height)
  }

  @Test
  fun `passes a non-image through untouched`() {
    val raw = byteArrayOf(1, 2, 3)
    val (bytes, contentType) = ImagePipeline.process(raw, "notes.txt")

    assertEquals("application/octet-stream", contentType)
    assertContentEquals(raw, bytes)
  }
}
