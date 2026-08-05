package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Mission
import net.vertexdezign.vdt.model.MissionFinishState
import net.vertexdezign.vdt.model.MissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract-row formatting that decides what a glance at the list actually says: the one-line
 * state, the colour that flags a contract worth hurrying for, and the money/time formatting.
 */
class MissionsPanelTest {
  private fun mission(
    status: MissionStatus = MissionStatus.CREATED,
    finishState: MissionFinishState? = null,
    completion: Float? = null,
    minutesLeft: Int? = null,
    id: Int = 1,
    type: String = "harvestMission",
    title: String = "Ernten",
    location: String = "",
    subtitle: String = "",
    extraProgress: String = "",
  ) = Mission(
    id = id,
    type = type,
    title = title,
    location = location,
    subtitle = subtitle,
    extraProgress = extraProgress,
    status = status,
    finishState = finishState,
    completion = completion,
    minutesLeft = minutesLeft,
  )

  @Test
  fun anOfferedContractCountsDownToItsDeadline() {
    assertEquals("2d 4h", statusLine(mission(minutesLeft = 2 * 1440 + 4 * 60)))
    assertEquals("3h", statusLine(mission(minutesLeft = 200)))
    assertEquals("45m", statusLine(mission(minutesLeft = 45)))
    assertEquals("expired", statusLine(mission(minutesLeft = 0)))
  }

  @Test
  fun aRunningContractShowsHowFarAlongItIs() {
    assertEquals("42%", statusLine(mission(status = MissionStatus.RUNNING, completion = 0.4237f)))
    // Accepted but not yet under way: the engine has no completion for it, and the countdown is not
    // the answer to "how is it going".
    assertEquals("Preparing", statusLine(mission(status = MissionStatus.PREPARING, minutesLeft = 600)))
  }

  @Test
  fun aFinishedContractSaysHowItEnded() {
    val done = MissionStatus.FINISHED
    assertEquals("Done", statusLine(mission(done, MissionFinishState.SUCCESS)))
    assertEquals("Failed", statusLine(mission(done, MissionFinishState.FAILED)))
    assertEquals("Timed out", statusLine(mission(done, MissionFinishState.TIMED_OUT)))
    assertEquals("Cancelled", statusLine(mission(done, MissionFinishState.CANCELED)))
    // The outcome outranks the clock: a contract that ran out of time reads "Timed out", not "expired".
    assertEquals("Timed out", statusLine(mission(done, MissionFinishState.TIMED_OUT, minutesLeft = 0)))
  }

  @Test
  fun theColourFlagsAnOfferAboutToLapse() {
    // Under an hour of game time is the point at which taking it on becomes urgent.
    assertEquals(VdtColors.Amber, statusColor(mission(minutesLeft = 30), selected = false))
    assertEquals(VdtColors.DarkGray, statusColor(mission(minutesLeft = 600), selected = false))
    // A contract with no deadline at all must not read as urgent.
    assertEquals(VdtColors.DarkGray, statusColor(mission(), selected = false))
    // …and a running one is not an offer about to lapse, however little time is left.
    assertEquals(
      VdtColors.DarkGray,
      statusColor(mission(status = MissionStatus.RUNNING, minutesLeft = 10), selected = false),
    )
  }

  @Test
  fun theColourSeparatesASuccessFromTheOtherThreeOutcomes() {
    val done = MissionStatus.FINISHED
    assertEquals(VdtColors.Green, statusColor(mission(done, MissionFinishState.SUCCESS), selected = false))
    assertEquals(VdtColors.Red, statusColor(mission(done, MissionFinishState.FAILED), selected = false))
    assertEquals(VdtColors.Red, statusColor(mission(done, MissionFinishState.CANCELED), selected = false))
    // On the selected row the green fill is the background, so the text goes white regardless.
    assertEquals(VdtColors.White, statusColor(mission(done, MissionFinishState.FAILED), selected = true))
  }

