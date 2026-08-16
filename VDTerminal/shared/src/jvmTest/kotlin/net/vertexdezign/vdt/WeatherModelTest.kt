package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.WeatherForecastData
import net.vertexdezign.vdt.model.WeatherKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/weather` fixture through the real server path
 * ([VdtParser.parseWeather]) and asserts the field mapping, the omission defaults, the lenient
 * [WeatherKind] parse and a lossless round-trip — the weather channel's half of the mod↔Kotlin
 * contract.
 *
 * `vanilla.json` is a German-locale base-game capture taken mid-afternoon on a rainy August day, with
 * one day per period. It happens to pin down three things worth having in a fixture: the hourly strip
 * wrapping past midnight, the day captions losing their day number at `daysPerPeriod = 1`, and the
 * current wind angle being snapped to 45° while the forecast angles are not.
 */
class WeatherModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/weather/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/weather/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: WeatherForecastData) {
    val encoded = json.encodeToString(WeatherForecastData.serializer(), data)
    val decoded = json.decodeFromString(WeatherForecastData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesTheVanillaCapture() {
    val data = VdtParser.parseWeather(example("vanilla.json"))

    assertEquals("1", data.version)
    assertEquals("°C", data.temperatureUnit)

    val today = assertNotNull(data.today)
    // One day per period, so the game's own caption drops the day number entirely — which is exactly
    // why this label crosses the wire rather than being assembled from period + dayInPeriod here.
    assertEquals("August", today.label)
    assertEquals(6, today.period)
    assertEquals(1, today.dayInPeriod)

    val now = assertNotNull(data.current)
    assertEquals(WeatherKind.RAIN, now.kind)
    assertEquals(24, now.temperature)
    assertEquals(10.6f, now.windSpeed)
    assertEquals(5, now.windBeaufort)
    // The current angle is snapped to 45° by the engine; the forecast ones below are not.
    assertEquals(315, now.windDirection)

    assertRoundTrips(data)
  }

  @Test
  fun theHourlyStripRunsTwoHourlyAndWrapsPastMidnight() {
    val data = VdtParser.parseWeather(example("vanilla.json"))

    assertEquals(12, data.hourly.size)
    // Ordered, not sorted: it starts at the capture's own hour and runs a full day forward, so the
    // numbers descend across midnight. Anything that sorts this list breaks the strip.
    assertEquals(listOf(17, 19, 21, 23, 1, 3, 5, 7, 9, 11, 13, 15), data.hourly.map { it.hour })
    assertEquals(WeatherKind.RAIN, data.hourly.first().kind)
    assertEquals(23, data.hourly.first().temperature)
    assertEquals(WeatherKind.CLOUDY, data.hourly.last().kind)

    // Forecast angles come from the weather XML rather than from a y-rotation, so unlike the current
    // reading they are arbitrary degrees.
    assertEquals(listOf(322, 332, 332, 332, 24), data.hourly.take(5).map { it.windDirection })
    assertTrue(data.hourly.none { it.windDirection % 45 == 0 })
  }

  @Test
  fun theOutlookRunsSixDaysFromTomorrow() {
    val data = VdtParser.parseWeather(example("vanilla.json"))

    assertEquals(6, data.daily.size)
    // Today is period 6; the outlook starts at the next one and never repeats today.
    assertEquals(listOf(7, 8, 9, 10, 11, 12), data.daily.map { it.period })
    assertEquals(listOf("Sept", "Okt", "Nov", "Dez", "Jan", "Feb"), data.daily.map { it.label })

    val first = data.daily.first()
    assertEquals(WeatherKind.SUN, first.kind)
    assertEquals(13, first.high)
    assertEquals(8, first.low)
    // Winter arrives on schedule — a fixture that actually exercises a second weather glyph.
    assertEquals(WeatherKind.SNOW, data.daily.last().kind)
    assertEquals(WeatherKind.PARTIALLY_CLOUDY, data.daily[1].kind)
  }

  @Test
  fun parsesAnEmptyForecastWithOmittedArrays() {
    // Inline: the capture is a healthy forecast. The mod omits an empty list rather than writing [],
    // and a client that can read the current weather but has no forecast items would land here.
    val data = VdtParser.parseWeather("""{ "version": "1", "temperatureUnit": "°C" }""")

    assertNull(data.today)
    assertNull(data.current)
    assertTrue(data.hourly.isEmpty())
    assertTrue(data.daily.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun anUnrecognisedWeatherTypeDegradesRatherThanFailingTheParse() {
    // A weather type a future game version adds must cost one icon, not the whole channel.
    val data =
      VdtParser.parseWeather(
        """{ "version": "1", "current": { "type": "ACID_RAIN", "temperature": 12 } }""",
      )
    assertEquals(WeatherKind.UNKNOWN, assertNotNull(data.current).kind)
  }

  @Test
  fun weatherRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseWeather(example("vanilla.json"))
    val message: ServerMessage = ServerMessage.Weather(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"weather\""), "expected the weather discriminator")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Weather))
  }

  @Test
  fun weatherCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Weather(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Weather).data)
  }
}
