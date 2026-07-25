package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.DriveDirection
import net.vertexdezign.vdt.model.FillDisplayType
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.PipeState
import net.vertexdezign.vdt.model.VdtData
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

    assertEquals("4", data.version)
    assertEquals("01.08.2024", data.environment?.date)

    // weather
    assertEquals(
      28,
      data.environment
        ?.weather
        ?.temperature
        ?.current,
    )
    assertEquals(
      "°C",
      data.environment
        ?.weather
        ?.temperature
        ?.unit,
    )

    // pda / map data
    val pda = assertNotNull(data.environment?.pda)
    assertEquals("S:/common/Farming Simulator 25/data/maps/mapUS/textures/ui/overview.dds", pda.filename)
    assertEquals(2048, pda.width)
    assertEquals(2048, pda.height)
    assertEquals(0.4532542f, pda.player?.posX)
    assertEquals(0.42799774f, pda.player?.posZ)
    assertEquals(91, pda.player?.heading)
    assertEquals("°", pda.player?.headingUnit)
    assertEquals(1, pda.player?.farmId)

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
    // `numStates` is absent from this fixture (it was captured before the mod exported it) and so
    // falls back to 0; current == target == 1 is implied by the captured RETRACTED label.
    assertEquals(PipeState.RETRACTED, v.pipe?.state)
    assertEquals(1, v.pipe?.current)
    assertEquals(1, v.pipe?.target)

    // combine motor has fuel + def but no air
    assertEquals(
      947f,
      v.motor
        ?.fillUnits
        ?.fuel
        ?.value,
    )
    assertEquals(
      110f,
      v.motor
        ?.fillUnits
        ?.def
        ?.value,
    )
    assertEquals(null, v.motor?.fillUnits?.air)

    // vehicle-level fillUnits use the repeated `fillUnit` form
    val fillUnits = assertNotNull(v.fillUnits)
    assertEquals(1, fillUnits.fillUnit.size)
    assertEquals(13500, fillUnits.fillUnit[0].capacity)
    assertEquals(5054f, fillUnits.fillUnit[0].value)
    assertEquals(37, fillUnits.fillUnit[0].fillLevelPercentage)
    // display hints are absent at their engine defaults
    assertEquals(0, fillUnits.fillUnit[0].precision)
    assertEquals(FillDisplayType.BAR, fillUnits.fillUnit[0].display)

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
    assertEquals(
      18500f,
      back.fillUnits
        ?.fillUnit
        ?.singleOrNull()
        ?.value,
    )

    // the nested trailer is reachable and carries its own fill unit
    val nested = assertNotNull(back.implement.singleOrNull())
    assertEquals("Rudolph DK 280 RP", nested.name)
    assertEquals(
      "WHEAT",
      nested.fillUnits
        ?.fillUnit
        ?.singleOrNull()
        ?.type,
    )
    assertEquals(
      18500f,
      nested.fillUnits
        ?.fillUnit
        ?.singleOrNull()
        ?.value,
    )

    // the whole BACK chain exposes both fill units — this recursive walk mirrors what the
    // Implements panel's collectFillUnits does (and what its "merged" toggle then sums).
    // `sumOf` has no Float overload, hence map/sum — same as mergeFillUnits.
    fun totalFill(imp: Implement): Float =
      (
        imp.fillUnits
          ?.fillUnit
          ?.map { it.value }
          ?.sum() ?: 0f
      ) +
        imp.implement.map { totalFill(it) }.sum()
    assertEquals(37000f, totalFill(back))

    assertJsonRoundTrips(data)
  }

  @Test
  fun coercesNullCapacityToDefault() {
    // A pass-through fill unit (a forage/carrot harvester's output) has no capacity in its XML, so
    // the engine reports +inf and the mod's JSON encoder emits `capacity: null`. A strict parse blew
    // up on that and froze the whole feed; coerceInputValues must fall it back to the default (0).
    val text =
      """{"version":"1","vehicle":{"fillUnits":{"fillUnit":""" +
        """[{"value":0,"fillLevelPercentage":0,"capacity":null,"unit":""}]}}}"""
    val data = VdtParser.parseJson(text)
    val unit =
      assertNotNull(
        data.vehicle
          ?.fillUnits
          ?.fillUnit
          ?.singleOrNull(),
      )
    assertEquals(0, unit.capacity)
  }

  @Test
  fun decodesFractionalConsumableFillUnit() {
    // Bale net/twine/wrap are the game's `Consumable` spec on a fill unit measured in SLOTS: capacity
    // is the slot count and the level is "spare rolls + how much of the mounted one is left", so it
    // is fractional. Inline rather than a fixture: no captured baler JSON exists yet, and inventing
    // one would put made-up fill-type names in examples/json.
    val text =
      """{"version":"2","vehicle":{"fillUnits":{"fillUnit":[""" +
        """{"value":2.4,"capacity":4,"fillLevelPercentage":60,"title":"Netz","unit":"","display":"STEP"}]}}}"""
    val unit =
      assertNotNull(
        VdtParser
          .parseJson(text)
          .vehicle
          ?.fillUnits
          ?.fillUnit
          ?.singleOrNull(),
      )
    assertEquals(2.4f, unit.value)
    assertEquals(4, unit.capacity)
    assertEquals(FillDisplayType.STEP, unit.display)
    assertEquals(0, unit.precision)
  }

  @Test
  fun decodesSchemaAndSelection() {
    // The rig diagram: each object names a silhouette and lists where children hang off it, and the
    // child points back with jointDescIndex. Inline rather than a fixture — the committed captures
    // predate the mod exporting any of this, and the offsets are per-vehicle XML data that would be
    // invented if hand-written into examples/json.
    val text =
      """{"version":"4","vehicle":{"schema":{"name":"HARVESTER","offsetX":0.25,"offsetY":0.5,""" +
        """"attacherJoint":[{"x":0.1,"y":0.2,"rotation":0,"invertX":false},""" +
        """{"x":0.9,"y":0.3,"rotation":1.5,"invertX":true,"liftedOffsetY":5}]},""" +
        """"selection":{"selected":false},""" +
        """"implement":[{"position":"FRONT","jointDescIndex":2,"schema":{"name":"COMBINE_HEADER"},""" +
        """"selection":{"selected":true,"controlGroup":{"current":2,"name":"Greifer",""" +
        """"names":["Kran","Greifer"]}}}]}}"""
    val v = assertNotNull(VdtParser.parseJson(text).vehicle)

    val schema = assertNotNull(v.schema)
    assertEquals("HARVESTER", schema.name)
    assertEquals(0.25f, schema.offsetX)
    assertEquals(2, schema.attacherJoint.size)
    assertTrue(schema.attacherJoint[1].invertX)
    assertEquals(5f, schema.attacherJoint[1].liftedOffsetY)
    // absent border fields stay null rather than defaulting to a misleading 0
    assertEquals(null, schema.borderLeft)

    val imp = assertNotNull(v.implement.singleOrNull())
    // the child indexes into the *parent's* joint list
    assertEquals(2, imp.jointDescIndex)
    assertEquals("COMBINE_HEADER", imp.schema?.name)
    assertTrue(imp.schema?.attacherJoint?.isEmpty() == true)

    // exactly one node in the rig is selected, and it carries the control group
    assertEquals(false, v.selection?.selected)
    assertEquals(true, imp.selection?.selected)
    val group = assertNotNull(imp.selection?.controlGroup)
    assertEquals(2, group.current)
    assertEquals("Greifer", group.name)
    assertEquals(listOf("Kran", "Greifer"), group.names)
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