  @Test
  fun theMapColoursAContractByWhatYouCanDoAboutIt() {
    // Amber: still open. Blue: under way. Green: money waiting. The same colour tints the contract's
    // field, so marker and field agree — and it is keyed off status, never off the mission type.
    assertEquals(VdtColors.Amber, missionColor(mission()))
    assertEquals(VdtColors.ProgressBlue, missionColor(mission(status = MissionStatus.RUNNING)))
    assertEquals(VdtColors.ProgressBlue, missionColor(mission(status = MissionStatus.PREPARING)))
    assertEquals(VdtColors.Green, missionColor(mission(status = MissionStatus.FINISHED)))
    // A failed contract is still waiting to be cleared off the list, so it stays in the finished
    // colour rather than becoming an offer again.
    assertEquals(VdtColors.Green, missionColor(mission(MissionStatus.FINISHED, MissionFinishState.FAILED)))
  }

  @Test
  fun aRowSaysWhereTheWorkIsAndWhatItIsFor() {
    // The subject is the mod's, already localized — the crop on a harvest job, the bale form on a
    // baling one. The row joins it to the location rather than spending a third line on it.
    assertEquals(
      "Land 49 · Hafer",
      rowSubject(mission(location = "Land 49", subtitle = "Hafer")),
    )
    assertEquals(
      "Land 35 · Rundballen",
      rowSubject(mission(location = "Land 35", subtitle = "Rundballen", type = "baleMission")),
    )
    // A contract that is for nothing in particular (ploughing, mowing) just says where.
    assertEquals("Land 2", rowSubject(mission(location = "Land 2")))
    // And one the mod could not place falls back to naming its type rather than showing a blank.
    assertEquals("plowMission", rowSubject(mission(type = "plowMission")))
  }

  @Test
  fun theTypeFilterIsBuiltFromTheBoardItself() {
    // The chips carry the game's own name for each kind of work, taken off the contracts — nothing
    // here spells out a mission type, so a modded one gets a chip like any other.
    val board =
      listOf(
        mission(id = 1, type = "harvestMission", title = "Ernten"),
        mission(id = 2, type = "harvestMission", title = "Ernten"),
        mission(id = 3, type = "plowMission", title = "Pflügen"),
        mission(id = 4, type = "harvestMission", title = "Ernten"),
        mission(id = 5, type = "modded.somethingNew", title = "Etwas Neues"),
      )

    val kinds = missionKinds(board)
    // Most-offered first: the filter's job is to cut the board down, so the big piles come first.
    assertEquals(listOf("Ernten", "Etwas Neues", "Pflügen"), kinds.map { it.label })
    assertEquals(listOf(3, 1, 1), kinds.map { it.count })
    assertEquals("modded.somethingNew", kinds[1].type)
  }

  @Test
  fun aKindWithNoTitleFallsBackToItsType() {
    // Better a raw token than a blank chip nobody can press with intent.
    assertEquals("weirdMission", missionKinds(listOf(mission(type = "weirdMission", title = ""))).single().label)
  }

  @Test
  fun theTileSaysWhatIsHappeningRatherThanRepeatingTheBar() {
    // The bar already shows the percentage, so the label spends its room on the game's own running
    // commentary where there is one.
    assertEquals(
      "Land 80 · Noch 6 Bäume",
      widgetProgressLabel(mission(location = "Land 80", extraProgress = "Noch 6 Bäume")),
    )
    assertEquals("Land 12", widgetProgressLabel(mission(location = "Land 12")))
    // With no location at all it names the job instead of showing nothing.
    assertEquals("Ernten", widgetProgressLabel(mission()))
  }

  @Test
  fun moneyReadsAsMoney() {
    assertEquals("5,400", money(5400))
    assertEquals("980", money(980))
    assertEquals("1,234,567", money(1234567))
    assertEquals("0", money(0))
    // A collected contract can pay out less than nothing once the leased machines are paid for.
    assertEquals("-1,200", money(-1200))
  }
}
