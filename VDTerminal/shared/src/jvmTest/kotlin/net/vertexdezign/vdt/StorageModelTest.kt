package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.StorageData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/storage` fixtures through the real server path
 * ([VdtParser.parseStorage]) and asserts the field mapping, the omission defaults (empty lists), and a
 * lossless JSON round-trip — the storage channel's half of the mod↔Kotlin contract. Production points
 * are covered by [ProductionModelTest].
 *
 * Four fixtures, all real game captures, and they do not all sit at the same channel version — a
 * capture is whatever the game wrote that day. `basic.json` is a singleplayer farm mid-silage-season,
 * recaptured at version 3, and `fermented_silo.json` the same farm once its covered bunker has
 * finished, still at version 2; `mp_modded.json` is a played-in **multiplayer client** on a modded
 * map, also recaptured at version 3; `empty.json` is the own-farm-with-nothing document. Between them
 * they carry every state the channel has ever written. The two shapes no capture can hold — a channel
 * version 1 file, and a stored row the mod could not read at all — are asserted inline.
 */
class StorageModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/storage/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/storage/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: StorageData) {
    val encoded = json.encodeToString(StorageData.serializer(), data)
    val decoded = json.decodeFromString(StorageData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  /**
   * The singleplayer capture, taken mid-silage-season: six storages including a manure heap, three
   * bunkers in two states, square and fermenting bales, and stored objects that hold no fill type at
   * all. Between this and [parsesTheMultiplayerCapture] every state the channel writes is covered by
   * real game data.
   *
   * Recaptured at version 3 from the same save, and **the version string is the only thing that
   * changed** — which is the evidence that the version 3 exclusion does not over-fire. This farm's
   * heap and slurry tank are standalone, no barn connected to either, so the rule must leave all six
   * storages exactly where they were; an over-eager `feedsHusbandry` would silently delete a building
   * the farm really owns, and that would show here as a missing row.
   */
  @Test
  fun parsesBasicStorage() {
    val data = VdtParser.parseStorage(example("basic.json"))

    assertEquals("3", data.version)
    assertEquals(6, data.storages.size)

    // A manure heap is a `fill` storage like any other, which is the point: in the engine it is not a
    // Storage at all, it hangs off a spec of its own, and it was invisible to this channel until
    // someone went looking for theirs. Here it is, next to the slurry tank that is its liquid twin.
    // Both are standalone — the farm fills them itself, no barn feeds either — which is why version 3
    // still reports them (it takes only a storage a husbandry feeds), and why the tank can also hold
    // digestate.
    val heap = data.storages.single { it.name == "Misthaufen" }
    assertEquals("fill", heap.kind)
    assertEquals("MANURE", heap.fills.single().type)
    assertEquals(3111, heap.fills.single().level)
    assertEquals(735000, heap.fills.single().capacity)
    val slurry = data.storages.single { it.name == "Güllebehälter" }
    assertEquals(listOf("DIGESTATE", "LIQUIDMANURE"), slurry.fills.map { it.type })
    assertEquals(14734, slurry.fills[0].level)

    // Three bunkers, and note they are three separate placeables that happen to share a name: the id
    // identifies one, never the name.
    assertEquals(3, data.bunkerSilos.size)
    assertEquals(
      1,
      data.bunkerSilos
        .map { it.name }
        .distinct()
        .size,
    )
    assertEquals(
      3,
      data.bunkerSilos
        .map { it.id }
        .distinct()
        .size,
    )

    // Being driven in and compacted.
    val compacting = data.bunkerSilos.single { it.compacted > 0 }
    assertEquals("FILL", compacting.state)
    assertEquals("CHAFF", compacting.type)
    assertEquals(12444, compacting.level)
    assertEquals(79, compacting.compacted)
    // Covered and fermenting: the game names the output type from here on, and the clock has started.
    // `fermented_silo.json` is the same bunker at the end of it.
    val closed = data.bunkerSilos.single { it.state == "CLOSED" }
    assertEquals("SILAGE", closed.type)
    assertEquals(3111, closed.level)
    assertEquals(5, closed.fermenting)
    assertEquals(0, closed.compacted)

    // Square bales exist, and fermenting ones are counted. Both grass groups are on their way to
    // silage, which is exactly why a stock overview must not price them as grass.
    val square = data.looseBales.single { it.shape == "SQUARE" }
    assertEquals("BALE", square.kind)
    assertEquals("GRASS_WINDROW", square.type)
    assertEquals(2, square.count)
    assertEquals(2, square.fermenting)
    assertEquals(1, data.looseBales.single { it.shape == "ROUND" }.fermenting)

    // The find this capture exists to pin: a crate and a vegetable pallet hold no fill type at all, so
    // the row names no resource — but it still says it is a PALLET, because what a thing IS and what
    // is IN it are two reads, and only the second one failed.
    val store = data.storages.single { it.name == "Lager" }
    val crate = store.objects.single { it.type == "" }
    assertEquals(2, crate.count)
    assertEquals("Palette (Kartoffel Small Box)", crate.title)
    assertEquals("PALLET", crate.kind)
    assertEquals(0, crate.level)
    // ...and its neighbours, which do hold something, are priced normally.
    assertEquals(2, store.objects.count { it.type == "POTATO" && it.kind == "PALLET" })

    // A LOOSE big bag, which is the half of the form split that stored objects cannot prove: this one
    // is resolved off the vehicle's `spec_bigBag`, not off an abstract object's `isBigBag`.
    val lime = data.loosePallets.single { it.kind == "BIGBAG" }
    assertEquals("LIME", lime.type)
    assertEquals(2, lime.count)
    assertEquals(4000, lime.level)
    assertEquals("", lime.shape)
    assertEquals(3, data.loosePallets.count { it.kind == "PALLET" })

    assertRoundTrips(data)
  }

  /**
   * The same farm as [parsesBasicStorage], a few days on: its covered bunker has finished fermenting.
   * `FERMENTED` is the last of the four bunker states, and the only one that shows the clock at the
   * end rather than part way — hence a capture of its own rather than a replacement.
   */
  @Test
  fun parsesTheFermentedBunkerCapture() {
    val data = VdtParser.parseStorage(example("fermented_silo.json"))

    val done = data.bunkerSilos.single { it.state == "FERMENTED" }
    // Same bunker as the CLOSED one in basic.json — same id, same liters, the clock run out.
    assertEquals("placeable86fbf84d48d404ffab0b367e6d6c3dc9", done.id)
    assertEquals("SILAGE", done.type)
    assertEquals(3111, done.level)
    assertEquals(100, done.fermenting)
    // Still covered, so no compaction figure: that belongs to FILL and is not resent here.
    assertEquals(0, done.compacted)
    // Its two neighbours are untouched, which is what makes the pair a clean before/after.
    assertEquals(2, data.bunkerSilos.count { it.state == "FILL" })

    // A honey pallet turned up in the meantime — 42 l of it, a part-filled pallet rather than a whole
    // one, which nothing else committed shows.
    val honey = data.loosePallets.single { it.type == "HONEY" }
    assertEquals("PALLET", honey.kind)
    assertEquals(42, honey.level)

    assertRoundTrips(data)
  }

  /**
   * A channel version 1 file — written before the bunker silos, the loose stock and the per-row fill
   * type existed — still has to parse under the extended model, every missing key falling back to its
   * default. That tolerance is what lets the mod ship fields ahead of the client, so it is asserted
   * rather than assumed. Inline, because no version 1 capture is committed any more.
   */
  @Test
  fun parsesAVersion1File() {
    val data =
      VdtParser.parseStorage(
        """
        {
          "version": "1",
          "storages": [
            { "id": "BaleBarn_1", "name": "Bale Barn", "kind": "object",
              "count": 32, "capacity": 250, "maxUnloadAmount": 25,
              "objects": [ { "index": 1, "title": "Round bale (Straw)", "count": 20 } ] }
          ]
        }
        """.trimIndent(),
      )

    assertEquals("1", data.version)
    val barn = data.storages.single()
    assertEquals(32, barn.count)
    assertEquals(20, barn.objects.single().count)
    // Everything version 2 added defaults away, on the row and on the document alike.
    assertEquals("", barn.objects.single().type)
    assertEquals(0, barn.objects.single().level)
    assertEquals("", barn.objects.single().kind)
    assertEquals("", barn.objects.single().shape)
    assertTrue(data.bunkerSilos.isEmpty())
    assertTrue(data.looseBales.isEmpty())
    assertTrue(data.loosePallets.isEmpty())

    assertRoundTrips(data)
  }

  /**
   * The played-in multiplayer capture: five storages, two bunkers, loose bales and a loose pallet, all
   * read from a joined **client**, which is what makes it the proof that none of the four blocks is
   * host-only. The map is modded, which is why `GRASS_FERMENTED` appears where a vanilla save would
   * say `SILAGE` — a reminder that fill type names are map data, not a fixed vocabulary.
   *
   * Recaptured at version 3, and every id in it changed — which is a fact about clients, not about
   * the recapture. A bought placeable's `uniqueId` lives in the host's savegame and is never sent:
   * `Placeable:readStream` carries filename, position and rotation only, so on the client
   * `PlaceableSystem:addPlaceable` mints one itself (`Utils.getUniqueId`, an MD5 over the object and
   * `getTime()`) and it differs every session. Only a *preplaced* placeable keeps a stable id there,
   * from the map's own attribute — the `preplaced_…` ids in the singleplayer captures. Nothing may
   * key on a client id across sessions.
   *
   * What v3 shows here it shows by **absence**, and it takes the husbandry fixture to see it: the same
   * farm's cow barn in `examples/json/husbandry/basic.json` holds 3834 l of `MANURE` and 233 l of
   * `LIQUIDMANURE` on its condition bars, and neither liter appears anywhere in this file. That is the
   * whole point of the pair — one heap of manure, reported once, by the pen that made it. The barn
   * keeps its own store rather than filling a heap placeable nearby, so nothing was ever in `storages`
   * to drop; the exclusion `feedsHusbandry` makes for a heap that *is* a placeable is not what this
   * capture exercises.
   */
  @Test
  fun parsesTheMultiplayerCapture() {
    val data = VdtParser.parseStorage(example("mp_modded.json"))

    assertEquals("3", data.version)
    assertEquals(5, data.storages.size)

    // Two bunkers, in two different states, and each carries only the percentage its own state
    // maintains — the other defaults to zero rather than arriving stale.
    assertEquals(2, data.bunkerSilos.size)
    val filling = data.bunkerSilos.single { it.state == "FILL" }
    // An empty bunker is still reported: it is a building the farm owns, at zero.
    assertEquals("CHAFF", filling.type)
    assertEquals(0, filling.level)
    assertEquals(0, filling.fermenting)
    val draining = data.bunkerSilos.single { it.state == "DRAIN" }
    // Opened, the game names the output type, and so does the export.
    assertEquals("SILAGE", draining.type)
    assertEquals(520486, draining.level)
    assertEquals(0, draining.compacted)
    assertEquals(0, draining.fermenting)

    // Loose stock: sorted by fill type, every bale round on this farm, and a pallet never carries the
    // bale-only `shape`.
    assertEquals(listOf("DRYGRASS_WINDROW", "STRAW"), data.looseBales.map { it.type })
    assertTrue(data.looseBales.all { it.shape == "ROUND" })
    assertEquals(15, data.looseBales[0].count)
    assertEquals(75000, data.looseBales[0].level)
    val milk = data.loosePallets.single()
    assertEquals("MILK_BOTTLED", milk.type)
    assertEquals("PALLET", milk.kind)
    assertEquals("", milk.shape)

    // The form fields, from real game data: a storage of pallets resolves each row to what it is, and
    // it does NOT do it off the title — the two SEEDS rows below hold the same fill type and split
    // PALLET from BIGBAG on `palletAttributes.isBigBag`, which is the game's own test.
    val garage = data.storages.single { it.name == "Offene Garage aus Holz" }
    assertEquals(10, garage.count)
    assertEquals(garage.count, garage.objects.sumOf { it.count })
    val seeds = garage.objects.filter { it.type == "SEEDS" }
    assertEquals(listOf("PALLET", "BIGBAG"), seeds.map { it.kind })
    assertTrue(garage.objects.all { it.shape == "" }, "a pallet carries no bale shape")

    // A stored group's `level` is ONE object's liters, so the group holds level * count, and the
    // groups add up to the storage's own total.
    val feedBarn = data.storages.single { it.name == "Futterhalle" }
    assertEquals(311, feedBarn.count)
    assertEquals(feedBarn.count, feedBarn.objects.sumOf { it.count })
    val straw = feedBarn.objects.single { it.type == "STRAW" }
    assertEquals(7500, straw.level)
    assertEquals(787500, straw.level * straw.count)

    // The trap for anything totalling this up: ONE fill type can span SEVERAL groups. The game splits
    // on more than type and level (the bale's xml file, its variation, whether it is wrapped), so the
    // hay in this barn arrives as three rows, two of them holding 5000 l bales either way. A consumer
    // has to sum by type rather than expect a row per type.
    val hay = feedBarn.objects.filter { it.type == "DRYGRASS_WINDROW" }
    assertEquals(3, hay.size)
    assertEquals(2, hay.count { it.level == 5000 })
    assertEquals(943245, hay.sumOf { it.level * it.count })

    // Every bale row — stored and loose alike — says BALE and refines it with a shape.
    assertTrue(feedBarn.objects.all { it.kind == "BALE" && it.shape == "ROUND" })
    assertTrue(data.looseBales.all { it.kind == "BALE" })

    assertRoundTrips(data)
  }

  /**
   * The one shape no capture can contain: a stored group the mod could not read at all — a modded
   * stand-in that answers neither of the two questions, so it names no resource *and* no form. It
   * still counts, because the total is what the unload dialog addresses.
   */
  @Test
  fun parsesAStoredRowNothingCouldRead() {
    val data =
      VdtParser.parseStorage(
        """
        {
          "version": "2",
          "storages": [
            { "id": "BaleBarn_1", "name": "Bale Barn", "kind": "object",
              "count": 32, "capacity": 250, "maxUnloadAmount": 25,
              "objects": [
                { "index": 1, "title": "Round bale (Straw)", "count": 20, "type": "STRAW",
                  "level": 4000, "kind": "BALE", "shape": "ROUND" },
                { "index": 2, "title": "Something", "count": 12 }
              ] }
          ]
        }
        """.trimIndent(),
      )

    val rows = data.storages.single().objects
    assertEquals("", rows[1].type)
    assertEquals("", rows[1].kind)
    assertEquals("", rows[1].shape)
    assertEquals(0, rows[1].level)
    assertEquals(12, rows[1].count)
    // Its readable neighbour, for contrast — and the reminder that a stored `level` is ONE object's
    // liters, so the group holds level * count.
    assertEquals(4000, rows[0].level)
    assertEquals(80000, rows[0].level * rows[0].count)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyStorageWithOmittedArrays() {
    // Own-farm-with-nothing / spectator: the mod writes just the version, so the Kotlin defaults must
    // fill the missing storages array.
    val data = VdtParser.parseStorage(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.storages.isEmpty())
    assertTrue(data.bunkerSilos.isEmpty())
    assertTrue(data.looseBales.isEmpty())
    assertTrue(data.loosePallets.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun storageRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseStorage(example("basic.json"))
    val message: ServerMessage = ServerMessage.Storage(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(
      encoded.contains("\"type\":\"storage\""),
      "expected the storage discriminator in $encoded",
    )
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.Storage))
  }

  /**
   * "File gone" has to be expressible on the wire: the server sends it when `storage.json` is absent
   * (export disabled) and the app clears its overview on it.
   */
  @Test
  fun storageCarriesTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.Storage(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.Storage).data)
  }
}
