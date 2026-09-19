package net.vertexdezign.vdt.server

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Decode → PNG. DDS goes through [Dds]; PNG/JPG through ImageIO; anything else passes through as
 * `application/octet-stream`.
 *
 * Every step says what it did, at DEBUG. A map overview is the one asset here whose size is set by
 * whoever built the map rather than by us — a 4x map's 4096² overview re-encodes to tens of
 * megabytes — so the numbers in these lines (source bytes, pixels, PNG bytes, milliseconds) are what
 * separates "the server never found it" from "the tablet could not hold it".
 */
object ImagePipeline {
  private val log = LoggerFactory.getLogger(ImagePipeline::class.java)

  fun process(data: ByteArray, filename: String): Pair<ByteArray, String> {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val started = System.nanoTime()

    val image: BufferedImage =
      when (ext) {
        "dds" -> {
          toBufferedImage(Dds.decode(data))
        }

        "png", "jpg", "jpeg" -> {
          ImageIO.read(ByteArrayInputStream(data)) ?: error("failed to decode $ext")
        }

        else -> {
          log.debug("{} ({} bytes) has no image extension we decode; passing it through", filename, data.size)
          return data to "application/octet-stream"
        }
      }

    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    val png = out.toByteArray()
    log.debug(
      "{}: {} bytes {} -> {}x{} -> {} bytes png in {} ms",
      filename,
      data.size,
      ext,
      image.width,
      image.height,
      png.size,
      (System.nanoTime() - started) / 1_000_000,
    )
    return png to "image/png"
  }

  private fun toBufferedImage(decoded: DecodedImage): BufferedImage {
    val img = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_ARGB)
    val rgba = decoded.rgba
    val pixels = IntArray(decoded.width * decoded.height)
    for (i in pixels.indices) {
      val o = i * 4
      val r = rgba[o].toInt() and 0xFF
      val g = rgba[o + 1].toInt() and 0xFF
      val b = rgba[o + 2].toInt() and 0xFF
      val a = rgba[o + 3].toInt() and 0xFF
      pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    img.setRGB(0, 0, decoded.width, decoded.height, pixels, 0, decoded.width)
    return img
  }
}
