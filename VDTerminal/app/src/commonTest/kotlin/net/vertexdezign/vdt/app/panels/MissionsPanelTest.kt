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
  ) = Mission(
    id = 1,
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
  fun moneyReadsAsMoney() {
    assertEquals("5,400", money(5400))
    assertEquals("980", money(980))
    assertEquals("1,234,567", money(1234567))
    assertEquals("0", money(0))
    // A collected contract can pay out less than nothing once the leased machines are paid for.
    assertEquals("-1,200", money(-1200))
  }
}
