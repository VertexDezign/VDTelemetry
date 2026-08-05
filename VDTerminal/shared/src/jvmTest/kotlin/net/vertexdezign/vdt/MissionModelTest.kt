package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.MissionFinishState
import net.vertexdezign.vdt.model.MissionStatus
import net.vertexdezign.vdt.model.MissionsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the missions channel through the real server path ([VdtParser.parseMissions]) and asserts
 * the field mapping, the omission defaults, and a lossless round-trip — the missions channel's half
 * of the mod↔Kotlin contract.
 */
class MissionModelTest {
  private val json = Json { encodeDefaults = true }

  private fun assertRoundTrips(data: MissionsData) {
    val encoded = json.encodeToString(MissionsData.serializer(), data)
    val decoded = json.decodeFromString(MissionsData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun decodesAContractOnOffer() {
    val data =
      VdtParser.parseMissions(
        """{"version":"1","canManage":true,"limit":{"active":1,"max":3},"missions":[{"id":77,""" +
          """"type":"sowMission","title":"Sowing","description":"Sow the field","status":"CREATED",""" +
          """"location":"Field 12","reward":5400,"leasable":true,"vehicleCosts":621,"fieldId":12,""" +
          """"areaHa":3.46,"posX":0.75,"posZ":0.25,"npc":{"name":"Anna","image":"npc/anna.png"},""" +
          """"details":[{"title":"Field","value":"12"},{"title":"Crop","value":"Wheat"}]}]}""",
      )
    assertRoundTrips(data)

    assertEquals("1", data.version)
    assertTrue(data.canManage)
    val limit = assertNotNull(data.limit)
    assertEquals(1, limit.active)
    assertEquals(3, limit.max)
    assertFalse(limit.isReached, "one of three used is not the cap")

    val mission = data.missions.single()
    assertEquals(77, mission.id)
    assertEquals("sowMission", mission.type)
    assertEquals(MissionStatus.CREATED, mission.status)
    assertTrue(mission.isOffered)
    assertFalse(mission.isActive)
    assertEquals(5400, mission.reward)
    assertTrue(mission.leasable)
    assertEquals(621, mission.vehicleCosts)
    assertEquals(12, mission.fieldId)
    assertEquals(3.46f, mission.areaHa)
    assertEquals(0.75f, mission.posX)
    assertEquals("Anna", assertNotNull(mission.npc).name)

    // The per-type detail arrives as the game's own rows — nothing here knows what a sow mission is.
    assertEquals(listOf("Field", "Crop"), mission.details.map { it.title })
    assertEquals("Wheat", mission.details[1].value)

    // Nothing has been taken on: no outcome, no progress, not ours.
    assertNull(mission.finishState)
    assertNull(mission.completion)
    assertNull(mission.totalReward)
    assertFalse(mission.own)
  }

  @Test
  fun decodesARunningContract() {
    val mission =
      VdtParser
        .parseMissions(
          """{"version":"1","missions":[{"id":3,"type":"deadwoodMission","title":"Deadwood",""" +
            """"status":"RUNNING","own":true,"completion":0.4237,"minutesLeft":320,""" +
            """"extraProgress":"3 trees remaining","reward":2100}]}""",
        ).missions
        .single()

    assertEquals(MissionStatus.RUNNING, mission.status)
    assertTrue(mission.isActive)
    assertFalse(mission.isOffered)
    assertTrue(mission.own)
    assertEquals(0.4237f, mission.completion)
    assertEquals(320, mission.minutesLeft)
    assertEquals("3 trees remaining", mission.extraProgress)
    // A forestry contract has no field, so nothing joins to the map polygons — the marker is all it
    // gets. Null rather than 0: field 0 is a real farmland id.
    assertNull(mission.fieldId)
    assertNull(mission.areaHa)
  }

  @Test
  fun decodesAFinishedContractAndItsPayout() {
    val mission =
      VdtParser
        .parseMissions(
          """{"version":"1","missions":[{"id":9,"type":"harvestMission","title":"Harvesting",""" +
            """"status":"FINISHED","finishState":"SUCCESS","own":true,"completion":1.0,""" +
            """"reward":8200,"totalReward":7600,"details":[{"title":"Reward","value":"8.200 €"},""" +
            """{"title":"Vehicle costs","value":"-600 €"}]}]}""",
        ).missions
        .single()

    assertTrue(mission.isFinished)
    assertEquals(MissionFinishState.SUCCESS, mission.finishState)
    // `reward` is what was offered; `totalReward` is what collecting it actually pays, and the two
    // differ by exactly the costs the detail rows break down.
    assertEquals(8200, mission.reward)
    assertEquals(7600, mission.totalReward)
    assertEquals("-600 €", mission.details[1].value)
  }

  @Test
  fun theFourWaysAContractCanEndAllDecode() {
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
    val data =
      VdtParser.parseMissions(
        """{"version":"1","missions":[{"id":4,"title":"Something new","status":"HIBERNATING",""" +
          """"finishState":"EXPLODED","reward":100}]}""",
      )
    val mission = data.missions.single()
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
    val message: ServerMessage = ServerMessage.Missions(VdtParser.parseMissions("""{"version":"1"}"""))
    val encoded = json.encodeToString(ServerMessage.serializer(), message)
    assertTrue(encoded.contains("\"type\":\"missions\""), "expected discriminator, got: $encoded")
    assertEquals(message, json.decodeFromString(ServerMessage.serializer(), encoded))

    // The absence has to cross the wire too: the app clears its contracts rather than offering ones
    // that may already be gone.
    val absent: ServerMessage = ServerMessage.Missions(null)
    val decoded =
      json.decodeFromString(
        ServerMessage.serializer(),
        json.encodeToString(ServerMessage.serializer(), absent),
      )
    assertNull(assertNotNull(decoded as? ServerMessage.Missions).data)
  }
}
