package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.AdsInspected
import net.vertexdezign.vdt.model.AdsService
import net.vertexdezign.vdt.model.AdsState
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FleetAds
import net.vertexdezign.vdt.model.FleetBreakdown
import net.vertexdezign.vdt.model.FleetVehicle
import net.vertexdezign.vdt.model.GameDate
import net.vertexdezign.vdt.model.MotorFillUnits
import net.vertexdezign.vdt.model.PropertyState
import net.vertexdezign.vdt.model.Wearable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the fleet list decides for itself: which of the two condition figures is the true one, what
 * counts as worth a look before a machine goes out, and an ordering that does not move under the
 * reader's finger.
 */
class FleetPanelTest {
  private fun machine(
    id: Int = 1,
    name: String = "Machine",
    category: String? = "Tractors",
    age: Int = 12,
    hours: Float = 100f,
    propertyState: PropertyState = PropertyState.OWNED,
    sellPrice: Int? = 50000,
    damage: Int? = 10,
    fuel: Int? = 80,
    ads: FleetAds? = null,
  ) = FleetVehicle(
    id = id,
    name = name,
    category = category,
    age = age,
    hours = hours,
    propertyState = propertyState,
    sellPrice = sellPrice,
    wearable = damage?.let { Wearable(damage = it, wear = 20, dirt = 30, unit = "%") },
    motorFillUnits = fuel?.let { MotorFillUnits(fuel = FillUnit(fillLevelPercentage = it)) },
    ads = ads,
  )

  private fun ads(
    state: AdsState = AdsState.READY,
    condition: Int? = null,
    complete: Boolean = true,
    hours: Float = 10f,
    interval: Float = 60f,
    breakdowns: List<FleetBreakdown> = emptyList(),
  ) = FleetAds(
    state = state,
    inspected = condition?.let { AdsInspected(condition = it, complete = complete) },
    service = AdsService(hours = hours, interval = interval),
    breakdowns = breakdowns,
  )

  @Test
  fun conditionComesFromWearWithoutAds() {
    assertEquals(90, fleetCondition(machine(damage = 10)))
    assertNull(fleetCondition(machine(damage = null)), "no wearable spec, no condition")
  }

  @Test
  fun conditionComesFromTheInspectionUnderAds() {
    // ADS pins the vanilla damage to 0, so reading it would report this worn machine as brand new.
    val machine = machine(damage = 0, ads = ads(condition = 47))
    assertEquals(47, fleetCondition(machine))
  }

  @Test
  fun aMachineAdsHasNeverInspectedHasNoCondition() {
    val machine = machine(damage = 0, ads = ads(condition = null))
    assertNull(fleetCondition(machine), "hiding it is the mechanic; 100% would be a lie")
    assertFalse(needsAttention(machine), "unknown is not the same as bad")
  }

  @Test
  fun anOrdinaryInspectionIsMarkedAsAnEstimate() {
    assertTrue(conditionIsApproximate(machine(ads = ads(condition = 60, complete = false))))
    assertFalse(conditionIsApproximate(machine(ads = ads(condition = 60, complete = true))))
    assertFalse(conditionIsApproximate(machine(damage = 10)), "a vanilla figure is exact")
  }

  @Test
  fun attentionCoversWearFuelAndEverythingAdsFlags() {
    assertFalse(needsAttention(machine()))
    assertTrue(needsAttention(machine(damage = 70)), "30% condition")
    assertTrue(needsAttention(machine(fuel = 4)))
    assertTrue(needsAttention(machine(ads = ads(state = AdsState.BROKEN))))
    assertTrue(needsAttention(machine(ads = ads(hours = 80f, interval = 60f))), "service overdue")
    assertTrue(
      needsAttention(machine(ads = ads(breakdowns = listOf(FleetBreakdown(id = "OIL"))))),
      "a fault the player has already found",
    )
  }

