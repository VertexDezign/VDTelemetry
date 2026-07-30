package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.MapSettings
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.layout.GridLayout
import net.vertexdezign.vdt.app.pages.AutoShow
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.pages.PageIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayStoreTest {
  private fun page(id: String) =
    Page(id = id, title = id, icon = PageIcon.Grid, autoShow = AutoShow.Never, layout = GridLayout.empty())

  @Test
  fun parsesTheDisplayParameter() {
    assertEquals(DisplayRequest.Pin("vehicle"), DisplayRequest.parse("?display=vehicle"))
    // The leading ? is optional, and the parameter needn't come first.
    assertEquals(DisplayRequest.Pin("page-1f3k"), DisplayRequest.parse("foo=1&display=page-1f3k"))
  }

  @Test
  fun readsAbsentAndOffApart() {
    // No parameter must not clear a device that was pinned earlier — that's what makes the bare URL
    // keep working on the display.
    assertEquals(DisplayRequest.Absent, DisplayRequest.parse(""))
    assertEquals(DisplayRequest.Absent, DisplayRequest.parse("?other=display"))
    assertEquals(DisplayRequest.Clear, DisplayRequest.parse("?display=off"))
    assertEquals(DisplayRequest.Clear, DisplayRequest.parse("?display=OFF"))
    // A valueless parameter reads as "off" rather than as a page called "".
    assertEquals(DisplayRequest.Clear, DisplayRequest.parse("?display="))
    assertEquals(DisplayRequest.Clear, DisplayRequest.parse("?display"))
  }

  @Test
  fun lastDisplayWins() {
    assertEquals(DisplayRequest.Pin("farm"), DisplayRequest.parse("?display=vehicle&display=farm"))
  }

  @Test
  fun theParameterSetsTheStoredValue() {
    val settings = MapSettings()
    DisplayStore(settings).apply(DisplayRequest.Pin("vehicle"))
    // A later visit without the parameter stays pinned — the whole point of persisting it.
    val reopened = DisplayStore(settings)
    reopened.apply(DisplayRequest.Absent)
    assertEquals("vehicle", reopened.target.value)

    reopened.apply(DisplayRequest.Clear)
    assertNull(reopened.target.value)
    assertNull(DisplayStore(settings).target.value)
  }

  @Test
  fun exitingClearsItForGood() {
    val settings = MapSettings()
    val store = DisplayStore(settings).also { it.pin("farm") }
    store.clear()
    assertNull(store.target.value)
    assertNull(settings.getStringOrNull(DisplayStore.KEY))
  }

  @Test
  fun resolvesPagesBeforeApps() {
    val pages = listOf(page("vehicle"), page("map"))
    assertEquals(Screen.OpenPage("vehicle"), resolveDisplay("vehicle", pages))
    // "map" is both a seeded app id and (here) a page id; the page wins.
    assertEquals(Screen.OpenPage("map"), resolveDisplay("map", pages))
    assertEquals(Screen.OpenApp("map"), resolveDisplay("map", emptyList()))
  }

  @Test
  fun resolvesNothingForAnIdThatIsGone() {
    // The shell renders its own recoverable state for this rather than a black screen.
    assertNull(resolveDisplay("deleted-page", listOf(page("vehicle"))))
  }
}
