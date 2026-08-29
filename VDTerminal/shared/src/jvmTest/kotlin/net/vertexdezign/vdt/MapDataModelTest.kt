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
 * Three captures, each carrying what the others cannot. `vanilla.json` is a whole real map in
 * singleplayer (77 fields, 73 POIs) and carries the mapping; `mp_modded.json` is a modded map seen
 * from a multiplayer client (85 fields, 63 POIs, four farms) and carries the per-farm palette and a
 * marker type the base game has no placeable for; `empty.json` is the loaded-but-nothing-to-show
 * file. The two absences none of them contains — a POI the game gave no name and a field whose
 * outline failed to resolve — are inline JSON below, because fixtures in this project are real game
 * captures and never hand-authored.
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

  /**
   * The farmland price, which arrived with map channel version 2.
   *
   * `mp_modded.json` is a version 2 capture and carries one on every field, so the real shape is
   * asserted below off that; the inline pair here is for the half a capture cannot show — a field
   * whose price the mod could not read, which is a key that is simply absent.
   */
  @Test
  fun carriesTheFarmlandPrice() {
    val data =
      VdtParser.parseMap(
        """
        {
          "version": "2",
          "terrainSize": 2048,
          "fields": [
            { "id": 12, "name": "12", "farmlandId": 12, "areaHa": 4.5, "labelX": 0.62, "labelZ": 0.31, "price": 112500 },
            { "id": 13, "name": "13", "farmlandId": 13, "areaHa": 2.0, "labelX": 0.4, "labelZ": 0.2 }
          ]
        }
        """.trimIndent(),
      )

    // Priced on an owned field as much as an unowned one — it is what the farmland costs, not an
    // offer, and the buy planner sorts by it and by price per hectare.
    assertEquals(112500, data.fields[0].price)
    // A field the mod couldn't read a price for reads as unknown, never as free: an unpriced field
    // sorted to the top of a "cheapest first" list would be a lie the app told itself.
    assertNull(data.fields[1].price)

    // A version 1 capture has no price anywhere, which is what an older mod looks like to a newer
    // app: unknown on every field, never free.
    val old = VdtParser.parseMap(example("vanilla.json"))
    assertEquals("1", old.version)
    assertTrue(old.fields.all { it.price == null })

    // And the real thing, off the version 2 capture: every one of its 85 farmlands is priced, none
    // of them at zero, on owned and unowned land alike.
    val captured = VdtParser.parseMap(example("mp_modded.json"))
    assertTrue(captured.fields.all { (it.price ?: 0) > 0 }, "every field in a v2 capture carries a real price")
    assertEquals(133608, captured.fields.single { it.id == 13 }.price, "priced although farm 1 already owns it")

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

    // A singleplayer capture, so Farm:getColor() hands every farm the same fixed green — here the
    // colour identifies nothing and the id has to. Multiplayer hands out the per-farm palette
    // instead, and [multiplayerHandsOutAPaletteThatCanStillRepeat] shows that even then two farms
    // can wear one hex: the app must never let a farm's colour be the only thing telling two farms
    // apart. Farm 14 is the guided-tour farm the exporter skips today; this file was captured
    // before it did.
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

  /**
   * The second committed capture: a **modded map, seen from a multiplayer client**, four farms in
   * one save. Everything it pins is something the singleplayer vanilla capture structurally cannot
   * show — a marker vocabulary wider than the base game's, and (below) a real per-farm palette.
   */
  @Test
  fun parsesTheModdedMultiplayerCapture() {
    val data = VdtParser.parseMap(example("mp_modded.json"))

    assertEquals("2", data.version)
    assertEquals(2048f, data.terrainSize)
    assertEquals(85, data.fields.size)
    assertEquals(45, data.pois.size)

    // The marker vocabulary is `PlaceableHotspot.TYPE`'s own key camelCased, so a modded map widens
    // it with nothing to maintain mod-side: this map puts an `exclamationMark` on a fire station,
    // a token the vanilla capture never produced — and carries none of that map's bees or trains.
    assertEquals("Moderne Deutsche Feuerwehr", data.pois.single { it.type == "exclamationMark" }.name)
    assertEquals(0, data.pois.count { it.type == "bee" || it.type == "train" })

    // Eleven markers called "Hof", on twelve farmyards — the twelfth calls itself "Bauernhaus". A POI
    // name is a label and never a key: the vanilla capture makes that point with two "Depot" markers,
    // this one makes it eleven times over, and the odd one out shows the label is not the type either.
    assertEquals(11, data.pois.count { it.name == "Hof" })
    assertEquals(12, data.pois.count { it.type == "farm" })

    // Same field invariants as the vanilla map, at a different scale: flat [x1,z1,...] outlines, the
    // smallest a four-corner farmland and the largest 33 points, all far under MAX_POLYGON_POINTS.
    assertTrue(data.fields.all { it.polygon.size >= 6 && it.polygon.size % 2 == 0 })
    assertEquals(4, data.fields.minOf { it.polygon.size / 2 })
    assertEquals(33, data.fields.maxOf { it.polygon.size / 2 })
    assertTrue(
      data.fields.all { field ->
        field.polygon.all { it in 0f..1f } && field.labelX in 0f..1f && field.labelZ in 0f..1f
      },
      "fields and their labels are normalized into the same [0,1] frame as the POIs",
    )
    assertTrue(data.fields.all { it.id == it.farmlandId && it.name == it.id.toString() })
    assertTrue(data.pois.all { it.posX in 0f..1f && it.posZ in 0f..1f })

    assertRoundTrips(data)
  }

  /**
   * What only a multiplayer capture can say about farm colour. In singleplayer `Farm:getColor()`
   * hands every farm the same green; in multiplayer it hands out `Farm.COLORS` by the index chosen
   * when the farm was created — so a real palette is a thing only a joined save produces.
   *
   * It also pins the guided-tour farm's **absence**. `FarmManager.GUIDED_TOUR_FARM_ID` is created
   * beside the spectator farm on every save — unnamed, owning nothing, and not a spectator, so the
   * original filter kept it and the previous capture carried it as farm 14. `collectFarms` skips it
   * now, and this capture is the first taken after that fix, which makes this the only place the fix
   * is asserted against real data rather than a stub.
   */
  @Test
  fun multiplayerHandsOutARealPalette() {
    val data = VdtParser.parseMap(example("mp_modded.json"))

    assertEquals(
      listOf(1, 2, 3),
      data.farms.map { it.id },
      "the game's own farm order, not sorted — and no farm 14, because the tour farm is filtered out",
    )
    assertEquals(listOf("Lindenhof Agrar GmbH", "Komune", "Rela Industries"), data.farms.map { it.name })
    assertTrue(data.farms.none { it.name.isBlank() }, "the unnamed tour farm is exactly what should not be here")
    assertEquals(listOf("#fff200", "#ff0000", "#2e00fa"), data.farms.map { it.color })

    // Two farms own land, which is the case singleplayer cannot produce: an owner id is not "mine",
    // so the app has to be able to render someone else's field as someone else's.
    val owned = data.fields.filter { it.ownerFarmId != null }
    assertEquals(listOf(13, 35, 43, 47, 48, 49, 68, 78), owned.map { it.id })
    assertEquals(setOf(1, 3), owned.mapNotNull { it.ownerFarmId }.toSet())
    assertEquals(78, data.fields.single { it.ownerFarmId == 3 }.id)

    // Markers split the same way — 17 of them on farm 1, one on farm 3's yard, and the rest public,
    // reporting no owner at all rather than farm 0.
    assertEquals(
      mapOf(1 to 17, 3 to 1),
      data.pois
        .mapNotNull { it.ownerFarmId }
        .groupingBy { it }
        .eachCount(),
    )
    assertEquals("Händlergebäude mit Werkstatt", data.pois.single { it.ownerFarmId == 3 }.name)
  }

  /**
   * Colour tints, it never identifies — the rule the committed capture can no longer demonstrate.
   *
   * `FarmManager:createFarm` takes the palette index as given, with nothing refusing one another farm
   * already wears, so two farms in one save can be the same hex and a legend that told them apart by
   * hue would show them as one. The previous `mp_modded.json` happened to contain that case (farm 3
   * and the guided-tour farm shared `#2e00fa`); filtering the tour farm out took the example with it,
   * and the rule outlived the example, so it is stated here rather than lost.
   *
   * The id is what joins. This is the same rule the app's design constraints state from the other
   * side, where hue may reinforce a distinction and never carry it.
   */
  @Test
  fun twoFarmsCanWearOneColour() {
    val data =
      VdtParser.parseMap(
        """
        {
          "version": "2",
          "terrainSize": 2048,
          "farms": [
            { "id": 1, "name": "Alpha", "color": "#2e00fa" },
            { "id": 4, "name": "Beta",  "color": "#2e00fa" }
          ]
        }
        """.trimIndent(),
      )

    assertEquals(2, data.farms.size)
    assertEquals(1, data.farms.mapNotNull { it.color }.distinct().size, "two farms, one hex — and both are real")
    assertEquals(listOf(1, 4), data.farms.map { it.id }, "the ids still tell them apart, which is why they are the key")
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
