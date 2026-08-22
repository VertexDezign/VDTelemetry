package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Mixer
import net.vertexdezign.vdt.model.MixerIngredient
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun ingredient(name: String, min: Int, max: Int, value: Double) =
  MixerIngredient(name = name, title = name, minPercentage = min, maxPercentage = max, value = value)

private fun mixer(vararg ingredients: MixerIngredient, capacity: Int = 12000) = Mixer(
  value = ingredients.sumOf { it.value },
  capacity = capacity,
  ingredients = ingredients.toList(),
)

/**
 * The two pieces of ISOBUS-panel logic that produce a *number* rather than a layout: which machine
 * the panel is looking at, and how much of an ingredient is still missing. Both fail quietly — a
 * wrong machine still draws a panel, a wrong shortfall still reads as a plausible litre count — so
 * they are tested away from Compose.
 */
class IsoBusTest {
  // -------------------------------------------------------------------------
  // Which machine the panel follows
  // -------------------------------------------------------------------------

  @Test
  fun autoFollowsTheFirstMachineWithASection() {
    val rig =
      Vehicle(
        name = "Tractor",
        implement =
        listOf(
          Implement(position = "FRONT", name = "Weight"),
          Implement(position = "BACK", name = "Mixer", mixer = mixer()),
        ),
      )
    assertEquals("Mixer", isoBusMachine(rig, null)?.name)
  }

  @Test
  fun autoReachesDownTheHitchChain() {
    // The mixer is behind a dolly, so it is not the rig's own BACK implement — a search that only
    // looked one level down would report nothing on the bus.
    val rig =
      Vehicle(
        name = "Tractor",
        implement =
        listOf(
          Implement(
            position = "BACK",
            name = "Dolly",
            implement = listOf(Implement(position = "BACK", name = "Mixer", mixer = mixer())),
          ),
        ),
      )
    assertEquals("Mixer", isoBusMachine(rig, null)?.name)
  }

  @Test
  fun autoFindsASelfPropelledMachineOnTheVehicleItself() {
    // A self-propelled mixer *is* the vehicle; there is no implement to look at.
    val rig = Vehicle(name = "SelfLine", mixer = mixer())
    assertEquals("SelfLine", isoBusMachine(rig, null)?.name)
  }

  @Test
  fun autoReportsNothingWhenNoMachineHasASection() {
    val rig = Vehicle(name = "Tractor", implement = listOf(Implement(position = "BACK", name = "Plough")))
    assertNull(isoBusMachine(rig, null))
  }

  @Test
  fun aNamedSlotPinsTheTileToThatPositionEvenWhenItIsEmpty() {
    // Not a fallback to whatever else is on the rig: a tile placed on FRONT says "nothing attached to
    // front" rather than silently swapping in the mixer hanging off the back.
    val rig =
      Vehicle(name = "Tractor", implement = listOf(Implement(position = "BACK", name = "Mixer", mixer = mixer())))
    assertNull(isoBusMachine(rig, RigSlot.FRONT))
    assertEquals("Mixer", isoBusMachine(rig, RigSlot.REAR)?.name)
    assertEquals("Tractor", isoBusMachine(rig, RigSlot.VEHICLE)?.name)
  }

  // -------------------------------------------------------------------------
  // How much is still missing
  // -------------------------------------------------------------------------

  @Test
  fun theShortfallAccountsForItsOwnEffectOnTheLoad() {
    // 1000 l of hay in a 4000 l load is 25%, against a 40% minimum. The naive `0.40*4000 - 1000` says
    // 600 l — but adding 600 l makes the load 4600 and the hay 1600, which is 34.8%, still short. The
    // real answer is 1000 l: 2000 of 5000 is exactly 40%.
    val hay = ingredient("Hay", min = 40, max = 60, value = 1000.0)
    val silage = ingredient("Silage", min = 20, max = 60, value = 3000.0)
    assertEquals(1000, shortfall(mixer(hay, silage), hay))
  }

  @Test
  fun theShortfallActuallyLandsInsideTheWindow() {
    val hay = ingredient("Hay", min = 40, max = 60, value = 1000.0)
    val silage = ingredient("Silage", min = 20, max = 60, value = 3000.0)
    val m = mixer(hay, silage)

    val added = shortfall(m, hay)!!.toDouble()
    val topped =
      mixer(hay.copy(value = hay.value + added), silage)
    assertEquals(0.40, topped.shareOf(topped.ingredients.first()), 0.0001)
  }

  @Test
  fun anIngredientAlreadyInsideItsWindowIsNotShort() {
    val hay = ingredient("Hay", min = 20, max = 60, value = 1000.0)
    val silage = ingredient("Silage", min = 20, max = 60, value = 3000.0)
    assertNull(shortfall(mixer(hay, silage), hay))
  }

  @Test
  fun anIngredientOverItsWindowIsNotShortEither() {
    // Over-max is fixed by adding *something else*, so this ingredient has nothing to say about it.
    val hay = ingredient("Hay", min = 20, max = 40, value = 3000.0)
    val silage = ingredient("Silage", min = 20, max = 60, value = 1000.0)
    assertNull(shortfall(mixer(hay, silage), hay))
  }

  @Test
  fun anEmptyWagonIsNotShortOfAnything() {
    // Every ingredient reads 0% in an empty tub, and none of that is a problem to solve.
    val hay = ingredient("Hay", min = 40, max = 60, value = 0.0)
    val silage = ingredient("Silage", min = 40, max = 60, value = 0.0)
    assertNull(shortfall(mixer(hay, silage), hay))
  }

  @Test
  fun anIngredientThatMustBeTheWholeLoadCannotBeToppedUp() {
    // A 100% minimum has no solution — `1 - min` is zero, and the naive arithmetic would divide by it.
    val only = ingredient("Silage", min = 100, max = 100, value = 100.0)
    val other = ingredient("Hay", min = 0, max = 0, value = 900.0)
    assertNull(shortfall(mixer(only, other), only))
  }

  // -------------------------------------------------------------------------
  // Formatting
  // -------------------------------------------------------------------------

  @Test
  fun massDropsToKilogramsBelowATonne() {
    assertEquals("640 kg", formatTonnes(0.64))
    assertEquals("1.0 t", formatTonnes(1.0))
    assertEquals("12.8 t", formatTonnes(12.75))
  }

  @Test
  fun theMixCountdownLosesItsDecimalOnceItIsNotWorthWatching() {
    assertEquals("3.2s", formatSeconds(3200))
    assertEquals("0.0s", formatSeconds(0))
    assertEquals("45s", formatSeconds(45_000))
  }
}