  @Test
  fun theViewsSliceTheFleet() {
    val ready = machine(id = 1, name = "Ready")
    val worn = machine(id = 2, name = "Worn", damage = 80)
    val inShop = machine(id = 3, name = "Shop", ads = ads(state = AdsState.REPAIR))
    val leased = machine(id = 4, name = "Leased", propertyState = PropertyState.LEASED)
    val plough = machine(id = 5, name = "Plough", fuel = null)
    val all = listOf(ready, worn, inShop, leased, plough)

    assertEquals(all, fleetView(all, FleetView.ALL))
    assertEquals(listOf(worn, inShop), fleetView(all, FleetView.ATTENTION))
    assertEquals(listOf(inShop), fleetView(all, FleetView.WORKSHOP))
    assertEquals(listOf(leased), fleetView(all, FleetView.LEASED))
    assertEquals(listOf(plough), fleetView(all, FleetView.IMPLEMENTS))
  }

  @Test
  fun onlyTheViewsAFleetCanAnswerAreOffered() {
    val plain = listOf(machine(id = 1))
    assertEquals(listOf(FleetView.ALL, FleetView.ATTENTION), fleetViews(plain))

    val mixed = listOf(
      machine(id = 1, ads = ads()),
      machine(id = 2, propertyState = PropertyState.LEASED),
      machine(id = 3, fuel = null),
    )
    assertEquals(FleetView.entries, fleetViews(mixed))
  }

  @Test
  fun searchMatchesNameAndCategory() {
    val fendt = machine(id = 1, name = "Fendt 942", category = "Tractors")
    val plough = machine(id = 2, name = "Lemken Juwel", category = "Ploughs")
    val all = listOf(fendt, plough)

    assertEquals(all, fleetSearch(all, "  "))
    assertEquals(listOf(fendt), fleetSearch(all, "fendt"))
    assertEquals(listOf(plough), fleetSearch(all, "PLOUGH"))
    assertTrue(fleetSearch(all, "combine").isEmpty())
  }

  @Test
  fun sortingIsTotalSoTheListDoesNotReshuffle() {
    // Three machines with the same hours: the name has to decide, in both directions, or a refresh
    // moves rows around under the reader.
    val b = machine(id = 1, name = "Bravo", hours = 10f)
    val a = machine(id = 2, name = "alpha", hours = 10f)
    val c = machine(id = 3, name = "Charlie", hours = 10f)
    val all = listOf(b, a, c)

    assertEquals(listOf(a, b, c), fleetSorted(all, FleetSort.HOURS, ascending = true))
    assertEquals(listOf(a, b, c), fleetSorted(all, FleetSort.HOURS, ascending = false))
    assertEquals(listOf(a, b, c), fleetSorted(all, FleetSort.NAME, ascending = true))
    assertEquals(listOf(c, b, a), fleetSorted(all, FleetSort.NAME, ascending = false))
  }

  @Test
  fun unknownValuesSortToTheEndInBothDirections() {
    val good = machine(id = 1, name = "Good", damage = 5)
    val bad = machine(id = 2, name = "Bad", damage = 60)
    val unmeasured = machine(id = 3, name = "Unmeasured", damage = 0, ads = ads(condition = null))
    val all = listOf(good, bad, unmeasured)

    assertEquals(listOf(bad, good, unmeasured), fleetSorted(all, FleetSort.CONDITION, ascending = true))
    assertEquals(listOf(good, bad, unmeasured), fleetSorted(all, FleetSort.CONDITION, ascending = false))
  }

  @Test
  fun sortingBySellValueLeavesLeasedMachinesLast() {
    val cheap = machine(id = 1, name = "Cheap", sellPrice = 1000)
    val dear = machine(id = 2, name = "Dear", sellPrice = 90000)
    val leased = machine(id = 3, name = "Leased", propertyState = PropertyState.LEASED, sellPrice = null)
    val all = listOf(cheap, dear, leased)

    assertEquals(listOf(dear, cheap, leased), fleetSorted(all, FleetSort.VALUE, ascending = false))
  }

