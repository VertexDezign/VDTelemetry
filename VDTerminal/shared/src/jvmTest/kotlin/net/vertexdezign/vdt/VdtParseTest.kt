package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VdtParseTest {

    private val json = Json { encodeDefaults = true }

    private fun example(name: String): String {
        // Walk up from the module dir to find the repo-root `examples/xml` fixtures.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "examples/xml/$name")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        error("Could not locate examples/xml/$name from ${File(".").absolutePath}")
    }

    private fun parse(name: String): VdtData = VdtParser.parse(example(name))

    private fun assertJsonRoundTrips(data: VdtData) {
        val encoded = json.encodeToString(VdtData.serializer(), data)
        val decoded = json.decodeFromString(VdtData.serializer(), encoded)
        assertEquals(data, decoded, "JSON round-trip should be lossless")
    }

    @Test
    fun parsesTractorWithCultivator() {
        val data = parse("tractor_with_cultivator.xml")

        assertEquals("1", data.version)
        assertEquals("01.08.2024", data.environment?.date)

        // weather
        assertEquals(19, data.environment?.weather?.temperature?.current)
        assertEquals("°C", data.environment?.weather?.temperature?.unit)

        // pda / map data
        val pda = assertNotNull(data.environment?.pda)
        assertEquals("data/maps/mapUS/pda_map.dds", pda.filename)
        assertEquals(2048, pda.width)
        assertEquals(2048, pda.height)
        assertEquals(0.53f, pda.player?.posX)
        assertEquals(0.41f, pda.player?.posZ)

        val v = assertNotNull(data.vehicle)
        assertEquals("STEYR Absolut 6200 CVT", v.name)
        assertEquals("tractor", v.type)
        assertEquals(0f, v.speed?.value)
        assertEquals("km/h", v.speed?.unit)
        assertEquals(DriveDirection.STOPPED, v.speed?.direction)

        val motor = assertNotNull(v.motor)
        assertEquals(MotorState.ON, motor.state)
        assertEquals(850, motor.rpm?.value)
        assertEquals(2200, motor.rpm?.max)
        assertEquals(7.73, motor.load?.value)
        assertEquals("D", motor.gear?.value)

        // Motor fill units are the named fuel/def/air form.
        assertEquals(48, motor.fillUnits?.def?.value)
        assertEquals(2305, motor.fillUnits?.air?.value)
        assertEquals(389, motor.fillUnits?.fuel?.value)
        assertEquals("diesel", motor.fillUnits?.fuel?.type)

        assertEquals(53, v.cruiseControl?.targetSpeed)
        assertEquals(false, v.cruiseControl?.active)

        assertEquals(3, v.implement.size)
        assertEquals("FRONT", v.implement[0].position)
        assertEquals("CLAAS W 600", v.implement[0].name)
        assertEquals("cultivator", v.implement[2].type)

        assertNotNull(v.combined)
        assertNotNull(v.combined?.implement?.front)

        assertJsonRoundTrips(data)
    }

    @Test
    fun parsesCombine() {
        val data = parse("combine.xml")
        val v = assertNotNull(data.vehicle)

        assertEquals("combineDrivable", v.type)
        assertEquals(10f, v.speed?.value)
        assertEquals(FoldableState.FOLDED, v.foldable.toFoldableState())
        assertEquals(PipeState.RETRACTED, v.pipe.toPipeState())

        // combine motor has fuel + def but no air
        assertEquals(1155, v.motor?.fillUnits?.fuel?.value)
        assertEquals(74, v.motor?.fillUnits?.def?.value)
        assertEquals(null, v.motor?.fillUnits?.air)

        // vehicle-level fillUnits use the repeated <fillUnit> form
        val fillUnits = assertNotNull(v.fillUnits)
        assertEquals(1, fillUnits.fillUnit.size)
        assertEquals(14100, fillUnits.fillUnit[0].capacity)

        assertEquals(1, v.implement.size)
        assertEquals("cutter", v.implement[0].type)

        assertNotNull(v.combined?.fillUnits)
        assertNotNull(v.combined?.implement?.front)

        assertJsonRoundTrips(data)
    }

    @Test
    fun parsesMultipleImplements() {
        val data = parse("mutliple_implements.xml")
        val v = assertNotNull(data.vehicle)

        assertEquals("H", v.motor?.gear?.group)
        assertEquals("5", v.motor?.gear?.value)

        // One top-level implement, which itself nests a child implement.
        assertEquals(1, v.implement.size)
        val top = v.implement[0]
        assertEquals("seedingRoller", top.type)
        assertEquals("CLOSED", top.cover)
        assertEquals(1, top.implement.size)
        assertEquals("roller", top.implement[0].type)

        assertNotNull(v.combined?.implement?.back)

        assertJsonRoundTrips(data)
    }

    @Test
    fun serverMessageUsesTypeDiscriminator() {
        val msg: ServerMessage = ServerMessage.Telemetry(parse("combine.xml"))
        val encoded = json.encodeToString(ServerMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"telemetry\""), "expected discriminator, got: $encoded")

        val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }
}
