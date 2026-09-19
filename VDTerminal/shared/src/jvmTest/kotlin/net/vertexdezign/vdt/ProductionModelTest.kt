package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.ProductionData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/production` fixtures through the real server path
 * ([VdtParser.parseProduction]) and asserts the field mapping, the omission defaults (empty lists,
 * absent output `mode`), and a lossless JSON round-trip — the production channel's half of the
 * mod↔Kotlin contract. Standalone storages are covered by [StorageModelTest].
 */
class ProductionModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/production/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/production/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: ProductionData) {
    val encoded = json.encodeToString(ProductionData.serializer(), data)
    val decoded = json.decodeFromString(ProductionData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicProduction() {
    val data = VdtParser.parseProduction(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(1, data.productionPoints.size)

    val point = data.productionPoints[0]
    assertEquals("BunkerMittel_1", point.id)
    assertEquals("Bunker Mittel", point.name)
    // A real production point (not a factory) — the omitted key falls back to the default.
    assertFalse(point.isFactory)
    assertEquals(2, point.lines.size)
    assertEquals(4, point.storage.size)

    val mist = point.lines[0]
    assertEquals("mist", mist.id)
    assertEquals("Mist", mist.name)
    assertEquals("missingInputs", mist.status)
    assertTrue(mist.enabled)
    assertEquals(360, mist.cyclesPerMonth)
    assertEquals(60, mist.costsPerMonth)

    assertEquals(1, mist.inputs.size)
    val input = mist.inputs[0]
    assertEquals("MANURE", input.type)
    assertEquals("Mist", input.title)
    assertEquals(400, input.amount)
    // Inputs carry no distribution mode.
    assertNull(input.mode)

    assertEquals(1, mist.outputs.size)
    val output = mist.outputs[0]
    assertEquals("FERMENTERMANURE", output.type)
    assertEquals("autoDeliver", output.mode)
    assertFalse(output.sellDirectly)

    // A disabled, inactive line exercises the boolean/enum defaults from the other direction.
    val silage = point.lines[1]
    assertEquals("inactive", silage.status)
    assertFalse(silage.enabled)

    // A storage row joins to a line's input/output by type.
    val manureStore = point.storage.firstOrNull { it.type == "MANURE" }
    assertEquals(0, assertNotNull(manureStore).level)
    assertEquals(20000, manureStore.capacity)

    assertRoundTrips(data)
  }

  /**
   * The Pumps & Hoses biogas plant capture — a dedicated-server CLIENT, which is the half that could
   * only be reasoned about before it existed: the DLC syncs its merged-placeable map by a dirty flag
   * and rebuilds it in `onReadUpdateStream`, and this file is the proof that a client therefore sees
   * the same two entries, the same summed recipe and the same utilization readings the host does.
   *
   * The plant in it: two fermenters merged into ONE entry, three cogeneration units merged into
   * another, two bunkers, a digestate tank and a gas torch. Beside it in the same file is an ordinary
   * dairy, which is what pins that a base-game point still carries no construction at all.
   */
  @Test
  fun parsesTheBiogasPlantCapture() {
    val data = VdtParser.parseProduction(example("mp_modded.json"))

    assertEquals("2", data.version)
    assertEquals(3, data.productionPoints.size)

    // Seven machines, three entries: the dairy, and the plant's two merged points.
    val dairy = data.productionPoints.first { it.construction == null }
    assertEquals("Lindenhof Molkerei", dairy.name)

    val plant = data.constructions.single()
    assertEquals("BGA (1)", plant.name)
    assertEquals("bga", plant.kind)
    val members = data.productionPoints.filter { it.construction?.id == plant.id }
    assertEquals(listOf("FERMENTER", "POWERPLANT"), members.mapNotNull { it.construction?.role })
    // The plant's root is its fermenter — the only role the DLC lets be one — so the id it groups
    // under is that placeable's, and the fermenter entry's own id is the same string.
    assertEquals(plant.id, members[0].id)

    assertEquals(listOf("BUNKER", "FERMENTER", "POWERPLANT", "SILO", "TORCH"), plant.parts.map { it.role })
    val byRole = plant.parts.associateBy { it.role }
    assertEquals(2, byRole.getValue("BUNKER").count)
    assertEquals(2, byRole.getValue("FERMENTER").count)
    // Three cogeneration units behind one entry — the case that started all of this.
    assertEquals(3, byRole.getValue("POWERPLANT").count)
    assertEquals(91, byRole.getValue("POWERPLANT").utilization)
    assertEquals(52, byRole.getValue("FERMENTER").utilization)

    // What the bunkers hold, summed across both of them; the fermenters hold nothing of their own
    // (their liters are their production point's storage, reported there and only there).
    assertEquals(
      listOf("MANURE", "SILAGE", "SUGARBEET_CUT"),
      byRole.getValue("BUNKER").fills.map { it.type },
    )
    assertEquals(8559, byRole.getValue("BUNKER").fills.first { it.type == "MANURE" }.level)
    assertTrue(byRole.getValue("FERMENTER").fills.isEmpty())

    // The digestate tank: a real fill, and the flat 0 utilization the DLC never implemented for it.
    val tank = byRole.getValue("SILO")
    assertEquals(114688, tank.fills.first { it.type == "DIGESTATE" }.level)
    assertEquals(0, tank.utilization)

    // A working plant whose torch reads zero: it burns the surplus, and this one has none to burn.
    assertEquals(0, byRole.getValue("TORCH").utilization)

    // The plant's production has no name in the game — the DLC keys it by sandbox type — so the
    // mod's generic fallback is what lands here, and the app titles the card by the role instead.
    val fermenter = members[0].lines.single()
    assertEquals("FERMENTER", fermenter.id)
    assertEquals("Line 1", fermenter.name)
    assertEquals("running", fermenter.status)
    assertTrue(fermenter.enabled)

    // The merged recipe: three inputs on one fermenter entry, and the point's storage read through
    // its stations rather than off its own tank.
    assertEquals(listOf("SILAGE", "LIQUIDMANURE", "MANURE"), fermenter.inputs.map { it.type })
    assertEquals(28065, members[0].storage.first { it.type == "SILAGE" }.level)

    // The DLC's FOURTH output mode, which is not a key of the base enum at all — it registers its
    // value at load time, and naming it off that enum answered "keep", a wrong answer that looked
    // right. Both fermenter outputs are on it here, and nothing outside the plant is.
    assertEquals(listOf("autoDistribution", "autoDistribution"), fermenter.outputs.map { it.mode })
    assertEquals(OutputMode.AUTO_DISTRIBUTION, OutputMode.fromToken(fermenter.outputs[0].mode))
    assertTrue(dairy.lines.flatMap { it.outputs }.all { it.mode == "keep" })

    // The cogeneration unit sells its charge outright, so it is never buffered and carries no bar.
    val power = members[1].lines.single().outputs.single()
    assertEquals("ELECTRICCHARGE", power.type)
    assertTrue(power.sellDirectly)

    assertRoundTrips(data)
  }

  @Test
  fun productionWithoutADlcCarriesNoConstruction() {
    val data = VdtParser.parseProduction(example("basic.json"))
    assertNull(data.productionPoints[0].construction)
    assertTrue(data.constructions.isEmpty())
    // An output mode token the app does not know stays null rather than collapsing onto a neighbour —
    // the guard that matters now that a DLC can add a fourth one the app may not have heard of.
    assertNull(OutputMode.fromToken("somethingElse"))
  }

  @Test
  fun parsesEmptyProductionWithOmittedArrays() {
    // Own-farm-with-nothing / spectator: the mod writes just the version, so the Kotlin defaults must
    // fill the missing productionPoints array.
    val data = VdtParser.parseProduction(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.productionPoints.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun productionRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseProduction(example("basic.json"))
    val message: ServerMessage = ServerMessage.Production(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(
      encoded.contains("\"type\":\"production\""),
      "expected the production discriminator in $encoded",
    )
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Production))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `production.json` is
   * absent (export disabled) and the app clears its overview on it.
   */
  @Test
  fun productionCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Production(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Production).data)
  }
}