  @Test
  fun sortingByServiceUsesHowFarThroughTheIntervalTheMachineIs() {
    // Not the raw hours: a machine 50 hours into a 60-hour interval is closer to due than one 60
    // hours into a 200-hour one.
    val nearlyDue = machine(id = 1, name = "Nearly", ads = ads(hours = 50f, interval = 60f))
    val plenty = machine(id = 2, name = "Plenty", ads = ads(hours = 60f, interval = 200f))
    val noAds = machine(id = 3, name = "NoAds", ads = null)
    val all = listOf(plenty, nearlyDue, noAds)

    assertEquals(listOf(nearlyDue, plenty, noAds), fleetSorted(all, FleetSort.SERVICE, ascending = false))
  }

  @Test
  fun theRowSaysCategoryHoursAndFuel() {
    assertEquals("Tractors · 1234.5 h · fuel 42%", rowSubtitle(machine(hours = 1234.5f, fuel = 42)))
    assertEquals("Ploughs · 61.2 h", rowSubtitle(machine(category = "Ploughs", hours = 61.24f, fuel = null)))
  }

  @Test
  fun theRowsBadgesAreWordsAndTheWorstOneWins() {
    assertEquals(listOf("BROKEN"), rowBadges(machine(ads = ads(state = AdsState.BROKEN))))
    assertEquals(listOf("WORKSHOP"), rowBadges(machine(ads = ads(state = AdsState.OVERHAUL))))
    assertEquals(listOf("SERVICE DUE"), rowBadges(machine(ads = ads(hours = 90f, interval = 60f))))
    assertEquals(listOf("FAULT"), rowBadges(machine(ads = ads(breakdowns = listOf(FleetBreakdown(id = "OIL"))))))
    assertEquals(emptyList(), rowBadges(machine()))
    assertEquals(listOf("LEASED"), rowBadges(machine(propertyState = PropertyState.LEASED)))
  }

  @Test
  fun formatsHoursAgeAndTimeAgo() {
    assertEquals("1234.5", formatHours(1234.54f))
    assertEquals("0.0", formatHours(0f))
    assertEquals("7 mo", formatAge(7))
    assertEquals("1 y", formatAge(12))
    assertEquals("2 y 3 mo", formatAge(27))

    val today = GameDate(year = 2, month = 7, day = 11)
    assertEquals(0, monthsBetween(GameDate(2, 7, 1), today))
    assertEquals(3, monthsBetween(GameDate(2, 4, 1), today))
    assertEquals(7, monthsBetween(GameDate(1, 12, 1), today))
    // A date in the future (a save fiddled with, or a clock the mod read mid-rollover) reads as now
    // rather than as "-2 months ago".
    assertEquals(0, monthsBetween(GameDate(3, 1, 1), today))

    assertEquals("this month", formatMonthsAgo(0))
    assertEquals("1 month ago", formatMonthsAgo(1))
    assertEquals("5 months ago", formatMonthsAgo(5))
  }

  @Test
  fun formatsTheWorkshopFinishTime() {
    assertEquals("16:30", formatFinish(16.5f, 0))
    assertEquals("07:00 tomorrow", formatFinish(7f, 1))
    assertEquals("09:15, in 3 days", formatFinish(9.25f, 3))
  }

  @Test
  fun saysWhoHasTheMachine() {
    assertEquals("Parked", statusLabel(machine()))
    assertEquals("Helper driving", statusLabel(machine().copy(isAI = true)))
    assertEquals("In use", statusLabel(machine().copy(isControlled = true)))
    assertEquals("You are in it", statusLabel(machine().copy(isEntered = true, isControlled = true)))
    assertEquals("Attached", statusLabel(machine().copy(attachedTo = 9)))
  }

  @Test
  fun theHeadlineNamesTheRigAnImplementIsOn() {
    assertEquals("Ploughs · on Fendt 942", headline(machine(category = "Ploughs", fuel = null), "Fendt 942"))
    assertEquals("Tractors · leased", headline(machine(propertyState = PropertyState.LEASED), null))
  }
}
