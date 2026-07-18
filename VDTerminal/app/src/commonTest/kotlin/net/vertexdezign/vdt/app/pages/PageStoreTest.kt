package net.vertexdezign.vdt.app.pages

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** [PageStore] ordering: reorder/move semantics and that the new order survives a reload. */
class PageStoreTest {
  private fun ids(store: PageStore) = store.pages.value.map { it.id }

  @Test
  fun reorderMovesItemAndPersists() {
    val settings = MapSettings()
    val store = PageStore(settings)
    assertEquals(listOf("vehicle", "farm"), ids(store)) // the seed order

    store.reorder(0, 1)
    assertEquals(listOf("farm", "vehicle"), ids(store))

    // A fresh store over the same settings sees the persisted order.
    assertEquals(listOf("farm", "vehicle"), ids(PageStore(settings)))
  }

  @Test
  fun reorderIgnoresNoOpAndOutOfRange() {
    val store = PageStore(MapSettings())
    val before = ids(store)
    store.reorder(0, 0) // same slot
    store.reorder(-1, 1) // from out of range
    store.reorder(0, 5) // to out of range
    assertEquals(before, ids(store))
  }

  @Test
  fun moveByIdClampsToEnds() {
    val store = PageStore(MapSettings()) // [vehicle, farm]
    store.move("vehicle", Int.MAX_VALUE) // clamps to the last slot (must not overflow to the front)
    assertEquals(listOf("farm", "vehicle"), ids(store))
    store.move("vehicle", Int.MIN_VALUE) // clamps back to the first
    assertEquals(listOf("vehicle", "farm"), ids(store))
    store.move("missing", 1) // unknown id: no-op
    assertEquals(listOf("vehicle", "farm"), ids(store))
  }
}
