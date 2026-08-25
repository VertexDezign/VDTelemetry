package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.MapData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/map` fixtures through the real server path
 * ([VdtParser.parseMap]) and asserts the field mapping, the omission defaults (empty arrays, null
 * owners, absent polygon), and a lossless JSON round-trip — the map channel's half of the
 * mod↔Kotlin contract.
 *
 * `vanilla.json` is a whole real map (77 fields, 73 POIs) and carries the mapping; `empty.json` is
 * the loaded-but-nothing-to-show file. The two absences neither of them contains — a POI the game
 * gave no name and a field whose outline failed to resolve — are inline JSON below, because
 * fixtures in this project are real game captures and never hand-authored.
 */
class MapDataModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/map/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/map/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: MapData) {
    val encoded = json.encodeToString(MapData.serializer(), data)
    val decoded = json.decodeFromString(MapData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  /**
   * The two omissions the capture cannot show, because everything on that map has a name and every
   * field resolved its outline: `collectPois` writes no `name` when the hotspot gives none, and
   * `collectPolygon` returns nothing when the polygon nodes fail or thin below three points — the
   * field then degrades to its label rather than vanishing. Both must decode to a usable default,
   * never to a crash.
   */
  @Test
  fun fillsInWhatTheModOmits() {
    val data =
      VdtParser.parseMap(
        """
        {
          "version": "1",
          "terrainSize": 2048,
          "pois": [{ "type": "shop", "posX": 0.208, "posZ": 0.741 }],
          "farms": [{ "id": 2, "color": "#638aff" }],
          "fields": [
            { "id": 12, "name": "12", "farmlandId": 12, "areaHa": 4.5, "labelX": 0.62, "labelZ": 0.31 }
          ]
        }
        """.trimIndent(),
      )

    val shop = data.pois.single()
    assertEquals("shop", shop.type)
    assertEquals("", shop.name, "a nameless marker still renders — as its icon, with no label")
    assertNull(shop.ownerFarmId, "no owner at all, rather than farm 0")

    assertEquals("", data.farms.single().name)
    assertEquals("#638aff", data.farms.single().color)

    // A field with no outline keeps everything else: the app can still put its number on the map.
    val unowned = data.fields.single()
    assertEquals(12, unowned.id)
    assertEquals(4.5f, unowned.areaHa)
    assertEquals(0.62f, unowned.labelX)
    assertTrue(unowned.polygon.isEmpty())
    assertNull(unowned.ownerFarmId)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyMapWithOmittedArrays() {
    // The mod omits empty `pois`/`fields` arrays (the Json encoder can't distinguish [] from {}),
    // so the Kotlin defaults must fill in. "Loaded but nothing to show" — e.g. a map without fields.
    val data = VdtParser.parseMap(example("empty.json"))

    assertEquals("1", data.version)
    assertEquals(2048f, data.terrainSize)
    assertTrue(data.pois.isEmpty())
    assertTrue(data.fields.isEmpty())
    assertTrue(data.farms.isEmpty())
    assertRoundTrips(data)
  }

  /**
   * The committed capture of a whole vanilla map. The counts are that file's and are the point: a
   * real map is 77 fields and 73 markers, which is the scale the overlay has to draw at, and three
   * of those fields are owned — the ratio the app's "my fields" filter actually faces.
   */
  @Test
  fun parsesTheVanillaCapture() {
    val data = VdtParser.parseMap(example("vanilla.json"))

    assertEquals("1", data.version)
    assertEquals(2048f, data.terrainSize, "the frame the normalized coordinates convert back into")
    assertEquals(77, data.fields.size)
    assertEquals(73, data.pois.size)

    // A singleplayer capture, so Farm:getColor() hands every farm the same fixed green (multiplayer
    // hands out the per-farm palette instead) — here the colour identifies nothing and the id has
    // to. Which is also why the app must never let a farm's colour be the only thing telling two
    // farms apart.
    assertEquals(listOf(1, 14), data.farms.map { it.id })
    assertEquals("Mein Hof", data.farms[0].name)
    assertEquals("", data.farms[1].name, "a farm with no name of its own falls back to the default")
    assertEquals(listOf("#82ab0c", "#82ab0c"), data.farms.map { it.color })

    // Ownership is the exception, not the rule: three owned fields, and every other one reports a
    // null owner rather than farm 0.
    val owned = data.fields.filter { it.ownerFarmId != null }
    assertEquals(listOf(84, 4, 85), owned.map { it.id })
    assertTrue(owned.all { it.ownerFarmId == 1 })

    // Every field arrived with an outline, flat [x1,z1,...] so the count is even, and thinned to
    // MIN_POINT_SPACING_M / MAX_POLYGON_POINTS — the whole map fits in 26 points per field, far
    // under the 256 cap the exporter allows for.
    assertTrue(data.fields.all { it.polygon.size >= 6 && it.polygon.size % 2 == 0 })
    assertEquals(26, data.fields.maxOf { it.polygon.size / 2 })
    assertTrue(
      data.fields.all { field ->
        field.polygon.all { it in 0f..1f } && field.labelX in 0f..1f && field.labelZ in 0f..1f
      },
      "fields and their labels are normalized into the same [0,1] frame as the POIs",
    )

    // In FS25 a field IS its farmland, and the field number is the id printed as a name.
    assertTrue(data.fields.all { it.id == it.farmlandId && it.name == it.id.toString() })

    val first = data.fields[0]
    assertEquals(19, first.id)
    assertEquals(2.49f, first.areaHa)
    assertEquals(0.04988f, first.labelX)
    assertEquals(0.0717f, first.labelZ)
    assertEquals(16, first.polygon.size)

    // 15 of the 73 markers belong to the player's farm; the public ones report no owner at all.
    assertEquals(15, data.pois.count { it.ownerFarmId != null })
    assertTrue(data.pois.filter { it.ownerFarmId != null }.all { it.ownerFarmId == 1 })
    assertEquals(20, data.pois.count { it.type == "bee" }, "beehives are the most numerous marker on this map")

    // A POI name is not a key: the depot contributes two markers, its pallet counter and its shop,
    // at different positions on one placeable. Anything joining a marker to another channel needs
    // an id, not a name (and the prices channel keeps its own coordinates for the same reason).
    val depot = data.pois.filter { it.name == "Depot" }
    assertEquals(listOf("unloadingPallet", "shop"), depot.map { it.type })
    assertEquals(2, depot.map { it.posX }.distinct().size)

    assertRoundTrips(data)
  }

  @Test
  fun mapRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseMap(example("vanilla.json"))
    val message: ServerMessage = ServerMessage.MapUpdate(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"map\""), "expected the map discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.MapUpdate))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `map.json` is absent
   * (export disabled) and the app clears its overlays on it. If `data` were non-nullable the null
   * could never be broadcast and stale overlays would stick forever.
   */
  @Test
  fun mapCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.MapUpdate(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.MapUpdate).data)
  }
}
