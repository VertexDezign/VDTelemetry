package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.i18n.de.German
import net.vertexdezign.vdt.app.i18n.en.English
import net.vertexdezign.vdt.model.BgaBiology
import net.vertexdezign.vdt.model.BgaControl
import net.vertexdezign.vdt.model.BgaFeed
import net.vertexdezign.vdt.model.BgaLevel
import net.vertexdezign.vdt.model.BgaStock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlantDiagramTest {
  // Every token the mod's PnhBgaControl.lua can export. An unknown one falls back to the token
  // itself, made readable — which is what a word missing here would silently show instead.
  private val modes = listOf(
    "INACTIVE", "LIQUID_MONO", "SOLID_MONO", "SOLID_MULTI", "RECIRCULATION", "MULTI_SUBSTRATE",
    "CO_FERMENTATION", "MANURE_LIQUID", "BEET_FERMENTATION",
  )
  private val statuses = listOf(
    "STOPPED", "STARTUP", "COLD", "ECONOMICAL_STABLE", "RECIRC_STABLE", "DRY_OK", "THERMOPHILIC",
    "MESOPHILIC_OPTIMAL", "BUFFERING", "NO_FEED", "SCUM_DRY", "HIGH_VISCOSITY", "HIGH_SHEAR", "ACID_CRASH",
    "AMMONIA_INHIBITION",
  )
  private val trends = listOf("DYING", "STRESSED", "DECAY", "DORMANT", "STABLE", "GROWING")
  private val separatorStates = listOf("INACTIVE", "OPERATING", "FULL", "NO_SILO", "STANDBY")

  @Test
  fun everyTokenTheModExportsHasAWordInBothLanguages() {
    for (strings in listOf(English, German)) {
      modes.forEach { assertNotNull(bgaModeWord(strings, it), "mode $it") }
      statuses.forEach { assertNotNull(bgaStatusWord(strings, BgaControl(status = it)), "status $it") }
      trends.forEach { assertNotNull(bgaTrendWord(strings, it), "trend $it") }
      separatorStates.forEach { assertNotNull(separatorWord(strings, it), "separator $it") }
    }
  }

  @Test
  fun theStatusWordsThatCarryANumberPrintIt() {
    assertEquals(
      "Starting up (40%)",
      bgaStatusLabel(English, BgaControl(status = "STARTUP", biology = BgaBiology(activity = 40))),
    )
    assertEquals("Säuresturz (pH 6,8)", bgaStatusLabel(German, BgaControl(status = "ACID_CRASH", ph = 6.8)))
  }

  @Test
  fun anUnknownTokenStaysReadable() {
    assertEquals("Some new mode", bgaModeLabel(English, "SOME_NEW_MODE"))
  }

  @Test
  fun aFeedLineFlowsOnlyWithStockBehindIt() {
    // The double-fermenter capture: silage feed set, bunker empty; slurry feed set, slurry there.
    val control = BgaControl(
      feed = BgaFeed(silage = 15200, liquid = 22000),
      stock = BgaStock(solid = BgaLevel(0, 115000), liquid = BgaLevel(22892, 5751000)),
    )
    val edges = edgesOf(control, running = true, parts = emptyMap()).associate { (it.from to it.to) to it.active }
    assertEquals(false, edges[Node.SOLID to Node.FERMENTER])
    assertEquals(true, edges[Node.LIQUID to Node.FERMENTER])
    // And nothing flows into a stopped plant, stock or not.
    val stopped = edgesOf(control, running = false, parts = emptyMap()).associate { (it.from to it.to) to it.active }
    assertEquals(false, stopped[Node.LIQUID to Node.FERMENTER])
  }
}
