package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire coverage for [ServerMessage.ChannelStats] through the polymorphic [ServerMessage.serializer]:
 * the `channelStats` discriminator, a fully-populated stat, and a stat whose interval fields are all
 * null (a channel seen at most once, or not yet). The server encodes with `encodeDefaults = true`; the
 * app decodes with `ignoreUnknownKeys = true` — both must round-trip losslessly.
 */
class ServerMessageTest {
  private val json = Json { encodeDefaults = true }

  private val sample: ServerMessage =
    ServerMessage.ChannelStats(
      ChannelStatsData(
        serverNowEpochMs = 1_700_000_000_000,
        channels =
          listOf(
            ChannelStat(
              name = "vdTelemetry.json",
              writes = 42,
              lastWriteEpochMs = 1_699_999_999_000,
              lastIntervalMs = 100,
              meanIntervalMs = 101.5,
              minIntervalMs = 96,
              maxIntervalMs = 140,
            ),
            // never written: every interval field defaults to null
            ChannelStat(name = "husbandry.json", writes = 0),
          ),
      ),
    )

  @Test
  fun `channel stats round-trip through the ServerMessage serializer with its discriminator`() {
    val encoded = json.encodeToString(ServerMessage.serializer(), sample)
    assertTrue(encoded.contains("\"type\":\"channelStats\""), "keeps the channelStats discriminator")

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(sample, decoded, "round-trip should be lossless (incl. the null interval fields)")
  }

  @Test
  fun `the app's lenient decoder also reads channel stats`() {
    val lenient = Json { ignoreUnknownKeys = true }
    val encoded = json.encodeToString(ServerMessage.serializer(), sample)
    assertEquals(sample, lenient.decodeFromString(ServerMessage.serializer(), encoded))
  }
}
