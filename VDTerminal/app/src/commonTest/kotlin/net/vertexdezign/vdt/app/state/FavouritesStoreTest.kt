package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.MapSettings
import net.vertexdezign.vdt.app.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavouritesStoreTest {
  private fun app(id: String) = Favourite(Favourite.Kind.App, id)

  @Test
  fun seedsCoreAppsOnFirstRun() {
    // Apps, not pages: the starter pages are already one swipe away with dots pointing at them.
    val store = FavouritesStore(MapSettings())
    assertEquals(listOf("map", "production", "storage"), store.favourites.value.map { it.id })
    assertTrue(store.favourites.value.all { it.kind == Favourite.Kind.App })
  }

  @Test
  fun togglePinsAndUnpins() {
    val store = FavouritesStore(MapSettings())
    store.toggle(app("map"))
    assertFalse(store.isPinned(app("map")))
    store.toggle(app("map"))
    assertTrue(store.isPinned(app("map")))
    // Re-pinning appends, so it lands at the end rather than back in its old slot.
    assertEquals("map", store.favourites.value.last().id)
  }

  @Test
  fun refusesToPinPastTheCap() {
    val store = FavouritesStore(MapSettings())
    store.toggle(app("animals")) // 3 -> 4, the cap
    assertTrue(store.isFull())
    store.toggle(app("tasks")) // no room
    assertEquals(FavouritesStore.MAX, store.favourites.value.size)
    assertFalse(store.isPinned(app("tasks")))
    // Unpinning still works at the cap — otherwise a full bar could never be changed.
    store.toggle(app("animals"))
    assertFalse(store.isFull())
  }

  @Test
  fun roundTripsThroughSettings() {
    val settings = MapSettings()
    FavouritesStore(settings).apply {
      toggle(app("storage")) // unpin
      toggle(Favourite(Favourite.Kind.Page, "vehicle"))
    }
    val reloaded = FavouritesStore(settings).favourites.value
    assertEquals(listOf("map", "production", "vehicle"), reloaded.map { it.id })
    assertEquals(Favourite.Kind.Page, reloaded.last().kind)
  }

  @Test
  fun fallsBackToDefaultsOnUnreadableStoredValue() {
    val settings = MapSettings().apply { putString("vdt.favourites", "{not json") }
    assertEquals(
      listOf(app("map"), app("production"), app("storage")),
      FavouritesStore(settings).favourites.value,
    )
  }

  @Test
  fun convertsBetweenScreens() {
    assertEquals(Screen.OpenApp("map"), app("map").toScreen())
    assertEquals(app("map"), Favourite.of(Screen.OpenApp("map")))
    assertEquals(
      Favourite(Favourite.Kind.Page, "farm"),
      Favourite.of(Screen.OpenPage("farm")),
    )
  }
}
