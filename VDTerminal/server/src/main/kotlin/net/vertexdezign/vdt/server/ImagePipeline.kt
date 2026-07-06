package net.vertexdezign.vdt.server

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Decode → (center-)crop → PNG, mirroring the Go server's `handleImage`.
 *
 * DDS is decoded via [Dds]; PNG/JPG go through ImageIO. Non-image extensions pass through as
 * `application/octet-stream`. Cropping matches `cropImage`: only crop when the target is smaller
 * than the source, centered.
 */
object ImagePipeline {
  fun process(
    data: ByteArray,
    filename: String,
    pdaWidth: Int,
    pdaHeight: Int,
  ): Pair<ByteArray, String> {
    val ext = filename.substringAfterLast('.', "").lowercase()

    val image: BufferedImage =
      when (ext) {
        "dds" -> {
          toBufferedImage(Dds.decode(data))
        }

        "png", "jpg", "jpeg" -> {
          ImageIO.read(ByteArrayInputStream(data)) ?: error("failed to decode $ext")
        }

        else -> {
          return data to "application/octet-stream"
        }
      }

    val result = if (pdaWidth > 0 && pdaHeight > 0) crop(image, pdaWidth, pdaHeight) else image
    val out = ByteArrayOutputStream()
    ImageIO.write(result, "png", out)
    return out.toByteArray() to "image/png"
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

  /** Center-crop to target size, but only if smaller than the source (port of `cropImage`). */
  private fun crop(
    img: BufferedImage,
    targetWidth: Int,
    targetHeight: Int,
  ): BufferedImage {
    val width = img.width
    val height = img.height
    if (targetWidth >= width && targetHeight >= height) return img

    val extractWidth = minOf(targetWidth, width)
    val extractHeight = minOf(targetHeight, height)
    val left = (width - extractWidth) / 2
    val top = (height - extractHeight) / 2

    val dst = BufferedImage(extractWidth, extractHeight, BufferedImage.TYPE_INT_ARGB)
    val g = dst.createGraphics()
    g.drawImage(
      img,
      0,
      0,
      extractWidth,
      extractHeight,
      left,
      top,
      left + extractWidth,
      top + extractHeight,
      null,
    )
    g.dispose()
    return dst
  }
}
