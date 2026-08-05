package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.MissionFinishState
import net.vertexdezign.vdt.model.MissionStatus
import net.vertexdezign.vdt.model.MissionsData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/missions` capture through the real server path
 * ([VdtParser.parseMissions]) and asserts the field mapping, the omission defaults, and a lossless
 * round-trip — the missions channel's half of the mod↔Kotlin contract.
 *
 * The capture is a real contract board: 27 contracts across 13 of the game's 16 mission types, three
 * of them this farm's (two running, one finished and uncollected) — which is also the farm at its
 * three-contract cap.
 */
class MissionModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/missions/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/missions/$name from ${File(".").absolutePath}")
  }

  private fun capture(): MissionsData = VdtParser.parseMissions(example("missions.json")).also { assertRoundTrips(it) }

  private fun assertRoundTrips(data: MissionsData) {
    val encoded = json.encodeToString(MissionsData.serializer(), data)
    val decoded = json.decodeFromString(MissionsData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesTheContractBoard() {
    val data = capture()

    assertEquals("1", data.version)
    assertTrue(data.canManage)
    assertEquals(27, data.missions.size)

    // The farm is at its cap in this capture, and the three it is running are exactly the ones it
    // owns — the count the engine's own limit check walks.
    val limit = assertNotNull(data.limit)
    assertEquals(3, limit.active)
    assertEquals(3, limit.max)
    assertTrue(limit.isReached, "the app must grey accept here")
    assertEquals(3, data.missions.count { it.own })
  }

  @Test
  fun decodesAFieldContractOnOffer() {
    val mission = assertNotNull(capture().missions.firstOrNull { it.id == 650 })

    assertEquals("herbicideMission", mission.type)
    assertEquals("Spritzen", mission.title)
    assertEquals("Land 27", mission.location)
    assertEquals(MissionStatus.CREATED, mission.status)
    assertTrue(mission.isOffered)
    assertEquals(723, mission.reward)
    assertEquals(27, mission.fieldId)
    assertEquals(0.48f, mission.areaHa)
    assertEquals(543, mission.minutesLeft)
    assertEquals(0.3708f, mission.posX)
    assertEquals(0.23726f, mission.posZ)

    val npc = assertNotNull(mission.npc)
    assertEquals("Walter", npc.name)
    assertEquals("dataS2/npc/grandpa/grandpa.png", npc.image)

    // Every contract in this capture comes with equipment to lease.
    assertTrue(mission.leasable)
    assertEquals(960, mission.vehicleCosts)

    // The game's own rows, in the player's language — the whole reason no client code knows what a
    // herbicide mission is.
    assertEquals(listOf("Mietkosten", "Feld", "Fläche"), mission.details.map { it.title })
    assertEquals("0.48 ha", mission.details[2].value)

    // Untaken: no outcome, no progress, not ours.
    assertNull(mission.finishState)
    assertNull(mission.completion)
    assertNull(mission.totalReward)
    assertFalse(mission.own)
  }

  @Test
  fun decodesAContractThisFarmIsWorking() {
    val mission = assertNotNull(capture().missions.firstOrNull { it.id == 649 })

    assertEquals("deadwoodMission", mission.type)
    assertEquals(MissionStatus.RUNNING, mission.status)
    assertTrue(mission.isActive)
    assertTrue(mission.own)
    // Just started: zero completion is a number, not an absence — it must survive as 0, since null
    // would read as "not started" on a contract that is.
    assertEquals(0f, mission.completion)
    assertEquals("Noch 6 Bäume", mission.extraProgress)
    assertEquals(3116, mission.minutesLeft)
    // Its detail rows count the trees, which is the per-type state a typed model would have needed a
    // deadwood-shaped branch for.
    assertEquals("Anzahl der Bäume", mission.details[3].title)
    assertEquals("6", mission.details[3].value)
  }

  @Test
  fun aPointLocatedContractStillNamesItsFarmland() {
    // Forestry and rock contracts have no field — but they *do* resolve the farmland under their
    // spot, so `fieldId` is populated for them too. What tells the two apart is `areaHa`: only a
    // field mission has a field object to measure. Anything keyed on "no fieldId means no field"
    // would be wrong on all three of these.
    val pointLocated = capture().missions.filter { it.id in setOf(648, 649, 661) }
    assertEquals(
      listOf("treeTransportMission", "deadwoodMission", "destructibleRockMission"),
      pointLocated.map { it.type },
    )
    for (mission in pointLocated) {
      assertNotNull(mission.fieldId, "${mission.type} should name its farmland")
      assertNull(mission.areaHa, "${mission.type} has no field to measure")
      // The marker still needs somewhere to go.
      assertNotNull(mission.posX)
      assertNotNull(mission.posZ)
    }
  }

  @Test
  fun aSuccessfulContractCanStillCostMoney() {
    // The capture's one finished contract was completed successfully and pays out **-171**: the
    // leased machines cost more than the contract was worth. So `totalReward` is not "reward minus a
    // bit" and must never be rendered as unsigned — the payout can be negative on a success.
    val mission = assertNotNull(capture().missions.firstOrNull { it.id == 658 })

    assertTrue(mission.isFinished)
    assertEquals(MissionFinishState.SUCCESS, mission.finishState)
    assertEquals(789, mission.reward)
    assertEquals(-171, mission.totalReward)
    assertEquals(960, mission.vehicleCosts)
    assertEquals(0.996f, mission.completion)

    // A finished contract carries the reward breakdown instead of the terms.
    assertEquals(
      listOf("Vertragswert", "Rückzahlung", "Mietkosten", "Fehlende Erträge"),
      mission.details.map { it.title },
    )
  }

  @Test
  fun everyMissionTypeInTheCaptureDecodesWithoutBeingKnown() {
    // Thirteen of the game's sixteen types in one board, and not one of them is named anywhere in
    // this module: they all decode through the same path, and each arrives with its own detail rows.
    val byType = capture().missions.groupBy { it.type }
    assertEquals(13, byType.size)
    for ((type, missions) in byType) {
      for (mission in missions) {
        assertTrue(mission.details.isNotEmpty(), "$type should carry the game's detail rows")
        assertTrue(mission.title.isNotEmpty(), "$type should carry a title")
        assertTrue(mission.description.isNotEmpty(), "$type should carry a description")
      }
    }
  }

  @Test
  fun theFourWaysAContractCanEndAllDecode() {
    // The capture only holds a SUCCESS, so the other three are pinned against the wire directly.
    fun finishState(token: String) =
      VdtParser
        .parseMissions("""{"version":"1","missions":[{"id":1,"status":"FINISHED","finishState":"$token"}]}""")
        .missions
        .single()
        .finishState

    assertEquals(MissionFinishState.SUCCESS, finishState("SUCCESS"))
    assertEquals(MissionFinishState.FAILED, finishState("FAILED"))
    assertEquals(MissionFinishState.TIMED_OUT, finishState("TIMED_OUT"))
    assertEquals(MissionFinishState.CANCELED, finishState("CANCELED"))
  }

  @Test
  fun anUnknownStatusFallsBackInsteadOfFailingTheParse() {
    // The mod may add a status ahead of the client — or a mod may register one of its own. Coercion
    // must leave the rest of the contract readable rather than dropping the whole channel.
    val mission =
      VdtParser
        .parseMissions(
          """{"version":"1","missions":[{"id":4,"title":"Something new","status":"HIBERNATING",""" +
            """"finishState":"EXPLODED","reward":100}]}""",
        ).missions
        .single()

    assertEquals(MissionStatus.CREATED, mission.status)
    assertNull(mission.finishState)
    assertEquals("Something new", mission.title)
    assertEquals(100, mission.reward)
  }

  @Test
  fun anEmptyChannelIsStillAValidAnswer() {
    // What the mod writes with no farm resolved (spectator): the channel exists, it just has nothing
    // to offer. It must decode to "no contracts", never to a failed parse.
    val data = VdtParser.parseMissions("""{"version":"1"}""")
    assertRoundTrips(data)

    assertEquals("1", data.version)
    assertTrue(data.missions.isEmpty())
    assertNull(data.limit)
    assertFalse(data.canManage, "no farm means nothing to manage")
  }

  @Test
  fun theContractCapIsReadableAsAPredicate() {
    fun limit(
      active: Int,
      max: Int,
    ) = assertNotNull(
      VdtParser.parseMissions("""{"version":"1","limit":{"active":$active,"max":$max}}""").limit,
    )

    assertTrue(limit(3, 3).isReached, "the app must grey accept at the cap")
    assertFalse(limit(2, 3).isReached)
    // max 0 is "the mod didn't say", not "no contracts allowed" — refusing every accept on a
    // missing field would break the app against an older mod.
    assertFalse(limit(0, 0).isReached)
  }

  @Test
  fun serverMessageCarriesTheChannelAndItsAbsence() {
    val message: ServerMessage = ServerMessage.Missions(capture())
    val encoded = json.encodeToString(ServerMessage.serializer(), message)
    assertTrue(encoded.contains("\"type\":\"missions\""), "expected discriminator, got: $encoded")
    assertEquals(message, json.decodeFromString(ServerMessage.serializer(), encoded))

    // The absence has to cross the wire too: the app clears its contracts rather than offering ones
    // that may already be gone.
    val absent: ServerMessage = ServerMessage.Missions(null)
    val decoded =
      json.decodeFromString(ServerMessage.serializer(), json.encodeToString(ServerMessage.serializer(), absent))
    assertNull(assertNotNull(decoded as? ServerMessage.Missions).data)
  }
}
