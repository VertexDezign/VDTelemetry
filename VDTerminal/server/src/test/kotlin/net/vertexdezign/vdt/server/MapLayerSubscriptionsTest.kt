package net.vertexdezign.vdt.server

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The union the mod is told to sweep. The cost of getting this wrong is asymmetric: too small and the
 * overlay someone is looking at silently stops updating; too eager and the mod restarts its sweep for
 * commands that changed nothing.
 */
class MapLayerSubscriptionsTest {
  private val published = mutableListOf<List<String>>()
  private val subscriptions = MapLayerSubscriptions { published.add(it) }

  @Test
  fun `unions what the connected dashboards are showing`() {
    subscriptions.show(1, listOf("crops"))
    subscriptions.show(2, listOf("growth"))

    assertEquals(listOf(listOf("crops"), listOf("crops", "growth")), published)
    assertEquals(listOf("crops", "growth"), subscriptions.current())
  }

  @Test
  fun `publishes nothing when the union is unchanged`() {
    subscriptions.show(1, listOf("crops"))
    // A second dashboard opening the same overlay, and the first one reconnecting and restating what
    // it already had: neither may disturb the sweep in flight.
    subscriptions.show(2, listOf("crops"))
    subscriptions.show(1, listOf("crops"))

    assertEquals(listOf(listOf("crops")), published)
  }

  @Test
  fun `a dashboard switching layers drops the plane nobody else is showing`() {
    subscriptions.show(1, listOf("crops"))
    subscriptions.show(1, listOf("growth"))

    assertEquals(listOf(listOf("crops"), listOf("growth")), published)
  }

  @Test
  fun `keeps a plane another session still shows`() {
    subscriptions.show(1, listOf("crops"))
    subscriptions.show(2, listOf("crops", "soil"))
    subscriptions.forget(1)

    // Session 1 leaving must not take crops with it -- session 2 is still showing it.
    assertEquals(listOf("crops", "soil"), subscriptions.current())
    assertEquals(listOf(listOf("crops"), listOf("crops", "soil")), published)
  }

  @Test
  fun `the last dashboard leaving means nobody is looking`() {
    subscriptions.show(1, listOf("crops"))
    subscriptions.forget(1)

    assertEquals(listOf(listOf("crops"), emptyList()), published)
    assertEquals(emptyList(), subscriptions.current())
  }

  @Test
  fun `forgetting an unknown session changes nothing`() {
    subscriptions.show(1, listOf("crops"))
    subscriptions.forget(99)

    assertEquals(listOf(listOf("crops")), published)
  }

  /** The command is a function of the set, so map iteration order must not rewrite the file. */
  @Test
  fun `publishes a sorted list`() {
    subscriptions.show(1, listOf("soil", "crops"))
    subscriptions.show(2, listOf("growth"))

    assertEquals(listOf(listOf("crops", "soil"), listOf("crops", "growth", "soil")), published)
  }

  /**
   * The bug this exists for: the mod deletes commands.xml at every map load, so a subscription sent
   * before the game reached the map was thrown away unread — and since the dashboards' desire never
   * changed, nothing would ever restate it. The overlay just never appeared.
   */
  @Test
  fun `restates the subscription when the mod reports it never got it`() {
    subscriptions.show(1, listOf("crops"))
    published.clear()

    subscriptions.reconcile(modActive = emptyList(), offered = listOf("crops", "growth", "soil"))

    assertEquals(listOf(listOf("crops")), published)
  }

  @Test
  fun `stays quiet when the mod is already sweeping what is wanted`() {
    subscriptions.show(1, listOf("crops"))
    published.clear()

    subscriptions.reconcile(modActive = listOf("crops"), offered = listOf("crops", "growth", "soil"))

    assertEquals(emptyList(), published)
  }

  /** A server restart under a running game: the mod sweeps for dashboards that no longer exist. */
  @Test
  fun `tells a mod sweeping for nobody to stop`() {
    subscriptions.reconcile(modActive = listOf("crops"), offered = listOf("crops", "growth", "soil"))

    assertEquals(listOf(emptyList()), published)
  }

  /**
   * A layer id the app persisted from a map that offered it must never be asked for here: the mod
   * would ignore it, keep reporting it inactive, and this would restate the command on every
   * catalogue write for the rest of the session.
   */
  @Test
  fun `never asks for a plane this map does not offer`() {
    subscriptions.show(1, listOf("nutrients"))
    published.clear()

    subscriptions.reconcile(modActive = emptyList(), offered = listOf("crops", "growth", "soil"))

    assertEquals(emptyList(), published, "an unofferable selection is not a mismatch to fix")
  }

  @Test
  fun `reconciling does not disturb the desired set`() {
    subscriptions.show(1, listOf("crops", "nutrients"))
    subscriptions.reconcile(modActive = emptyList(), offered = listOf("crops"))

    // The unofferable id is still wanted -- a later map (or mod) that offers it gets it.
    assertEquals(listOf("crops", "nutrients"), subscriptions.current())
  }

  @Test
  fun `an empty selection is a legitimate state, not a no-op`() {
    subscriptions.show(1, listOf("crops"))
    subscriptions.show(1, emptyList()) // the user picked "None"

    assertEquals(listOf(listOf("crops"), emptyList()), published)
  }
}
