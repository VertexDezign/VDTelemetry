package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json` fixtures through the real server path ([VdtParser.parseJson])
 * and asserts the model's field mapping, a lossless JSON round-trip, and the [ServerMessage] wire
 * discriminator.
 */
class VdtModelTest {

    private val json = Json { encodeDefaults = true }

    private fun example(name: String): String {
        // Walk up from the module dir to find the repo-root `examples/json` fixtures.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "examples/json/$name")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        error("Could not locate examples/json/$name from ${File(".").absolutePath}")
    }

    private fun model(name: String): VdtData = VdtParser.parseJson(example(name))

    private fun assertJsonRoundTrips(data: VdtData) {
        val encoded = json.encodeToString(VdtData.serializer(), data)
        val decoded = json.decodeFromString(VdtData.serializer(), encoded)
        assertEquals(data, decoded, "JSON round-trip should be lossless")
    }

    @Test
    fun parsesTractorWithCultivator() {
        val data = model("tractor_with_cultivator.json")

        assertEquals("1", data.version)
        assertEquals("01.08.2024", data.environment?.date)

        // weather
        assertEquals(28, data.environment?.weather?.temperature?.current)
        assertEquals("°C", data.environment?.weather?.temperature?.unit)

        // pda / map data
        val pda = assertNotNull(data.environment?.pda)
        assertEquals("S:/common/Farming Simulator 25/data/maps/mapUS/textures/ui/overview.dds", pda.filename)
        assertEquals(2048, pda.width)
        assertEquals(2048, pda.height)
        assertEquals(0.4532542f, pda.player?.posX)
        assertEquals(0.42799774f, pda.player?.posZ)

        val v = assertNotNull(data.vehicle)
        assertEquals("Valtra T195 Active", v.name)
        assertEquals("tractor", v.type)
        assertEquals(0f, v.speed?.value)
        assertEquals("km/h", v.speed?.unit)
        assertEquals(DriveDirection.STOPPED, v.speed?.direction)

        assertJsonRoundTrips(data)
    }

    @Test
    fun parsesCombine() {
        val data = model("combine.json")
        val v = assertNotNull(data.vehicle)

        assertEquals("combineDrivable", v.type)
        assertEquals(3.92f, v.speed?.value)
        assertEquals(FoldableState.EXTENDED, v.foldable)
        assertEquals(PipeState.RETRACTED, v.pipe)

        // combine motor has fuel + def but no air
        assertEquals(947, v.motor?.fillUnits?.fuel?.value)
        assertEquals(110, v.motor?.fillUnits?.def?.value)
        assertEquals(null, v.motor?.fillUnits?.air)

        // vehicle-level fillUnits use the repeated `fillUnit` form
        val fillUnits = assertNotNull(v.fillUnits)
        assertEquals(1, fillUnits.fillUnit.size)
        assertEquals(13500, fillUnits.fillUnit[0].capacity)
        assertEquals(5054, fillUnits.fillUnit[0].value)
        assertEquals(37, fillUnits.fillUnit[0].fillLevelPercentage)

        assertEquals(1, v.implement.size)
        assertEquals("cutter", v.implement[0].type)

        assertJsonRoundTrips(data)
    }

    @Test
    fun parsesMultipleImplements() {
        val data = model("mutliple_implements.json")
        val v = assertNotNull(data.vehicle)

        assertEquals("N", v.motor?.gear?.group)
        assertEquals("R", v.motor?.gear?.value)

        assertEquals(2, v.implement.size)

        assertJsonRoundTrips(data)
    }

    @Test
    fun parsesNestedTrailersAndAggregatesFillUnits() {
        val data = model("nested_trailers.json")
        val v = assertNotNull(data.vehicle)

        // BACK is a trailer that itself pulls a nested trailer; both carry wheat.
        val back = assertNotNull(v.implement.firstOrNull { it.position == "BACK" })
        assertEquals("trailer", back.type)
        assertEquals(18500, back.fillUnits?.fillUnit?.singleOrNull()?.value)

        // the nested trailer is reachable and carries its own fill unit
        val nested = assertNotNull(back.implement.singleOrNull())
        assertEquals("Rudolph DK 280 RP", nested.name)
        assertEquals("WHEAT", nested.fillUnits?.fillUnit?.singleOrNull()?.type)
        assertEquals(18500, nested.fillUnits?.fillUnit?.singleOrNull()?.value)

        // the whole BACK chain exposes both fill units — this recursive walk mirrors what the
        // Implements panel's collectFillUnits does (and what its "merged" toggle then sums).
        fun totalFill(imp: Implement): Int =
            (imp.fillUnits?.fillUnit?.sumOf { it.value } ?: 0) + imp.implement.sumOf { totalFill(it) }
        assertEquals(37000, totalFill(back))

        assertJsonRoundTrips(data)
    }

    @Test
    fun serverMessageUsesTypeDiscriminator() {
        val msg: ServerMessage = ServerMessage.Telemetry(model("combine.json"))
        val encoded = json.encodeToString(ServerMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"telemetry\""), "expected discriminator, got: $encoded")

        val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }
}
