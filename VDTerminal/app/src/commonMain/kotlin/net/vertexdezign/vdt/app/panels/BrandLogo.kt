package net.vertexdezign.vdt.app.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import net.vertexdezign.vdt.model.Brand
import org.jetbrains.skia.Image

/**
 * Decoded logos by brand name, held outside composition: the header recomposes on every frame and
 * the player switches vehicles far more often than brands come and go, so each logo is fetched once.
 */
private val logoCache = mutableMapOf<String, ImageBitmap>()

/**
 * Brands the server answered for with a verdict — no such file, a format it cannot decode. Neither
 * changes during a session, so they are not asked for again; the header shows their name instead.
 */
private val logoRefused = mutableSetOf<String>()

private val logoClient by lazy { HttpClient { install(HttpTimeout) } }

/**
 * The [brand]'s logo from `$brandImageUrl/{name}`, or null while it loads and whenever it can't be
 * had — the caller draws the brand's name then, so a missing logo costs nothing but the picture.
 *
 * A network error is not remembered: the next vehicle change of that brand tries again.
 */
@Composable
internal fun rememberBrandLogo(brandImageUrl: String, brand: Brand?): ImageBitmap? {
  val name = brand?.name?.takeIf { it.isNotBlank() && !brand.image.isNullOrBlank() && brandImageUrl.isNotBlank() }
  var logo by remember(name) { mutableStateOf(name?.let { logoCache[it] }) }
  LaunchedEffect(name) {
    if (name == null || logo != null || name in logoRefused) return@LaunchedEffect
    val outcome =
      runCatching {
        val response = logoClient.get("$brandImageUrl/${name.encodeURLPathPart()}")
        if (!response.status.isSuccess()) {
          logoRefused += name
          println("VDT: no logo for brand $name (${response.status}); showing its name")
          return@runCatching null
        }
        Image.makeFromEncoded(response.readRawBytes()).toComposeImageBitmap()
      }
    outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    outcome.getOrNull()?.let {
      logoCache[name] = it
      logo = it
    }
  }
  return logo
}
